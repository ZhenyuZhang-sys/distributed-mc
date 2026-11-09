"""游戏内来源上色和算力面板的端到端（docs/overlay-plan.md 第 8 节，第一期只做这一条）：真客户端（HeadlessMC）开 overlay 后
收到来源快照和增量，算力面板拿到 worker 的 STATUS，关掉以后服务器不再发。渲染只做冒烟（无头客户端不真正画），看日志没有异常。

客户端装正式的客户端 mod + Fabric API + 测试小 mod（同意流程照第 9 步走，worker 会起来）；测试小 mod 里的 OverlayTestHook 按系统属性：
进服 ON 秒后像玩家打 /mcdonate show 那样打开两样，HUD 秒时把算力面板的几行字记进日志，OFF 秒时关掉，之后记下关掉以后又收到了几包。
飞行用 /bench fly 推着这个真玩家往前飞（服务器那边移动玩家），视野里不断有新 chunk，才有增量。
"""
import re
import threading
import time

import pytest

from step9.headless_client import TESTHOOKS_JAR, prepare_mods, run_client

pytestmark = pytest.mark.long

NAME = "overlay1"
ON, HUD, OFF, LEAVE, STAY = 20, 150, 160, 200, 230


@pytest.fixture(scope="module")
def run(server, tmp_path_factory):
    base = server.artifact_dir or tmp_path_factory.mktemp("client")
    mods = prepare_mods(base / "client-mods-overlay", extra=[TESTHOOKS_JAR])

    def fly():
        # 等这个玩家上线、overlay 打开以后，推着他往 +x 飞 90 秒（鞘翅速度的一半，服务器跟得上，不卡死客户端）
        deadline = time.monotonic() + 600
        while time.monotonic() < deadline and NAME not in server.rcon("list"):
            time.sleep(2)
        time.sleep(ON + 10)
        server.rcon(f"bench fly start {NAME} 15 90 overlay-e2e")

    t = threading.Thread(target=fly, daemon=True)
    t.start()
    props = (f"-Dmcdonate.testhooks.overlayOnAfter={ON} -Dmcdonate.testhooks.hudLogAfter={HUD} "
             f"-Dmcdonate.testhooks.overlayOffAfter={OFF} -Dmcdonate.testhooks.leaveAfter={LEAVE}")
    return run_client(NAME, STAY, mods, base / "client-overlay", env={"CLIENT_JVM_ARGS": props})


def lines(run, needle):
    return [l for l in run["log"].splitlines() if needle in l]


def test_snapshot_and_increments_arrive(run, server):
    assert lines(run, "测试钩子：打开 overlay 和 hud"), "测试钩子没打开 overlay"
    snap = lines(run, "overlay 收到来源快照")
    assert snap, [l for l in run["log"].splitlines() if "overlay" in l][-20:]
    assert int(re.search(r"快照：(\d+) 个 chunk", snap[0]).group(1)) > 0, snap[0]   # 打开以前生成的 chunk 也在快照里
    assert lines(run, "overlay 收到来源增量"), "飞行中没收到增量"
    assert f"overlay 订阅：{NAME} overlay=true hud=true" in server.logs()


def test_hud_reads_worker_status(run):
    hud = lines(run, "测试钩子：算力面板")
    assert hud, "测试钩子没记算力面板"
    text = hud[-1]
    assert "worker 没在跑" not in text and ("在跑" in text or "暂停" in text), text   # STATUS 回来了（11-09 修过 stdout 前缀）
    # 测试服务器自己的 worker、worker2 加上这个客户端起的 worker（11-09 第一次跑：3 个）
    assert int(re.search(r"在线 donor (\d+)", text).group(1)) >= 1, text


def test_off_stops_sending(run, server):
    off = run["log"].splitlines().index(lines(run, "测试钩子：关掉 overlay 和 hud")[0])
    after = [l for l in run["log"].splitlines()[off:] if "overlay 收到来源" in l]
    assert not after, after
    late = lines(run, "测试钩子：关掉以后又收到")
    assert late and int(re.search(r"又收到 (\d+) 包", late[-1]).group(1)) <= 1, late   # 最多一包已经在路上
    assert f"overlay 订阅：{NAME} overlay=false hud=false" in server.logs()


MIXIN_FAIL = re.compile(r"Mixin apply failed|InvalidInjectionException|MixinTransformerError|mcdonate-common\.mixins\.json.*(rror|ail)")
OUR_ERROR = re.compile(r"/(ERROR|WARN)\]: .*(mcdonate|overlay|[Mm]ixin)")


def test_no_mixin_or_render_errors(run):
    # 只看我们的：common 的 mixin 在客户端没有找不到目标的报错、mcdonate / overlay 没有 ERROR、WARN。
    #（11-09 第一次跑：HeadlessMC 没有朗读用的 libflite.so，那几行是原版 text2speech 的，不算）
    bad = [l for l in run["log"].splitlines() if MIXIN_FAIL.search(l) or OUR_ERROR.search(l)]
    assert not bad, bad[:10]
