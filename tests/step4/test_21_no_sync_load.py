"""第 4 步（11-09 看门狗问题）：2 核鞘翅飞 40 秒，主线程不会因为同步取 chunk 卡住。

fish 6.6 用旧的移动方式（按墙钟把假玩家放到"起点 + 速度 × 时间"）时，假玩家跑到生成前面，
实体 tick 查脚下流体（Entity.updateFluidHeightAndDoFluidPushing → Level.getFluidState）时在主线程上同步生成，
一个 tick 卡满 60 秒，看门狗杀服。真实客户端不会走进自己没收到的 chunk（LocalPlayer.tick 先查 hasChunkAt）。
现在飞行像客户端一样，前面的 chunk 没就绪就停在边缘。这里要求：同步加载 0 次、最长 tick 远小于 60 秒、确实卡住过。
标 long：--quick 不跑。共享锁，不是测量。
"""
import json
import os
from pathlib import Path

import pytest

from bench import layer2_campaign as lc

PROTOCOL = Path(__file__).resolve().parents[2] / 'bench/layer2-protocol.json'


@pytest.mark.long
def test_two_core_elytra_never_blocks_main_thread(server_manager):
    server_manager.finish()
    p = lc.validate({**lc.load_protocol(PROTOCOL), 'seconds': 40, 'warmup_by_cpu': {'1': 256, '2': 256, '4': 256}})
    slot = next(s for s in lc.slots(p) if s['speed_name'] == 'elytra' and s['cpus'] == 2 and s['kind'] == 'smoke')
    directory = Path(os.environ['MCD_ARTIFACT_DIR']) / 'step4-no-sync-load' / 'attempt-001'
    directory.mkdir(parents=True)
    result = lc.real_fly_slot(p)(slot, directory)
    done = json.loads((Path(result['flight_dir']) / 'raw' / 'done.json').read_text())
    assert done['sync_loads']['count'] == 0, done['sync_loads']
    assert 'invalid' not in result
    assert result['mspt_max'] < 5_000, result['mspt_max']
    assert done['unready_ticks'] == 0
    # 2 核原版每秒生成不到 10 个 chunk，鞘翅要 40 个：一定会卡住，实际距离比理想的短
    assert result['stall_s'] > 0 and result['distance_ratio'] < 1
