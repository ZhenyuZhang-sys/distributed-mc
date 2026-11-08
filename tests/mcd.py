"""测试用的公共工具：通过 scripts/ 操作测试服务器。"""

from __future__ import annotations

import os
import json
import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = REPO_ROOT / "scripts"
TEST_DATA = REPO_ROOT / "docker" / "test-data"
WORKER_DATA = REPO_ROOT / "docker" / "test-data-worker"
WORKER2_DATA = REPO_ROOT / "docker" / "test-data-worker2"
EXCHANGE = REPO_ROOT / "docker" / "test-exchange"
WHITELIST = Path(__file__).resolve().parent / "log_whitelist.txt"

# Minecraft 的颜色代码，比如 §a
_COLOR = re.compile(r"§.")
_ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
# 日志里算作出错的行
_BAD_LINE = re.compile(r"/ERROR\]|\b[\w.$]*(?:Exception|Error)(?=[:\s]|$)|^\s*at [\w.$/]+\(")


# 异常的堆栈部分：at 行、Caused by、Suppressed、"... 12 more"
_STACK_LINE = re.compile(r"^\s*(?:at [\w.$/]+\(|Caused by:|Suppressed:|\.\.\. \d+ more)")


def check_log_text(text: str) -> list[str]:
    """日志里算出错、又不在白名单里的行。

    白名单放过一个异常时，紧跟着的堆栈行（at、Caused by、... N more）属于同一个异常，也一起放过；
    遇到第一条不是堆栈的行就结束。白名单以外的堆栈照旧报。
    """
    allowed = [re.compile(line.strip()) for line in WHITELIST.read_text().splitlines()
               if line.strip() and not line.lstrip().startswith("#")]
    bad = []
    in_allowed_block = False
    for original in text.splitlines():
        line = _ANSI.sub("", _COLOR.sub("", original))
        line = re.sub(r"^\d{4}-\d\d-\d\dT\S+ ", "", line)
        payload = re.sub(r"^(?:\[\d\d:\d\d:\d\d\] )?\[[^\]\n]+\]:\s*(?:\[STDERR\]:\s*)?", "", line)
        is_stack = bool(_STACK_LINE.match(payload))
        if in_allowed_block and is_stack:
            continue
        in_allowed_block = False
        if _BAD_LINE.search(line) or _BAD_LINE.search(payload):
            if any(pattern.search(line) for pattern in allowed):
                in_allowed_block = True
            else:
                bad.append(original)
    return bad


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
    """测试服务器的资源限制：docker/n100.env，再叠 docker/.env.local（本目录用的核），环境变量优先。"""
    out = {}
    local = Path(os.environ.get("MCD_LOCAL_ENV", REPO_ROOT / "docker" / ".env.local"))
    for path in (REPO_ROOT / "docker" / "n100.env", local):
        if not path.is_file():
            continue
        for line in path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                k, v = line.split("=", 1)
                out[k] = v
    return {k: os.environ.get(k, v) for k, v in out.items()}


def load_tile_file(path: Path) -> dict:
    """读 worker 导出的 tile 文件，返回 {(x, z): chunk 的 NBT}。"""
    import nbtlib

    root = nbtlib.load(path)
    return {(int(c["x"]), int(c["z"])): c["data"] for c in root["chunks"]}


