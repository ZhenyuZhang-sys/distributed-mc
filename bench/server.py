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


def data_root(service: str) -> Path:
    """compose 服务 → 存档目录。调用时再取模块变量（测试会替换 TEST_DATA）。"""
    return {"minecraft": TEST_DATA, "worker": WORKER_DATA, "worker2": WORKER2_DATA}[service]
WHITELIST = REPO_ROOT / "tests/log_whitelist.txt"

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


def donor_imports(server: "Server") -> list:
    """coordinator 的全部导入记录（/donor imports 分页取，RCON 一次回复只有约 4096 字节）。"""
    out, start = [], 0
    while True:
        page = json.loads(server.rcon(f"donor imports {start}"))
        out += page["items"]
        start += len(page["items"])
        if not page["items"] or start >= page["total"]:
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


CHUNKY_TASKS = TEST_DATA / "config" / "chunky" / "tasks"
CHUNKY_BUSY = "A task was already started"


def _move_tasks(tasks: Path, dest: Path) -> list[str]:
    if not tasks.is_dir():
        return []
    moved = []
    for path in sorted(p for p in tasks.rglob("*") if p.is_file()):
        rel = path.relative_to(tasks)
        target = Path(dest) / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(path), str(target))
        moved.append(str(rel))
    return moved


def quarantine_chunky_tasks(dest: Path) -> list[str]:
    """把残留的 Chunky 任务文件移到 dest/stale-chunky-tasks，返回移走的相对路径。

    只在测试服务器停着、马上要换全新世界时调用：这时的任务文件一定是上一轮（比如被中断的那轮）留下的。
    Chunky 按维度把任务存在共享的 config 目录，不随世界删除；不清掉，新世界的 chunky start 会被拒绝。
    """
    return _move_tasks(CHUNKY_TASKS, Path(dest) / "stale-chunky-tasks")


def quarantine_worker_chunky_tasks(dest: Path) -> list[str]:
    """worker、worker2 的存档目录（第 3 步起）也有自己的 Chunky 配置，同样清。返回 "worker/..." 形式的路径。"""
    moved = []
    for service in ("worker", "worker2"):
        tasks = data_root(service) / "config" / "chunky" / "tasks"
        moved += [f"{service}/{r}" for r in _move_tasks(tasks, Path(dest) / "stale-chunky-tasks" / service)]
    return moved


def start_chunky(server: "Server") -> str:
    """发 chunky start；有残留任务时 Chunky 不开始、只提示 confirm，这里立刻报错，不白等超时。"""
    out = server.rcon("chunky start")
    if CHUNKY_BUSY in out:
        raise RuntimeError("Chunky 有残留任务，拒绝开始新任务：" + out.strip())
    return out


