"""benchmark 入口真的拿独占锁（不需要服务器）。

合并 fish 的共享锁以后复核：bench.sh 和 test.sh --slow 先拿独占锁，
之后 prepare_heavy 里的 take_shared_lock 看到已持有就直接返回，不会把锁降成共享。
做法：用临时锁文件，PATH 前面放一个假 docker。脚本拿到锁后第一次调 docker 时，
假 docker 先停 5 秒，这期间从外面试拿共享锁，应当拿不到；然后假 docker 报
"平时玩的服务器还在跑"，脚本退出，不会碰真的 Docker。
"""
import os
import subprocess
import time

import pytest

from mcd import SCRIPTS

CASES = {
    'bench': ['bash', str(SCRIPTS / 'bench.sh')],
    'test-slow': ['bash', str(SCRIPTS / 'test.sh'), 'step0', '--slow'],
}


def fake_env(tmp_path):
    bin_dir = tmp_path / 'bin'
    bin_dir.mkdir()
    marker = tmp_path / 'docker-called'
    docker = bin_dir / 'docker'
    docker.write_text(f'#!/bin/sh\ntouch "{marker}"\nsleep 5\necho fake-running\n')
    docker.chmod(0o755)
    lock = tmp_path / 'heavy.lock'
    lock.touch()
    env = {**os.environ, 'PATH': f'{bin_dir}:{os.environ["PATH"]}', 'MCD_HEAVY_LOCK': str(lock)}
    env.pop('MCD_LOCK_HELD', None)
    # 只测全局锁。本目录的锁假装已经拿着：完整 test.sh 里跑这个测试时，外层 test.sh 正拿着本目录的锁，
    # 里面的 test.sh 要是再去拿，会一直排队，测不到全局锁。
    env['MCD_CHECKOUT_LOCK_HELD'] = '1'
    return env, lock, marker


def can_share(lock):
    return subprocess.run(['flock', '-s', '-n', str(lock), 'true']).returncode == 0


@pytest.mark.parametrize('name', CASES)
def test_holds_exclusive_lock_while_running(tmp_path, name):
    env, lock, marker = fake_env(tmp_path)
    p = subprocess.Popen(CASES[name], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        deadline = time.monotonic() + 30
        while not marker.exists():
            assert p.poll() is None, p.communicate()
            assert time.monotonic() < deadline, '脚本没走到第一次调 docker'
            time.sleep(0.05)
        assert not can_share(lock), f'{name} 运行中别人还能拿共享锁：没有独占'
        out, err = p.communicate(timeout=30)
    finally:
        p.kill()
    assert p.returncode != 0 and '还在跑' in err, err  # 被假 docker 拦下，没碰真 Docker
    assert can_share(lock)  # 退出后锁释放


@pytest.mark.parametrize('name', CASES)
def test_waits_for_shared_holders(tmp_path, name):
    # 有测试或构建拿着共享锁：benchmark 排队，不往下走
    env, lock, marker = fake_env(tmp_path)
    holder = subprocess.Popen(['flock', '-s', str(lock), 'sleep', '30'])
    try:
        time.sleep(0.3)
        r = subprocess.run(['timeout', '3', *CASES[name]], env=env, capture_output=True, text=True, timeout=30)
        assert r.returncode == 124, r.stderr
        assert '另一个重活正在跑' in r.stderr and not marker.exists(), r.stderr  # 全局锁的排队提示，不是本目录锁的
    finally:
        holder.kill()
