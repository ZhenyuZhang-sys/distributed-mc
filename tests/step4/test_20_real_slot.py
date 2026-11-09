"""第 4 步：冒烟测量的真实飞行驱动（real_fly_slot）先跑一个很小的槽位，确认流程通（不是测量，共享锁）。

新世界 → 最小预热 → 预生成航线并重启 → 等 CPU 稳定 → 飞 5 秒 → 记 docker 事件 → 删世界。
标 long：--quick 不跑。正式测量在 test_90 的 slow 部分。
"""
import json
import os
from pathlib import Path

import pytest

from bench import layer2_campaign as lc

PROTOCOL = Path(__file__).resolve().parents[2] / 'bench/layer2-protocol.json'


@pytest.mark.long
def test_real_slot_driver_runs_once(server_manager):
    server_manager.finish()  # 停掉前面测试的服务器；槽位自己开新世界
    p = lc.load_protocol(PROTOCOL)
    p = lc.validate({**p, 'seconds': 5, 'warmup_by_cpu': {'1': 256, '2': 256, '4': 256}})
    slot = next(s for s in lc.slots(p) if s['name'] == 'pregen')
    slot = {**slot, 'speed': 4.3}  # 预生成的范围小一点
    directory = Path(os.environ['MCD_ARTIFACT_DIR']) / 'step4-real-slot' / 'attempt-001'
    directory.mkdir(parents=True)
    result = lc.real_fly_slot(p)(slot, directory)
    assert result['seconds'] == 5 and result['view_distance'] == 10
    assert isinstance(result['concurrent'], list)
    assert (directory / 'docker-events.jsonl').is_file()
    # 原版基线不能有 donor worker 在旁边跑：本项目的 worker 容器在槽位期间不该启动
    project = lc.test_project()
    events = [json.loads(line) for line in (directory / 'docker-events.jsonl').read_text().splitlines()]
    workers = [e for e in events if e.get('Type') == 'container' and e.get('Action') == 'start'
               and e['Actor']['Attributes'].get('com.docker.compose.project') == project
               and e['Actor']['Attributes'].get('com.docker.compose.service', '').startswith('worker')]
    assert not workers, [e['Actor']['Attributes'].get('name') for e in workers]
    assert result['workers_running'] == []
    assert json.loads((Path(result['flight_dir']) / 'summary.json').read_text())['run'].startswith('selfcheck-pregen')
    # 预生成后重启再飞：初始视野是从存档读的，不该有很多原版生成
    done = json.loads((Path(result['flight_dir']) / 'raw' / 'done.json').read_text())
    assert done['provenance']['loaded'] > 0
