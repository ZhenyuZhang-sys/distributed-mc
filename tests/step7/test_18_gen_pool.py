"""11-10 kexu：测试的 docker worker 不走客户端，原来没有 -XX:ActiveProcessorCount，生成线程池按容器核数 − 1
（2 核 worker 只有 1 个生成线程）；真客户端是池 = 设的线程数（WorkerLaunch：ActiveProcessorCount = threads + 1）。
codex-pipe 2×2（2736827，总 4 核）：池 1+1 时 0.83 tile/s，加到 2+2 后 1.43～1.50 tile/s。

改成和真客户端一样：worker、worker2 的生成线程默认 = WORKER_CPUS（取整、至少 1），WORKER_GEN_THREADS 可以单独设；
worker3 按 WORKER3_CPUS、WORKER3_GEN_THREADS。scripts/lib.sh 的 tc 算好 ActiveProcessorCount 交给 compose。
worker 在心跳里报实际看到的核数（cpus）和生成池大小（genPool），/donor status 显示，测量驱动每轮核对。
"""
import json
import math
import os
import re
import subprocess

from mcd import REPO_ROOT, compose, limits, wait_until, worker_id


def config(profiles: str, **env) -> dict:
    saved = os.environ.copy()
    try:
        os.environ["COMPOSE_PROFILES"] = profiles
        os.environ.update(env)
        return json.loads(compose("config", "--format", "json"))["services"]
    finally:
        os.environ.clear()
        os.environ.update(saved)


def apc(svc: dict) -> int:
    m = re.search(r"-XX:ActiveProcessorCount=(\d+)", svc["environment"]["JVM_OPTS"])
    assert m, svc["environment"]["JVM_OPTS"]
    return int(m.group(1))


def threads_for(cpus) -> int:
    return max(1, math.ceil(float(cpus)))


def expected_worker_threads() -> int:
    """这个槽位 worker 应有的生成线程数：WORKER_GEN_THREADS，没设就是 WORKER_CPUS 取整（默认 4）。"""
    lim = limits()
    g = os.environ.get("WORKER_GEN_THREADS") or lim.get("WORKER_GEN_THREADS")
    return int(g) if g else threads_for(os.environ.get("WORKER_CPUS") or lim.get("WORKER_CPUS") or 4)


def test_compose_gives_workers_client_like_thread_pool():
    s = config("worker,worker2,worker3")
    w = expected_worker_threads()
    assert apc(s["worker"]) == w + 1, s["worker"]["environment"]["JVM_OPTS"]
    assert apc(s["worker2"]) == w + 1, "worker2 和 worker 一样"
    assert apc(s["worker3"]) == threads_for(s["worker3"]["cpus"]) + 1, "worker3 按自己的核数（0.5 核 → 1 个）"


def test_overrides_follow_cpus_and_explicit_threads():
    assert apc(config("worker", WORKER_CPUS="2")["worker"]) == 3, "2 核：2 个生成线程"
    assert apc(config("worker", WORKER_CPUS="8")["worker"]) == 9, "kexu 1×8"
    assert apc(config("worker", WORKER_CPUS="4", WORKER_GEN_THREADS="6")["worker"]) == 7, "单独设线程数"
    assert apc(config("worker3", WORKER3_CPUS="4")["worker3"]) == 5, "kexu 3×4 的 worker3"


# ---- codex-audit 第二十二节：tc 取值要和 compose 看到的一样（L1 引号/注释/插值，L2 后层空值覆盖，L3 没 export 的变量）----

def local_env(tmp_path, text: str) -> str:
    """一个临时的 .env.local（MCD_LOCAL_ENV 指过去）：只影响这次 compose 渲染。"""
    f = tmp_path / "env.local"
    f.write_text(text)
    return str(f)


