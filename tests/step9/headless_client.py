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


def run_client(name: str, seconds: int, mods: Path, out: Path) -> dict:
    """进服挂 seconds 秒。返回 {"log": 客户端日志, "ps": 进程快照, "gamedir": 游戏目录列表, "mods": 装了什么}。"""
    out.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    try:
        os.environ["MCD_CLIENT_MODS"] = str(mods)
        os.environ["MCD_CLIENT_OUT"] = str(out)
        compose("run", "--rm", "client", name, str(seconds), timeout=1800)  # 第一次要下载客户端，慢
    finally:
        os.environ.clear()
        os.environ.update(env)
    assert (out / "done").exists(), "客户端脚本没跑完"
    read = lambda f: (out / f).read_text(errors="replace") if (out / f).exists() else ""
    return {"log": read("latest.log"), "ps": read("ps.txt"), "gamedir": read("gamedir.txt"), "mods": read("mods.txt"),
            "launcher": read("launcher.log")}


def java_processes(ps: str) -> list[str]:
    return [l for l in ps.splitlines() if " java " in f" {l} " or "/java " in l]


def worker_names(server) -> set[str]:
    return {w["name"] for w in json.loads(server.rcon("donor status"))["workers"]}
