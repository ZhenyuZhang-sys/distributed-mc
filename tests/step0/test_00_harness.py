"""失败路径用假命令验证，不接触 Docker。"""

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

from mcd import Server, check_log_text


def saving_reply(command):
    return {"save-off": "Automatic saving is now disabled",
            "save-all flush": "Saved the game",
            "save-on": "Automatic saving is now enabled"}[command]


def test_timestamped_stack_is_error():
    assert check_log_text("2026-01-01T00:00:00Z [main/ERROR]: boom")
    assert check_log_text("2026-01-01T00:00:00Z     at com.example.Test.run(Test.java:2)")
    assert check_log_text("java.lang.IllegalStateException: broken")
    assert check_log_text("[03:28:12] [Server thread/INFO]: [STDERR]: \tat knot//net.minecraft.Test.run(Test.java:2)")
    assert check_log_text("\x1b[31m[main/ERROR]: boom\x1b[0m")
    assert not check_log_text("[main/INFO]: Done")


def test_archived_errors_survive_new_container(tmp_path, monkeypatch):
    server = Server("test", 424242, "2026-01-01T00:00:00Z")
    monkeypatch.setattr(server, "logs", lambda: "[main/ERROR]: early failure\n")
    server.archive_logs(tmp_path)
    monkeypatch.setattr(server, "logs", lambda: "[main/INFO]: new container\n")
    assert server.error_lines() == ["[main/ERROR]: early failure"]


def test_snapshot_restores_save_on_failure(tmp_path, monkeypatch):
    calls = []
    server = Server("test", 424242, "now")
    monkeypatch.setattr(server, "rcon", lambda cmd: calls.append(cmd) or saving_reply(cmd))
    monkeypatch.setattr(server, "_copy_world", lambda path: (_ for _ in ()).throw(OSError("copy failed")))
    with pytest.raises(OSError, match="copy failed"):
        server.snapshot(tmp_path / "snapshot")
    assert calls == ["save-off", "save-all flush", "save-on"]


def test_snapshot_stops_server_if_save_on_fails(tmp_path, monkeypatch):
    calls = []
    server = Server("test", 424242, "now")

    def rcon(command):
        if command == "save-on":
            raise RuntimeError("RCON unavailable")
        return saving_reply(command)

    monkeypatch.setattr(server, "rcon", rcon)
    monkeypatch.setattr(server, "_copy_world", lambda path: path)
    monkeypatch.setattr("mcd.compose", lambda *args, **kwargs: calls.append(args))
    with pytest.raises(RuntimeError, match="RCON unavailable"):
        server.snapshot(tmp_path / "snapshot")
    assert calls == [("stop",)]


def test_snapshot_stops_server_if_save_on_is_rejected(tmp_path, monkeypatch):
    calls = []
    server = Server("test", 424242, "now")
    monkeypatch.setattr(server, "rcon", lambda cmd: "Unknown command" if cmd == "save-on" else saving_reply(cmd))
    monkeypatch.setattr(server, "_copy_world", lambda path: path)
    monkeypatch.setattr("mcd.compose", lambda *args, **kwargs: calls.append(args))
    with pytest.raises(RuntimeError, match="恢复自动保存"):
        server.snapshot(tmp_path / "snapshot")
    assert calls == [("stop",)]


def test_snapshot_attempts_save_on_after_save_off_timeout(tmp_path, monkeypatch):
    calls = []
    server = Server("test", 424242, "now")

    def rcon(command):
        calls.append(command)
        if command == "save-off":
            raise TimeoutError("reply lost after disabling saves")
        return "Automatic saving is now enabled"

    monkeypatch.setattr(server, "rcon", rcon)
    with pytest.raises(TimeoutError):
        server.snapshot(tmp_path / "snapshot")
    assert calls == ["save-off", "save-on"]


def test_snapshot_rejects_destination_inside_world(tmp_path, monkeypatch):
    monkeypatch.setattr("mcd.TEST_DATA", tmp_path)
    server = Server("test", 424242, "now")
    server.world_dir.mkdir()
    with pytest.raises(ValueError, match="快照"):
        server._copy_world(server.world_dir / "snapshot")
    assert not (server.world_dir / "snapshot").exists()


