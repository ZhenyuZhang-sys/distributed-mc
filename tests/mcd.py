"""测试用的公共工具：通过 scripts/ 操作测试服务器。"""

from __future__ import annotations

import os
import re
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = REPO_ROOT / "scripts"
TEST_DATA = REPO_ROOT / "docker" / "test-data"
WHITELIST = Path(__file__).resolve().parent / "log_whitelist.txt"

# Minecraft 的颜色代码，比如 §a
_COLOR = re.compile(r"§.")
# 日志里算作出错的行
_BAD_LINE = re.compile(r"/ERROR\]|Exception[:\s]|^\s+at [\w.$]+\(")


def run(*cmd: str, timeout: float = 120) -> str:
    """运行命令，失败时把输出带进异常信息。"""
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} 退出码 {p.returncode}\n{p.stdout}\n{p.stderr}")
    return p.stdout


def compose(*args: str, timeout: float = 120) -> str:
    """对测试服务器执行 docker compose（复用 lib.sh 里的 tc）。"""
    script = 'source "$1"; shift; tc "$@"'
    return run("bash", "-c", script, "bash", str(SCRIPTS / "lib.sh"), *args, timeout=timeout)


def limits() -> dict[str, str]:
    """测试服务器的资源限制：docker/n100.env，环境变量优先。"""
    out = {}
    for line in (REPO_ROOT / "docker" / "n100.env").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k] = os.environ.get(k, v)
    return out


def wait_until(check, timeout: float, interval: float = 1.0, what: str = "条件"):
    """每隔 interval 秒调用一次 check()，直到返回真值。超时报错。"""
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        if time.monotonic() > deadline:
            raise TimeoutError(f"等待 {what} 超过 {timeout} 秒")
        time.sleep(interval)


def chunky(server: "Server", x: int, z: int, radius: int, timeout: float = 900) -> int:
    """用 Chunky 生成以 (x, z) 为中心、半径 radius 格的方形，返回 Chunky 报告的 chunk 数。"""
    pattern = r"Task finished for minecraft:overworld\. Processed: (\d+) chunks"
    # 同一轮里可能已经跑过 Chunky，等新增的那条完成记录
    before = len(re.findall(pattern, server.logs()))
    for cmd in ["chunky world minecraft:overworld", "chunky shape square",
                f"chunky center {x} {z}", f"chunky radius {radius}", "chunky start"]:
        server.rcon(cmd)
    return wait_until(lambda: len(d := re.findall(pattern, server.logs())) > before and int(d[-1]),
                      timeout=timeout, interval=1, what="Chunky 完成")


@dataclass
class Server:
    level: str
    seed: int
    started_at: str  # RFC3339，用来只看这次测试以来的日志

    @property
    def world_dir(self) -> Path:
        return TEST_DATA / self.level

    def rcon(self, command: str) -> str:
        out = run(str(SCRIPTS / "rcon.sh"), *command.split())
        return _COLOR.sub("", out).strip()

    def logs(self) -> str:
        return compose("logs", "--no-log-prefix", f"--since={self.started_at}", "minecraft")

    def health(self) -> str:
        cid = compose("ps", "-q", "minecraft").strip()
        if not cid:
            return "not running"
        return run("docker", "inspect", "-f", "{{.State.Health.Status}}", cid).strip()

    def restart(self) -> None:
        compose("stop", timeout=180)
        compose("up", "-d", "--wait", "--wait-timeout", "600", timeout=660)

    def error_lines(self) -> list[str]:
        """日志里的 ERROR 和异常堆栈，去掉白名单里的。"""
        allowed = []
        if WHITELIST.exists():
            for line in WHITELIST.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    allowed.append(re.compile(line))
        bad = []
        for line in self.logs().splitlines():
            if _BAD_LINE.search(line) and not any(p.search(line) for p in allowed):
                bad.append(line)
        return bad