def chunky(server: "Server", x: int, z: int, radius: int, timeout: float = 900) -> int:
    """用 Chunky 生成以 (x, z) 为中心、半径 radius 格的方形，返回 Chunky 报告的 chunk 数。"""
    pattern = r"Task finished for minecraft:overworld\. Processed: (\d+) chunks"
    # 同一轮里可能已经跑过 Chunky，等新增的那条完成记录
    before = len(re.findall(pattern, server.logs()))
    for cmd in ["chunky world minecraft:overworld", "chunky shape square",
                f"chunky center {x} {z}", f"chunky radius {radius}"]:
        server.rcon(cmd)
    start_chunky(server)
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
    retired: bool = False
    finish_error: str | None = None
    service: str = "minecraft"  # 或 "worker"、"worker2"（第 3、6 步的 donor）

    @property
    def data_root(self) -> Path:
        return data_root(self.service)

    @property
    def world_dir(self) -> Path:
        return self.data_root / self.level

    def assert_active(self):
        if self.retired:
            raise RuntimeError(f"会话已结束：{self.level}")

    def finish(self):
        if self.retired:
            if self.finish_error: raise RuntimeError(self.finish_error)
            return self.artifact_dir
        try:
            try:
                compose("stop", timeout=180)
            finally:
                self.archive_logs((self.artifact_dir or REPO_ROOT / "artifacts" / self.level) / "finish")
            errors = self.error_lines()
            if errors: raise RuntimeError("服务器日志错误：\n" + "\n".join(errors[:30]))
        except Exception as exc:
            self.finish_error = str(exc)
            raise
        finally:
            self.retired = True
        return self.artifact_dir

    def rcon(self, command: str) -> str:
        self.assert_active()
        t0 = time.monotonic_ns()
        flag = [f"--{self.service}"] if self.service != "minecraft" else []
        out = run(str(SCRIPTS / "rcon.sh"), *flag, command)
        if self.artifact_dir:
            self.artifact_dir.mkdir(parents=True, exist_ok=True)
            with (self.artifact_dir / "commands.jsonl").open("a") as stream:
                stream.write(json.dumps(dict(service=self.service, command=command, response=out, start_ns=t0,
                                             end_ns=time.monotonic_ns())) + "\n")
        return _COLOR.sub("", out).strip()

    def logs(self) -> str:
        self.assert_active()
        return compose("logs", "--no-log-prefix", f"--since={self.started_at}", self.service)

    def health(self) -> str:
        cid = compose("ps", "-q", self.service).strip()
        if not cid:
            return "not running"
        return run("docker", "inspect", "-f", "{{.State.Health.Status}}", cid).strip()

    def restart(self) -> None:
        """测试服务器和 worker 一起重启。"""
        self.assert_active()
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
        csv_path = self.data_root / "config/chunky" / f"{name}.csv"
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
        start_chunky(self)

        def finished():
            lines = self.logs().splitlines()
            if len(lines) < cursor:
                raise RuntimeError("Chunky 任务期间日志轮转，完成事件不可验证")
            match = re.search(r"Task finished for minecraft:overworld\. Processed: ([\d,]+) chunks", "\n".join(lines[cursor:]))
            return int(match.group(1).replace(",", "")) if match else None

        count = wait_until(finished, timeout=300, interval=1, what="本次 Chunky 任务完成")
        if count != len(expected):
            raise AssertionError(f"Chunky 边界不符：期望 {len(expected)}，实际 {count}；{selection}")
        result = {"count": count, "expected": expected, "selection": selection, "completion_cursor": cursor, "completion_observed_ns": time.monotonic_ns()}
        if self.artifact_dir:
            (self.artifact_dir / f"{name}.json").write_text(json.dumps(result, indent=2) + "\n")
        # 后续步骤按中心和半径选区，不能继续沿用本次 CSV pattern。
        self.rcon("chunky pattern loop")
        # Chunky 会持久化 CSV selection；保留输入，后续配置和重启仍可能读取它。
        return result

    def chunky_list(self, chunks: list[tuple[int, int]], timeout: float = 900) -> int:
        """用 Chunky 生成给定的 chunk（CSV 模式，可以是散的），返回 Chunky 报告的个数。"""
        name = f"list-{time.time_ns()}"
        csv_path = self.data_root / "config/chunky" / f"{name}.csv"
        csv_path.parent.mkdir(parents=True, exist_ok=True)
        csv_path.write_text("".join(f"{x},{z}\n" for x, z in chunks))
        xs, zs = [c[0] for c in chunks], [c[1] for c in chunks]
        for command in ["chunky world minecraft:overworld", "chunky shape square",
                        f"chunky corners {16 * min(xs)} {16 * min(zs)} {16 * max(xs) + 15} {16 * max(zs) + 15}",
                        f"chunky pattern csv {name}"]:
            self.rcon(command)
        cursor = len(self.logs().splitlines())
        start_chunky(self)

        def finished():
            lines = self.logs().splitlines()
            m = re.search(r"Task finished for minecraft:overworld\. Processed: ([\d,]+) chunks", "\n".join(lines[cursor:]))
            return int(m.group(1).replace(",", "")) if m else None

        count = wait_until(finished, timeout=timeout, interval=2, what="Chunky 生成给定的 chunk")
        self.rcon("chunky pattern loop")
        return count

    def error_lines(self) -> list[str]:
        """日志里的 ERROR 和异常堆栈，去掉白名单里的。"""
        text = "\n".join(path.read_text() for path in self.archives)
        if not self.sealed:
            text += "\n" + self.logs()
        return check_log_text(text)