@pytest.mark.parametrize("cx,cz,size", [(133, 0, 2), (-3, -3, 6), (-34, -2, 2)])
def test_chunky_uses_exact_coordinates(tmp_path, monkeypatch, cx, cz, size):
    monkeypatch.setattr("mcd.TEST_DATA", tmp_path)
    (tmp_path / "config/chunky").mkdir(parents=True)
    server = Server("test", 424242, "now")
    commands, coordinates = [], []

    def rcon(command):
        commands.append(command)
        if command.startswith("chunky pattern csv "):
            path = tmp_path / "config/chunky" / (command.split()[-1] + ".csv")
            coordinates.extend(tuple(map(int, line.split(","))) for line in path.read_text().splitlines())
        return "selection"

    monkeypatch.setattr(server, "rcon", rcon)
    monkeypatch.setattr(server, "logs", lambda: (
        f"Task finished for minecraft:overworld. Processed: {size * size} chunks"
        if "chunky start" in commands else ""))
    result = server.chunky_square(cx, cz, size)
    assert set(coordinates) == {(x, z) for x in range(cx, cx + size) for z in range(cz, cz + size)}
    assert result["count"] == len(coordinates) == size * size
    # Chunky 把 CSV 名写入持久化 selection，下一条配置命令和重启仍会读取。
    assert len(list((tmp_path / "config/chunky").glob("*.csv"))) == 1


@pytest.fixture
def fake_repo(tmp_path):
    root = tmp_path / "repo"
    shutil.copytree(Path(__file__).resolve().parents[2] / "scripts", root / "scripts")
    (root / "docker/test-data/old").mkdir(parents=True)
    (root / "docker/test-data/old/keep").write_text("precious test evidence")
    (root / "docker/test-data/world.env").write_text("MC_LEVEL=old\nMC_SEED=424242\n")
    (root / "docker/.env.test").write_text("EULA=TRUE\nRCON_PASSWORD=fixture\nMCD_TEST_PROJECT=mc-test-unit\n")
    (root / "docker/n100.env").write_text("MC_CPUS=4\nMC_CPUSET=12-15\n")
    bin_dir = root / "bin"
    bin_dir.mkdir()
    docker = bin_dir / "docker"
    docker.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['FAKE_COMMANDS'], 'a') as f: f.write(json.dumps(args) + '\\n')
if args[0] == 'ps':
    if '-aq' in args and os.environ.get('FAKE_FOREIGN'): print('foreign-container')
    sys.exit(0)
if args[0] == 'inspect': print('/another-checkout/docker/test-data')
if args[0] == 'compose' and 'ps' in args and os.environ.get('FAKE_CONTAINER'): print('own-container')
if args[0] == 'compose' and 'logs' in args:
    print('[main/INFO]: stopped cleanly')
    sys.exit(int(os.environ.get('FAKE_ARCHIVE_STATUS', '0')))
if 'config' in args:
    print(json.dumps({'services': {'minecraft': {'volumes': [{'target': '/data', 'source': os.environ['FAKE_DATA']}]}}}))