def test_dotenv_syntax_is_parsed_like_compose(tmp_path):
    # L1：合法的 dotenv：引号、行尾注释、插值。原来引号报"要是正整数"、连 tc down 都失败；WORKER_CPUS="2" 被算成 1 条
    assert apc(config("worker", MCD_LOCAL_ENV=local_env(tmp_path, 'WORKER_GEN_THREADS="6"\n'))["worker"]) == 7
    assert apc(config("worker", MCD_LOCAL_ENV=local_env(tmp_path, 'WORKER_CPUS="2"\n'))["worker"]) == 3
    assert apc(config("worker", MCD_LOCAL_ENV=local_env(tmp_path, "WORKER_GEN_THREADS=5 # 生成线程\n"))["worker"]) == 6
    assert apc(config("worker", MCD_LOCAL_ENV=local_env(tmp_path, "WORKER_CPUS=2\nWORKER_GEN_THREADS=${WORKER_CPUS}\n"))["worker"]) == 3


def tc_config(script: str, **env) -> dict:
    """source lib.sh 后跑一段 bash（最后是 tc config），返回渲染出来的 services。打开 worker、worker3 的 profile。"""
    e = {k: v for k, v in os.environ.items() if not k.startswith(("WORKER", "MCD_WORKER"))}
    e.update(env, COMPOSE_PROFILES="worker,worker2,worker3")
    out = subprocess.run(["bash", "-c", f"source scripts/lib.sh; {script}"], capture_output=True, text=True, cwd=REPO_ROOT, env=e)
    assert out.returncode == 0, out.stderr
    return json.loads(out.stdout)["services"]


def test_empty_value_overrides_earlier_one(tmp_path):
    # L2：后一层 env 文件写了空值就是空（compose 的 ${X:-默认} 取默认），不保留前一层的
    early = tmp_path / "early.env"
    early.write_text("WORKER_CPUS=8\nWORKER3_CPUS=4\nWORKER_GEN_THREADS=8\n")
    late = tmp_path / "late.env"
    late.write_text("WORKER_CPUS=\nWORKER3_CPUS=\nWORKER_GEN_THREADS=\n")
    s = tc_config(f'LIMITS_ENV="{early}"; LOCAL_ENV="{late}"; tc config --format json')
    assert s["worker3"]["cpus"] == 0.5 and apc(s["worker3"]) == 2, ("worker3 回到默认 0.5 核", s["worker3"]["cpus"], apc(s["worker3"]))
    assert s["worker"]["cpus"] == 4 and apc(s["worker"]) == 5, ("worker 回到默认 4 核", s["worker"]["cpus"], apc(s["worker"]))


def test_unexported_shell_variable_is_not_compose_env():
    # L3：没 export 的 Bash 变量 compose 看不到，派生值也不能用它；export 的照常
    s = tc_config("WORKER_CPUS=1; tc config --format json")
    assert apc(s["worker"]) == threads_for(s["worker"]["cpus"]) + 1, (s["worker"]["cpus"], apc(s["worker"]))
    s = tc_config("export WORKER_CPUS=2; tc config --format json")
    assert s["worker"]["cpus"] == 2 and apc(s["worker"]) == 3


def test_bad_thread_setting_does_not_block_non_creating_commands(tmp_path):
    # L1：down、ps、exec 这些不建容器的不算线程参数，写错了也不挡（不然所有槽位的测试清理都会失败）
    env = dict(os.environ, MCD_LOCAL_ENV=local_env(tmp_path, "WORKER_GEN_THREADS=abc\n"))
    out = subprocess.run(["bash", "-c", "source scripts/lib.sh; tc ps --services >/dev/null"], capture_output=True, text=True,
                         cwd=REPO_ROOT, env=env)
    assert out.returncode == 0, out.stderr
    bad = subprocess.run(["bash", "-c", "source scripts/lib.sh; tc config >/dev/null"], capture_output=True, text=True,
                         cwd=REPO_ROOT, env=env)
    assert bad.returncode != 0 and "生成线程数" in bad.stderr, "建容器、渲染时照样报错"


def me(server, wid):
    return next(w for w in json.loads(server.rcon("donor status"))["workers"] if w["id"] == wid)


def test_worker_reports_cpus_and_generation_pool(server, worker):
    wid = worker_id(server, "worker")
    wait_until(lambda: me(server, wid).get("genPool") is not None, timeout=30, interval=1, what="worker 报了生成池大小")
    w = me(server, wid)
    print({k: w.get(k) for k in ("cpus", "genPool", "threads")})
    want = expected_worker_threads()
    assert w["genPool"] == want, f"生成池 = 设的线程数 {want}：{w}"
    assert w["cpus"] == want + 1, w
