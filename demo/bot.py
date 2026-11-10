#!/usr/bin/env python3
"""演示服务器的聊天命令（盯日志里玩家说的 !xxx）。也能在 shell 里调：python3 -c 'import bot; bot.ab(1)'。
固定路线（录视频）：从 (10000,10000) 往东；每个世界只能飞一次，换模式前 !reset。
换路线对比（!ab）：每条路线往北挪 2000 格（z = 12000, 14000, ...），不用重开世界。"""
import collections, json, os, re, subprocess, threading, time
os.chdir(os.path.dirname(os.path.abspath(__file__)))
DC = ["docker", "compose"]
FIX_X, FIX_Z = 10000, 10000
VIEW = int(next((l.split("=")[1] for l in open(".env") if l.startswith("MC_VIEW_DISTANCE=")), "10"))
state = {"mode": "donor"}
busy = threading.Lock()

def rcon(*c):
    return subprocess.run(DC + ["exec", "-T", "minecraft", "rcon-cli", *c], capture_output=True, text=True, timeout=60).stdout

def say(msg):
    for line in msg.splitlines():
        rcon("tellraw", "@a", json.dumps({"text": "[demo] " + line, "color": "aqua"}, ensure_ascii=False))

def status():
    return json.loads(rcon("donor", "status").strip().splitlines()[0])

def flags():
    try: return json.load(open("data/world/demo-routes.json"))
    except Exception: return {}

def mark(key, what):
    f = flags(); f[key] = what; json.dump(f, open("data/world/demo-routes.json", "w"))

def fmt(r):
    p = r.get("provenance") or {}
    d = p.get("resumed", 0); tot = max(1, d + p.get("vanilla", 0))
    return (f"{r['tag']}: blank {r['void_mean']:.0%} on average (last 10 s {r['void_last10']:.0%}), stuck {r['stall_s']} s, "
            f"flew {r['distance']}/{r['ideal']} blocks, donor share {d/tot:.0%}, longest tick {r['mspt_max']} ms")