def worker_id(server: "Server", svc: str = "worker", timeout: float = 180) -> int:
    """这个 worker 容器在 coordinator 那边的编号（worker 的名字是容器的 hostname）。"""
    host = compose("ps", "-q", svc).strip()[:12]

    def find():
        for w in json.loads(server.rcon("donor status"))["workers"]:
            if w["name"] == host:
                return w["id"]
        return None

    return wait_until(find, timeout=timeout, interval=2, what=f"{svc} 连上 coordinator")


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
    archives: list[Path] = field(default_factory=list)
    sealed: bool = False
    initial_snapshot: Path | None = None
    predecessor: dict = field(default_factory=dict)
    artifact_dir: Path | None = None
    service: str = "minecraft"  # 或 "worker"

    @property
    def world_dir(self) -> Path:
        return {"worker": WORKER_DATA, "worker2": WORKER2_DATA}.get(self.service, TEST_DATA) / self.level

    def rcon(self, command: str) -> str:
        flag = [f"--{self.service}"] if self.service in ("worker", "worker2") else []
        out = run(str(SCRIPTS / "rcon.sh"), *flag, *command.split())
        return _COLOR.sub("", out).strip()

    def logs(self) -> str:
        return compose("logs", "--no-log-prefix", f"--since={self.started_at}", self.service)

    def health(self) -> str:
        cid = compose("ps", "-q", self.service).strip()
        if not cid:
            return "not running"
        return run("docker", "inspect", "-f", "{{.State.Health.Status}}", cid).strip()

    def restart(self) -> None:
        """两台一起重启。"""
        compose("stop", timeout=180)
        if self.artifact_dir:
            self.archive_logs(self.artifact_dir / "before-restart")
        compose("up", "-d", "--wait", "--wait-timeout", "600", timeout=660)
        self.sealed = False

    def archive_logs(self, destination: Path) -> Path:
        text = self.logs()
        if not text.strip():
            raise RuntimeError(f"世界 {self.level} 日志为空，不能作为通过证据")
        destination.mkdir(parents=True, exist_ok=True)
        path = destination / "server.log"
        path.write_text(text)
        if path not in self.archives:
            self.archives.append(path)
        self.sealed = True
        return path

    def _copy_world(self, destination: Path) -> Path:
        destination = Path(destination)
        if destination.resolve().is_relative_to(self.world_dir.resolve()):
            raise ValueError("快照必须在活动世界目录之外")
        if destination.exists():
            raise FileExistsError(f"快照已存在：{destination}")
        destination.mkdir(parents=True)
        shutil.copy2(self.world_dir / "level.dat", destination / "level.dat")
        shutil.copytree(self.world_dir / "region", destination / "region")
        return destination

    def snapshot(self, destination: Path) -> Path:
        try:
            result = self.rcon("save-off")
            if result not in ("Automatic saving is now disabled", "Saving is already turned off"):
                raise RuntimeError(f"未确认关闭自动保存：{result}")
            result = self.rcon("save-all flush")
            if "Saved the game" not in result:
                raise RuntimeError(f"未确认保存成功：{result}")
            return self._copy_world(destination)
        finally:
            try:
                result = self.rcon("save-on")
                if result not in ("Automatic saving is now enabled", "Saving is already turned on"):
                    raise RuntimeError(f"无法恢复自动保存：{result}")
            except Exception:
                # 无法恢复自动保存时正常停服，不能把服务器留在 save-off。
                compose("stop", timeout=180)
                raise

    def chunky_square(self, cx: int, cz: int, size: int) -> dict:
        if size < 1:
            raise ValueError("Chunky 区域边长必须为正数")
        expected = [(x, z) for x in range(cx, cx + size) for z in range(cz, cz + size)]
        # 1.4.23 的 loop 将半径向上取整，偶数边长会多一行/列。
        # CSV 明确列出目标；square 仅提供覆盖这些坐标的外围边界。
        name = f"step0-{time.time_ns()}"
        # 坐标清单放进这台服务器自己的 Chunky 配置目录（worker 的在 test-data-worker）
        data_root = {"worker": WORKER_DATA, "worker2": WORKER2_DATA}.get(self.service, TEST_DATA)
        csv_path = data_root / "config/chunky" / f"{name}.csv"
        csv_path.write_text("".join(f"{x},{z}\n" for x, z in expected))
        if self.artifact_dir:
            self.artifact_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(csv_path, self.artifact_dir / csv_path.name)
        for command in ["chunky world minecraft:overworld", "chunky shape square",
                        f"chunky corners {16 * cx} {16 * cz} {16 * (cx + size) - 1} {16 * (cz + size) - 1}",
                        f"chunky pattern csv {name}"]:
            self.rcon(command)
        selection = self.rcon("chunky selection")
        # 连续日志的行游标把此前任务完成行排除。
        cursor = len(self.logs().splitlines())
        self.rcon("chunky start")

        def finished():
            lines = self.logs().splitlines()
            if len(lines) < cursor:
                raise RuntimeError("Chunky 任务期间日志轮转，完成事件不可验证")
            match = re.search(r"Task finished for minecraft:overworld\. Processed: ([\d,]+) chunks", "\n".join(lines[cursor:]))
            return int(match.group(1).replace(",", "")) if match else None

        count = wait_until(finished, timeout=300, interval=1, what="本次 Chunky 任务完成")
        if count != len(expected):
            raise AssertionError(f"Chunky 边界不符：期望 {len(expected)}，实际 {count}；{selection}")
        result = {"count": count, "expected": expected, "selection": selection}
        if self.artifact_dir:
            (self.artifact_dir / f"{name}.json").write_text(json.dumps(result, indent=2) + "\n")
        # 后续步骤按中心和半径选区，不能继续沿用本次 CSV pattern。
        self.rcon("chunky pattern loop")
        # Chunky 会持久化 CSV selection；保留输入，后续配置和重启仍可能读取它。
        return result

    def error_lines(self) -> list[str]:
        """日志里的 ERROR 和异常堆栈，去掉白名单里的。"""
        text = "\n".join(path.read_text() for path in self.archives)
        if not self.sealed:
            text += "\n" + self.logs()
        return check_log_text(text)
