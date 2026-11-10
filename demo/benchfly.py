#!/usr/bin/env python3
"""用 bench mod 的 /bench fly 匀速沿 +x 飞（每 tick 放玩家、精确记视野里没就绪的 chunk）。
用法：./benchfly.py --player ababfish --speed 33.5 --secs 60 --x 10000 --z 10000 --tag donor"""
import argparse, json, os, subprocess, time, glob
os.chdir(os.path.dirname(os.path.abspath(__file__)))
ap = argparse.ArgumentParser()
for k, v in (("speed", 33.5), ("secs", 60), ("x", 10000), ("z", 10000), ("y", 130), ("warmup", 60)):
    ap.add_argument("--" + k, type=float, default=v)
ap.add_argument("--player", default="ababfish"); ap.add_argument("--tag", default="run")
a = ap.parse_args()
def rcon(*c):
    return subprocess.run(["docker", "compose", "exec", "-T", "minecraft", "rcon-cli", *c], capture_output=True, text=True, timeout=60).stdout.strip()
st = json.loads(rcon("donor", "status").splitlines()[0])
rcon("gamemode", "spectator", a.player)
rcon("tp", a.player, str(a.x), str(a.y), str(a.z), "270", "10")
t = time.time()
while time.time() - t < a.warmup:          # 等起点周围就绪
    time.sleep(2)
    try:
        if json.loads(rcon("bench", "fly", "ready", a.player).splitlines()[0]).get("not_ready", 1) == 0: break
    except Exception: pass
run = f"{a.tag}-{time.strftime('%H%M%S')}"
print(rcon("bench", "fly", "start", a.player, str(a.speed), str(int(a.secs)), run, "wall"), flush=True)
time.sleep(a.secs + 3)
for _ in range(30):
    if os.path.exists(f"data/bench-runs/{run}/done.json"): break
    time.sleep(1)
done = json.load(open(f"data/bench-runs/{run}/done.json"))
rows = [json.loads(l) for l in open(f"data/bench-runs/{run}/seconds.jsonl")]
vr = [r["void_rate"] for r in rows]
res = dict(tag=a.tag, run=run, demand=st["demand"], workers=len(st["workers"]), speed=a.speed, secs=int(a.secs),
           void_mean=round(sum(vr) / len(vr), 3), void_max=round(max(r["void_rate_max"] for r in rows), 3),
           void_last10=round(sum(vr[-10:]) / len(vr[-10:]), 3),
           distance=round(done.get("distance", 0)), ideal=round(done.get("ideal_distance") or a.speed * a.secs),
           stall_s=round(done.get("stall_ms", 0) / 1000, 1), mspt_max=round(max(r["mspt_max"] for r in rows)),
           provenance=done.get("provenance"), counters=done.get("counters"),
           per_s=[round(v, 2) for v in vr])
print(json.dumps(res, ensure_ascii=False))
with open("runs.jsonl", "a") as f: f.write(json.dumps(res, ensure_ascii=False) + "\n")