def prep(player, secs, speed, x, z, ahead=None, only=None):
    """donor 把这条往东的路线提前算好：玩家先到 (0,0)，服务器不占着那片。"""
    key = f"{x},{z}"
    if key in flags():
        say(f"Route {key} was already used in this world; pick another or !reset"); return False
    if not status()["workers"]:
        say("No donor connected: turn on the donor in mc-donate settings first"); return False
    x0, z0 = int(x) // 16, int(z) // 16; L = ahead if ahead is not None else int(secs * speed / 16) + 30; W = VIEW + 14  # 视距 + 原版外圈 11 + 余量 3
    rcon("gamemode", "spectator", player); rcon("tp", player, "0", "160", "0")
    rcon("donor", "demand", "off"); rcon("donor", "reset")  # 清掉自动找需求排的、和这条路线无关的任务
    mark(key, "prep")
    tiles = sorted((tx, tz) for tx in range((x0 - W) // 4, (x0 + L) // 4 + 1) for tz in range((z0 - W) // 4, (z0 + W) // 4 + 1))
    say(f"Donor is precomputing route {key}: {len(tiles)} tiles. Waiting at (0,0)")
    p = subprocess.Popen(DC + ["exec", "-T", "minecraft", "rcon-cli"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
    ws = sorted(w["id"] for w in status()["workers"])
    if only is not None:
        for tx, tz in tiles: p.stdin.write(f"donor assign {tx} {tz} {only}\n")
    elif len(ws) >= 2:
        # 多个 donor：按 x 切成连续的几段，每段整块给一个 donor，只留几条接缝（11-10 两台交替派时接缝太多，1 核服务器扛不住）
        cols = sorted({tx for tx, _ in tiles}); seg = -(-len(cols) // len(ws))
        owner = {tx: ws[min(i // seg, len(ws) - 1)] for i, tx in enumerate(cols)}
        for tx, tz in tiles: p.stdin.write(f"donor assign {tx} {tz} {owner[tx]}\n")
        say(f"{len(ws)} donors: route split into {len(ws)} segments along x")
    else:
        for tx, tz in tiles: p.stdin.write(f"donor enqueue {tx} {tz}\n")
    p.stdin.close(); p.wait()
    t = time.time(); last = 0; stuck = 0
    while time.time() - t < 900:
        time.sleep(5)
        try: st = status()
        except Exception: continue
        if not st["workers"]:
            say("Donor disconnected, stopping"); return False
        left = st["tasks"]["queued"] + st["tasks"]["leased"]
        idle = st["tasks"]["leased"] == 0 and all(w["inFlight"] == 0 for w in st["workers"])
        stuck = stuck + 1 if (idle and 0 < left <= 5) else 0
        if left == 0 or stuck >= 3: break
        if time.time() - last > 20: say(f"Donor precomputing: {left}/{len(tiles)} left"); last = time.time()
    rcon("donor", "reset")
    cx0, cx1 = min(t[0] for t in tiles) * 4, max(t[0] for t in tiles) * 4 + 3
    cz0, cz1 = min(t[1] for t in tiles) * 4, max(t[1] for t in tiles) * 4 + 3
    f = flags(); f.setdefault("_rect", {})[key] = [cx0, cz0, cx1, cz1]; json.dump(f, open("data/world/demo-routes.json", "w"))
    send_rect(player, key)
    say(f"Route ready ({time.time() - t:.0f} s): precomputed up to x={cx1 * 16 + 15}; east of that is computed live (yellow box on the minimap)"); return True

def send_rect(player, key):
    r = flags().get("_rect", {}).get(key)
    if r: rcon("tellraw", player, json.dumps({"text": "[demo-prep] %d %d %d %d" % tuple(r)}))

def fly(player, secs, speed, x, z, mode):
    key = f"{x},{z}"; f = flags()
    if f.get(key) == "flown":
        say(f"Route {key} was already flown in this world; pick another or !reset"); return None
    if mode == "server":
        if key in f: say(f"Route {key} was already computed by the donor; it cannot be used for server-only"); return None
        rcon("donor", "demand", "off"); rcon("donor", "reset")
    elif mode == "warm":   # 只提前算起点那一段（起飞时服务器会接管的圈），后面边飞边算
        if key in f: say(f"Route {key} was already used; !reset first"); return None
        if not prep(player, secs, speed, x, z, ahead=VIEW + 16): return None
        rcon("donor", "demand", "on")
    elif mode == "live":   # 边飞边算：不提前算，donor 按飞行方向实时派
        if key in f: say(f"Route {key} was already used"); return None
        rcon("donor", "reset"); rcon("donor", "demand", "on")
    else:
        if f.get(key) != "prep" and not prep(player, secs, speed, x, z): return None
        rcon("donor", "demand", "on")
    mark(key, "flown")
    send_rect(player, key)
    say(f"{mode}: start ({x},{z}); waiting for the area to load, then flying east at {speed:g} blocks/s for {secs:g} s")
    out = subprocess.run(["./benchfly.py", "--secs", str(secs), "--speed", str(speed), "--x", str(x), "--z", str(z),
                          "--player", player, "--tag", f"{mode}-z{z}"], capture_output=True, text=True).stdout.strip().splitlines()
    try:
        r = json.loads(out[-1]); say("Done. " + fmt(r)); return r
    except Exception:
        say("Flight error: " + (out[-1] if out else "no output")); return None

def next_z():
    used = [int(k.split(",")[1]) for k in flags() if not k.startswith("_")]
    z = 12000
    while z in used: z += 2000
    return z

def ab(rounds=1, secs=60, speed=20, player="ababfish"):
    """换路线对比：每轮先只靠服务器飞一条新路线，再 donor 预先算好另一条新路线再飞。"""
    res = []
    for i in range(int(rounds)):
        z1 = next_z(); a = fly(player, secs, speed, FIX_X, z1, "server")
        z2 = next_z(); b = fly(player, secs, speed, FIX_X, z2, "donor")
        if a and b:
            res.append((a, b))
            say(f"Round {i+1}: server only blank {a['void_mean']:.0%}, stuck {a['stall_s']} s | donor blank {b['void_mean']:.0%}, stuck {b['stall_s']} s")
    return res

def ab3(secs=60, speed=20, player="ababfish"):
    """三条新路线：只靠服务器 / donor 边飞边算 / donor 提前算好。"""
    out = {}
    for mode in ("server", "live", "donor"):
        out[mode] = fly(player, float(secs), float(speed), FIX_X, next_z(), mode)
    say(" | ".join(f"{m} blank {r['void_mean']:.0%} stuck {r['stall_s']}s" for m, r in out.items() if r))
    return out

def players():
    m = re.search(r"online: (.*)$", rcon("list").strip())
    return [x.strip() for x in m.group(1).split(",") if x.strip()] if m else []

def group(secs, speed, mode, gap=5):
    """所有在线玩家并排（z 方向相隔 gap 格）从同一个起点同时往东飞，速度一样。"""
    ps = players()
    if not ps: say("No players online"); return
    x, z = FIX_X, FIX_Z; key = f"{x},{z}"; f = flags()
    if f.get(key) == "flown": say(f"Route {key} was already flown; !reset first"); return
    if mode == "server":
        rcon("donor", "demand", "off"); rcon("donor", "reset")
    elif mode in ("donor", "warm"):
        if f.get(key) != "prep" and not prep(ps[0], secs, speed, x, z, ahead=(VIEW + 16) if mode == "warm" else None): return
        rcon("donor", "demand", "on")
    else:
        rcon("donor", "reset"); rcon("donor", "demand", "on")
    mark(key, "flown")
    for i, p in enumerate(ps):
        rcon("gamemode", "spectator", p); rcon("tp", p, str(x), "130", str(z + gap * i), "270", "10"); send_rect(p, key)
    say(f"{mode}: {len(ps)} players side by side ({gap} blocks apart), waiting for the area to load")
    t = time.time()
    while time.time() - t < 60:
        time.sleep(2)
        try:
            if all(json.loads(rcon("bench", "fly", "ready", p).splitlines()[0]).get("not_ready", 1) == 0 for p in ps): break
        except Exception: pass
    stamp = time.strftime("%H%M%S"); runs = [f"{mode}-g{i}-{stamp}" for i in range(len(ps))]
    pipe = subprocess.Popen(DC + ["exec", "-T", "minecraft", "rcon-cli"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
    for p, r in zip(ps, runs): pipe.stdin.write(f"bench fly start {p} {speed:g} {int(secs)} {r} wall\n")
    pipe.stdin.close(); pipe.wait()
    say(f"Flying east at {speed:g} blocks/s for {secs:g} s")
    time.sleep(secs + 4)
    for p, r in zip(ps, runs):
        try:
            rows = [json.loads(l) for l in open(f"data/bench-runs/{r}/seconds.jsonl")]; d = json.load(open(f"data/bench-runs/{r}/done.json"))
            vm = sum(x["void_rate"] for x in rows) / len(rows)
            say(f"{p}: blank {vm:.0%} on average, stuck {d.get('stall_ms', 0)/1000:.1f} s, flew {d.get('distance', 0):.0f} blocks")
        except Exception as e: say(f"{p}: no result ({e})")

def pos(p):
    m = re.search(r"\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]", rcon("data", "get", "entity", p, "Pos"))
    return (float(m.group(1)), float(m.group(3))) if m else None

def duo(secs, speed, seg=8, la=14, gap=5):
    """两台以上 donor 按固定大段轮流算：起点那段全给最快的一台提前算好，起飞后脚本按玩家位置往前派，
    第 k 段（seg 列 tile）给第 k % n 台；提前量 la 列 tile。服务器自己的找需求关掉。"""
    ps = players(); st = status()
    # 起点那段给测过速度里最快的；都没测过就给连得最久（编号最小）的
    ws = [w["id"] for w in sorted(st["workers"], key=lambda w: (w["done"] < 5, w["avgMs"] if w["done"] >= 5 else w["id"]))]
    if not ps: say("No players online"); return
    if len(ws) < 2: say("!duo needs at least 2 donors connected"); return
    x, z = FIX_X, FIX_Z; key = f"{x},{z}"
    if key in flags(): say(f"Route {key} was already used; !reset first"); return
    start_chunks = VIEW + 16
    if not prep(ps[0], secs, speed, x, z, ahead=start_chunks, only=ws[0]): return
    rcon("donor", "demand", "off"); rcon("donor", "reset"); mark(key, "flown")
    W = VIEW + 14; z0 = int(z) // 16; tzs = range((z0 - W) // 4, (z0 + W) // 4 + 1)
    next_col = (int(x) // 16 + start_chunks) // 4 + 1; base = next_col
    for i, p in enumerate(ps):
        rcon("gamemode", "spectator", p); rcon("tp", p, str(x), "130", str(z + gap * i), "270", "10")
    say(f"duo: {len(ws)} donors share the route in segments of {seg * 64} blocks (faster donor gets more), look-ahead {la * 64} blocks")
    t = time.time()
    while time.time() - t < 60:
        time.sleep(2)
        try:
            if all(json.loads(rcon("bench", "fly", "ready", p).splitlines()[0]).get("not_ready", 1) == 0 for p in ps): break
        except Exception: pass
    pipe = subprocess.Popen(DC + ["exec", "-T", "minecraft", "rcon-cli"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, text=True)
    stamp = time.strftime("%H%M%S"); runs = [f"duo-g{i}-{stamp}" for i in range(len(ps))]
    for p, r in zip(ps, runs): pipe.stdin.write(f"bench fly start {p} {speed:g} {int(secs)} {r} wall\n")
    pipe.stdin.flush()
    say(f"Flying east at {speed:g} blocks/s for {secs:g} s")
    t0 = time.time(); sent = collections.Counter(); cols = collections.Counter(); cur = None; left = 0; last_w = 0; weight = {}
    while time.time() - t0 < secs + 2:
        if time.time() - last_w > 3:   # 每 3 秒按实测速度更新权重：lanes / 每块耗时
            try:
                cur_ws = status()["workers"]
                known = {w["id"]: max(1, w.get("lanes") or 1) / max(100, w["avgMs"]) for w in cur_ws if w["done"] >= 5}
                fill = max(known.values()) if known else 1.0
                weight = {w["id"]: known.get(w["id"], fill) for w in cur_ws}   # 还没测出速度的按快的算
            except Exception: pass
            last_w = time.time()
        q = pos(ps[0])
        if q:
            pc = int(q[0]) // 64
            while next_col <= pc + la:
                if left == 0:   # 新的一段：给"已分到的列数 / 速度"最小的那台（快的多分）
                    total = sum(cols.values()) + seg
                    starved = [w for w in ws if (cols[w] + seg) * len(ws) * 1.5 < total]   # 少于平均份额的 2/3：先给它
                    cur = starved[0] if starved else min(ws, key=lambda w: (cols[w] + seg) / weight.get(w, 1e-9)); left = seg
                for tz in tzs: pipe.stdin.write(f"donor assign {next_col} {tz} {cur}\n")
                sent[cur] += len(tzs); cols[cur] += 1; left -= 1; next_col += 1
            pipe.stdin.flush()
        time.sleep(1)
    pipe.stdin.close(); pipe.wait(); time.sleep(3)
    say("duo tiles per donor: " + ", ".join(f"worker {w}: {n}" for w, n in sent.items()))
    for p, r in zip(ps, runs):
        try:
            rows = [json.loads(l) for l in open(f"data/bench-runs/{r}/seconds.jsonl")]; d = json.load(open(f"data/bench-runs/{r}/done.json"))
            say(f"{p}: blank {sum(x['void_rate'] for x in rows) / len(rows):.0%} on average, stuck {d.get('stall_ms', 0)/1000:.1f} s, flew {d.get('distance', 0):.0f} blocks")
        except Exception as e: say(f"{p}: no result ({e})")

def run(player, cmd, args):
    num = lambda i, d: float(args[i]) if len(args) > i else d
    if cmd == "help":
        say("Fixed route: !server, !donor (precompute all), !warm (precompute start) or !live, then !fly [secs=60] [speed=20]; !reset before switching modes\n"
            "Side by side (all players): mode command, then !group [secs] [speed]\n"
            "Two donors in turns: !reset, then !duo [secs=60] [speed=20] [segment tiles=8] [look-ahead tiles=14]\n"
            "Parallel routes: !ab [rounds=1] [secs=60] [speed=20] | !reset fresh world (disconnects) | !stats [secs] | !last")
    elif cmd in ("server", "donor", "live", "warm"):
        state["mode"] = cmd
        say("Mode: " + {"server": "server only", "donor": "donor precomputes the whole route", "live": "donor computes live (no precompute)",
                         "warm": "donor precomputes the start, then computes live"}[cmd])
    elif cmd == "fly":
        fly(player, num(0, 60), num(1, 20), FIX_X, FIX_Z, state["mode"])
    elif cmd == "duo":
        duo(num(0, 60), num(1, 20), int(num(2, 8)), int(num(3, 14)))
    elif cmd == "group":
        group(num(0, 60), num(1, 20), state["mode"])
    elif cmd == "ab":
        ab(num(0, 1), num(1, 60), num(2, 20), player)
    elif cmd == "reset":
        say("Starting a fresh world in 10 s; the server restarts for about 1 minute, then reconnect"); time.sleep(10)
        subprocess.run(["./demo.sh", "reset"], capture_output=True)
    elif cmd == "stats":
        say(subprocess.run(["./demo.sh", "stats", args[0] if args else "30"], capture_output=True, text=True).stdout.strip())
    elif cmd == "last":
        try: say(fmt(json.loads(open("runs.jsonl").read().strip().splitlines()[-1])))
        except Exception: say("No results yet")
    else:
        say("Unknown command, type !help")

def handle(player, cmd, args):
    if not busy.acquire(blocking=False):
        say("The previous command is still running, please wait"); return
    try: run(player, cmd, args)
    except Exception as e: say(f"Error: {e}")
    finally: busy.release()

if __name__ == "__main__":
    PAT = re.compile(r"\]: <(\w+)> !(\w+)\s*(.*)$")
    while True:
        p = subprocess.Popen(DC + ["logs", "-f", "--since", "1s", "--no-log-prefix", "minecraft"], stdout=subprocess.PIPE, text=True)
        for line in p.stdout:
            m = PAT.search(line.strip())
            if m:
                threading.Thread(target=handle, args=(m.group(1), m.group(2).lower(), m.group(3).split()), daemon=True).start()
        time.sleep(3)
