"""第 10 步 10.2：断点续跑。假驱动，不起服务器。"""
import contextlib
import json

import pytest

from analysis.run_matrix import cell_id, expand, run, validate


def config(**over):
    c = {'name': 't', 'seed': 7, 'repeats': 2, 'rerun_on_interference': False, 'experiments': {
        'flight': [{'donors': [0, 2], 'speed': ['elytra'], 'server_cpus': [2], 'duration_s': 60}],
        'verify': [{'cheat': ['none', 'ore'], 'duration_s': 300}]}}
    c.update(over)
    return validate(c)


class Driver:
    """记下被调了哪些单元；可以在第 n 次调用时中断，或者让某些单元一直失败。"""

    def __init__(self, interrupt_at=None, fail=lambda cell: False, interference=lambda cell: []):
        self.calls, self.interrupt_at, self.fail, self.interference = [], interrupt_at, fail, interference

    def __call__(self, cell, out):
        self.calls.append(cell_id(cell))
        if self.interrupt_at is not None and len(self.calls) == self.interrupt_at:
            raise KeyboardInterrupt('测试中断')
        if self.fail(cell):
            raise RuntimeError('驱动失败')
        (out / 'data.txt').write_text('ok')
        return dict(value=len(self.calls), interference=self.interference(cell))


def drivers(d):
    return {'flight': d, 'verify': d}


def manifests(root, cid):
    return [json.loads(p.read_text()) for p in sorted((root / 'matrix-t' / cid).glob('attempt-*/manifest.json'))]


def test_interrupted_run_resumes_only_missing_cells(tmp_path):
    cfg = config()
    total = len(expand(cfg))
    d = Driver(interrupt_at=3)
    with pytest.raises(KeyboardInterrupt):
        run(cfg, drivers(d), tmp_path)
    interrupted = d.calls[-1]
    assert manifests(tmp_path, interrupted)[-1]['status'] == 'interrupted'
    d2 = Driver()
    summary = run(cfg, drivers(d2), tmp_path)
    assert len(d2.calls) == total - 2              # 前两个已经 passed，不重跑
    assert interrupted in d2.calls
    assert [m['status'] for m in manifests(tmp_path, interrupted)] == ['interrupted', 'passed']
    assert summary['passed'] == total and summary['failed'] == []
    d3 = Driver()
    run(cfg, drivers(d3), tmp_path)
    assert d3.calls == []                          # 全部做完，再跑什么都不做


def test_failing_cell_retried_at_most_three_times(tmp_path):
    cfg = config()
    bad = next(c for c in expand(cfg) if c['experiment'] == 'verify' and c['params']['cheat'] == 'ore')
    fail = lambda cell: cell_id(cell) == cell_id(bad)
    for _ in range(4):
        summary = run(cfg, drivers(Driver(fail=fail)), tmp_path)
    assert [m['status'] for m in manifests(tmp_path, cell_id(bad))] == ['failed'] * 3
    assert summary['failed'] == [cell_id(bad)]
    assert all('驱动失败' in m['error'] for m in manifests(tmp_path, cell_id(bad)))


def test_changed_parameter_is_new_cell(tmp_path):
    run(config(), drivers(Driver()), tmp_path)
    cfg = config()
    cfg['experiments']['flight'][0]['duration_s'] = 90
    d = Driver()
    run(cfg, drivers(d), tmp_path)
    flight = {cell_id(c) for c in expand(cfg) if c['experiment'] == 'flight'}
    assert set(d.calls) == flight                  # 只有 flight 变了，verify 沿用


def test_tampered_result_not_reused(tmp_path):
    cfg = config()
    run(cfg, drivers(Driver()), tmp_path)
    victim = cell_id(expand(cfg)[0])
    (tmp_path / 'matrix-t' / victim / 'attempt-001' / 'data.txt').write_text('改过')
    d = Driver()
    run(cfg, drivers(d), tmp_path)
    assert d.calls == [victim]
    assert len(manifests(tmp_path, victim)) == 2   # 旧的留着，另起一个


def test_interference_marks_or_reruns(tmp_path):
    noisy = lambda cell: ['fish-build 12:00:01 start']
    run(config(), drivers(Driver(interference=noisy)), tmp_path / 'smoke')
    m = manifests(tmp_path / 'smoke', cell_id(expand(config())[0]))
    assert [x['status'] for x in m] == ['passed'] and m[0]['concurrent'] is True
    strict = config(rerun_on_interference=True)
    summary = run(strict, drivers(Driver(interference=noisy)), tmp_path / 'full')
    m = manifests(tmp_path / 'full', cell_id(expand(strict)[0]))
    assert [x['status'] for x in m] == ['invalid'] * 3
    assert len(summary['failed']) == len(expand(strict))


def test_lock_taken_per_executed_cell_and_config_recorded(tmp_path):
    taken = []

    @contextlib.contextmanager
    def lock():
        taken.append(1)
        yield

    cfg = config()
    run(cfg, drivers(Driver()), tmp_path, lock=lock)
    assert len(taken) == len(expand(cfg))
    run(cfg, drivers(Driver()), tmp_path, lock=lock)
    assert len(taken) == len(expand(cfg))          # 跳过的单元不拿锁
    saved = json.loads((tmp_path / 'matrix-t' / 'config.json').read_text())
    assert saved['configs'][-1] == cfg and len(saved['configs']) == 1


def test_result_has_cell_and_driver_output(tmp_path):
    cfg = config()
    run(cfg, drivers(Driver()), tmp_path)
    c = expand(cfg)[0]
    r = json.loads((tmp_path / 'matrix-t' / cell_id(c) / 'attempt-001' / 'result.json').read_text())
    assert r['cell_id'] == cell_id(c) and r['experiment'] == c['experiment'] and r['params'] == c['params']
    assert 'value' in r['output']


def test_missing_driver_rejected_before_running(tmp_path):
    with pytest.raises(ValueError):
        run(config(), {'flight': Driver()}, tmp_path)
    assert not (tmp_path / 'matrix-t').exists()