if 'stop' in args: sys.exit(int(os.environ.get('FAKE_STOP_STATUS', '0')))
if 'up' in args: Path(os.environ['FAKE_UP']).touch()
if 'down' in args: sys.exit(int(os.environ.get('FAKE_DOWN_STATUS', '0')))
''')
    docker.chmod(0o755)
    env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
           "MCD_LOCK_HELD": "1", "FAKE_DATA": str(root / "docker/test-data"),
           "MCD_ARTIFACT_DIR": str(root / "artifacts"),
           "FAKE_COMMANDS": str(root / "commands"), "FAKE_UP": str(root / "up")}
    return root, env


def test_stop_failure_preserves_world(fake_repo):
    root, env = fake_repo
    result = subprocess.run([str(root / "scripts/new_world.sh"), "new"], env={**env, "FAKE_STOP_STATUS": "23"}, capture_output=True)
    assert result.returncode != 0
    assert (root / "docker/test-data/old/keep").exists()
    assert not (root / "up").exists()


def test_archive_failure_preserves_world(fake_repo):
    root, env = fake_repo
    result = subprocess.run(
        [str(root / "scripts/new_world.sh"), "new"],
        env={**env, "FAKE_CONTAINER": "1", "FAKE_ARCHIVE_STATUS": "17"}, capture_output=True,
    )
    assert result.returncode != 0
    assert (root / "docker/test-data/old/keep").exists()
    assert not (root / "up").exists()


def test_logs_archived_before_replacing_world(fake_repo):
    root, env = fake_repo
    result = subprocess.run(
        [str(root / "scripts/new_world.sh"), "new"],
        env={**env, "FAKE_CONTAINER": "1"}, capture_output=True,
    )
    assert result.returncode == 0, result.stderr.decode()
    assert list((root / "artifacts").glob("before-replace-*/server.log"))
    commands = [json.loads(line) for line in (root / "commands").read_text().splitlines()]
    assert next(i for i, cmd in enumerate(commands) if "logs" in cmd) < next(
        i for i, cmd in enumerate(commands) if "up" in cmd)


@pytest.mark.parametrize("old", ["world", "../escape", "/tmp/escape"])
def test_unsafe_old_level_rejected(fake_repo, old):
    root, env = fake_repo
    (root / "docker/test-data/world.env").write_text(f"MC_LEVEL={old}\n")
    result = subprocess.run([str(root / "scripts/new_world.sh"), "new"], env=env, capture_output=True)
    assert result.returncode != 0
    assert not (root / "up").exists()


def test_symlink_world_rejected(fake_repo):
    root, env = fake_repo
    (root / "docker/test-data/link").symlink_to(root / "docker/test-data/old", target_is_directory=True)
    result = subprocess.run([str(root / "scripts/new_world.sh"), "link"], env=env, capture_output=True)
    assert result.returncode != 0
    assert (root / "docker/test-data/old/keep").exists()


def test_private_project_is_used(fake_repo):
    root, env = fake_repo
    result = subprocess.run([str(root / "scripts/new_world.sh"), "new"], env=env, capture_output=True)
    assert result.returncode == 0, result.stderr.decode()
    commands = [json.loads(line) for line in (root / "commands").read_text().splitlines()]
    compose = [cmd for cmd in commands if cmd[0] == "compose"]
    assert compose
    assert all(cmd[cmd.index("-p") + 1] == "mc-test-unit" for cmd in compose)


def test_rcon_preserves_negative_coordinates(fake_repo):
    root, env = fake_repo
    result = subprocess.run(
        [str(root / "scripts/rcon.sh"), "setblock", "-48", "200", "-48", "minecraft:stone"],
        env=env, capture_output=True,
    )
    assert result.returncode == 0, result.stderr.decode()
    command = json.loads((root / "commands").read_text().splitlines()[-1])
    assert command[command.index("rcon-cli") + 1:] == [
        "--", "setblock", "-48", "200", "-48", "minecraft:stone",
    ]


def test_foreign_instance_rejected_before_stop(fake_repo):
    root, env = fake_repo
    result = subprocess.run([str(root / "scripts/new_world.sh"), "new"], env={**env, "FAKE_FOREIGN": "1"}, capture_output=True)
    assert result.returncode != 0
    commands = [json.loads(line) for line in (root / "commands").read_text().splitlines()]
    assert not any(action in command for command in commands for action in ("stop", "down", "up"))
    assert (root / "docker/test-data/old/keep").exists()


def test_down_failure_fails_otherwise_successful_run(fake_repo):
    root, env = fake_repo
    (root / "tests/step0").mkdir(parents=True)
    fake_python = root / "bin/pytest-python"
    fake_python.write_text("#!/bin/sh\nexit 0\n")
    fake_python.chmod(0o755)
    result = subprocess.run([str(root / "scripts/test.sh"), "step0"], env={**env, "MCD_PYTHON": str(fake_python), "FAKE_DOWN_STATUS": "9"}, capture_output=True)
    assert result.returncode != 0
