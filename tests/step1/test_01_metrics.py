"""CPU 计量的独立数值预期。"""
import pytest
from bench.metrics import metrics, parse_cpu_stat

def test_units():
    r = metrics(0, 2_000_000_000, 100, 2_560_100, 256, 2)
    assert r['cpu_ms_per_chunk'] == 10
    assert r['chunks_per_s'] == 128
    assert r['avg_cpu_cores'] == 1.28
    assert r['quota_utilization'] == .64

@pytest.mark.parametrize('args', [(0,0,0,1,256,1),(0,1,2,1,256,1),(0,1,0,1,0,1)])
def test_invalid_samples(args):
    with pytest.raises(ValueError): metrics(*args)

def test_cpu_stat():
    assert parse_cpu_stat('usage_usec 2560000\nuser_usec 2000000\nsystem_usec 560000\n')['usage_usec'] == 2560000
    with pytest.raises(ValueError): parse_cpu_stat('user_usec 1')
