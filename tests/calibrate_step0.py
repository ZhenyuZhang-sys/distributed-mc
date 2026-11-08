"""显式校准工具；正式验收不会自行放宽出生区阈值。"""

import json
import os
import shutil
import struct
import time
from datetime import datetime, timezone
from pathlib import Path

from analysis.mca import iter_chunk_positions, read_chunk, read_world_metadata
from mcd import REPO_ROOT, SCRIPTS, Server, compose, run, wait_until


def main():
    evidence = Path(os.environ["MCD_ARTIFACT_DIR"])
    started = datetime.now(timezone.utc).isoformat()
    level = f"cal{time.time_ns()}"
    server = Server(level, 424242, started, artifact_dir=evidence)
    try:
        run(str(SCRIPTS / "new_world.sh"), level, "424242", timeout=900)
        wait_until(lambda: "Done (" in server.logs(), timeout=30, what="启动完成")
        snapshot = server.snapshot(evidence / "initial")
        meta = read_world_metadata(snapshot)
        positions = list(iter_chunk_positions(snapshot))
        assert positions, "参考世界为空"
        sx, _, sz = meta.spawn
        scx, scz = sx // 16, sz // 16
        observed = [min(x-scx for x, z in positions), min(z-scz for x, z in positions),
                    max(x-scx for x, z in positions), max(z-scz for x, z in positions)]
        profile = {
            "schema_version": 1, "seed": meta.seed, "data_version": meta.data_version,
            "spawn": list(meta.spawn), "spawn_chunk_radius": server.rcon("gamerule spawnChunkRadius"),
            "initial_observed_relative_bounds": observed,
            "initial_observed_chunks": len(positions),
            "margin_chunks": 0,
            "source": "独立无玩家、无 Chunky 的 1.21.1/seed=424242 参考世界；正常停服后枚举包括生成依赖外圈的全部索引，不添加主观余量。",
        }
        fixtures = REPO_ROOT / "tests/fixtures/mca"
        fixtures.mkdir(parents=True, exist_ok=True)
        # 用服务器命令确立独立的已知方块，不把读取器自己的输出当答案。
        marker = (sx, 200, sz)
        server.rcon(f"setblock {sx} 200 {sz} minecraft:diamond_block")
        assert "Test passed" in server.rcon(f"execute if block {sx} 200 {sz} minecraft:diamond_block")
        compose("stop", timeout=180)
        server.archive_logs(evidence)
        assert not server.error_lines(), server.error_lines()
        stopped = server._copy_world(evidence / "stopped")
        # 启动时已有的半成品可能直到停服才落盘；不能仅用初始 49 个索引定边界。
        positions = list(iter_chunk_positions(stopped))
        bounds = [min(x-scx for x, z in positions), min(z-scz for x, z in positions),
                  max(x-scx for x, z in positions), max(z-scz for x, z in positions)]
        profile.update(observed_relative_bounds=bounds, allowed_relative_bounds=bounds,
                       observed_chunks=len(positions))
        target = fixtures / "java-1.21.1"
        (target / "region").mkdir(parents=True, exist_ok=True)
        shutil.copy2(stopped / "level.dat", target / "level.dat")
        source = stopped / "region" / f"r.{scx // 32}.{scz // 32}.mca"
        raw = source.read_bytes()
        slot = scx % 32 + 32 * (scz % 32)
        location = struct.unpack_from(">I", raw, slot * 4)[0]
        offset, sectors = location >> 8, location & 255
        record = raw[offset*4096:(offset+sectors)*4096]
        header = bytearray(8192)
        struct.pack_into(">I", header, slot*4, (2 << 8) | sectors)
        (target / "region" / source.name).write_bytes(header + record)
        result = read_chunk(target, scx, scz)
        assert result.status == "minecraft:full"
        assert result.block_state(sx % 16, 200, sz % 16).name == "minecraft:diamond_block"
        (target / "expected.json").write_text(json.dumps({"chunk": [scx, scz], "marker": marker, "block": "minecraft:diamond_block", "data_version": 3955}, indent=2) + "\n")
        (fixtures / "spawn_profile.json").write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(profile, ensure_ascii=False))
    except BaseException:
        try:
            compose("stop", timeout=180)
            server.archive_logs(evidence / "failure")
        except Exception as error:
            print(f"收集校准失败日志：{error}")
        raise


if __name__ == "__main__":
    main()
