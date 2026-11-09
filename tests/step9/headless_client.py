"""第 9 步：起一次无头的真客户端（docker/client，HeadlessMC 2.10.0），返回它留下的证据。"""

import json
import os
import shutil
from pathlib import Path

from mcd import REPO_ROOT, compose, data_root

CLIENT_JAR = REPO_ROOT / "client/build/libs/mcdonate-client-0.1.0.jar"


def prepare_mods(dest: Path, extra: list[Path] = ()) -> Path:
    """这一轮客户端要装的 mod：正式的客户端 mod jar + Fabric API（和服务器用同一个 jar）+ extra。"""
    dest.mkdir(parents=True, exist_ok=True)
    fabric_api = sorted((data_root("minecraft") / "mods").glob("fabric-api-*.jar"))
    assert fabric_api, "服务器的 mods 里没有 Fabric API"
    for jar in [CLIENT_JAR, fabric_api[-1], *extra]:
        shutil.copy2(jar, dest / jar.name)
    return dest


def run_client(name: str, seconds: int, mods: Path, out: Path, env: dict | None = None) -> dict:
    """进服挂 seconds 秒。返回 {"log": 客户端日志, "ps": 进程快照, "gamedir": 游戏目录列表, "mods": 装了什么}。"""
    out.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    try:
        os.environ["MCD_CLIENT_MODS"] = str(mods)
        os.environ["MCD_CLIENT_OUT"] = str(out)
        # --build：run.sh 是打进镜像的，改了要重建（有缓存，很快）。第一次要下载客户端，慢
        extra = [a for k, v in (env or {}).items() for a in ("-e", f"{k}={v}")]
        compose("run", "--rm", "--build", *extra, "client", name, str(seconds), timeout=2400)
    finally:
        os.environ.clear()
        os.environ.update(env)
    assert (out / "done").exists(), "客户端脚本没跑完"
    read = lambda f: (out / f).read_text(errors="replace") if (out / f).exists() else ""
    return {"log": read("latest.log"), "ps": read("ps.txt"), "gamedir": read("gamedir.txt"), "mods": read("mods.txt"),
            "launcher": read("launcher.log"), "ps_after_kill": read("ps-after-kill.txt"), "killed": read("killed")}


TESTHOOKS_JAR = REPO_ROOT / "client-testhooks/build/libs/mcdonate-client-testhooks-0.1.0.jar"


def worker_procs(ps_block: str) -> list[str]:
    """进程快照里的 worker 子进程（Fabric 服务器启动器）。"""
    return [l for l in ps_block.splitlines() if "fabric-server-launch.jar" in l and "pgrep" not in l]


def snapshots(ps: str) -> list[tuple[str, str]]:
    """把 ps.txt 按 "== t=…" 分成 (标签, 内容)。"""
    out, label, buf = [], None, []
    for line in ps.splitlines():
        if line.startswith("== "):
            if label is not None:
                out.append((label, "\n".join(buf)))
            label, buf = line[3:], []
        else:
            buf.append(line)
    if label is not None:
        out.append((label, "\n".join(buf)))
    return out


def java_processes(ps: str) -> list[str]:
    return [l for l in ps.splitlines() if " java " in f" {l} " or "/java " in l]


def worker_names(server) -> set[str]:
    return {w["name"] for w in json.loads(server.rcon("donor status"))["workers"]}
