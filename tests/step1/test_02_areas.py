"""选区、协议和矩阵必须先通过纯逻辑验证。"""
import pytest
from bench.models import Area, matrix, protocol_id

def test_negative_coordinates():
    a = Area('ocean', -33, -17, -18, -2, -200, -200)
    assert len(a.positions()) == len(set(a.positions())) == 256
    assert a.positions()[0] == (-33,-17)
    assert a.positions()[-1] == (-18,-2)

def test_bad_area():
    with pytest.raises(ValueError): Area('ocean',0,0,16,15,200,200)
    with pytest.raises(ValueError): Area('ocean',0,0,15,15,20,20)

def test_matrix_and_protocol():
    m = matrix(42)
    assert len(m) == len(set(m)) == 45
    assert m == matrix(42) and m != matrix(43)
    # 轮数可配置；少跑几轮正好是完整矩阵的前缀，粗测结果以后能接着补满。
    short = matrix(42, repeats=2)
    assert len(short) == 18 and short == m[:18] and {r for _, _, r in short} == {1, 2}
    with pytest.raises(ValueError): matrix(42, repeats=1)
    assert protocol_id({'seed':1,'protocol_id':'old'}) == protocol_id({'seed':1})
    assert protocol_id({'seed':1}) != protocol_id({'seed':2})

def test_guard_rejects_partial_chunk(tmp_path):
    from bench.layer1 import empty
    from mca_fixtures import world,chunk,write_chunk
    a=Area('ocean',0,0,15,15,200,200)
    snapshot=world(tmp_path)
    write_chunk(snapshot,-1,0,tag=chunk(-1,0,status='minecraft:noise'))
    with pytest.raises(RuntimeError,match='保护区'):empty(snapshot,a)

@pytest.mark.parametrize('name',['..','.','../escape','/tmp/escape','a/b',''])
def test_campaign_path_cannot_escape(name):
    from bench.models import validate_campaign_id
    with pytest.raises(ValueError):validate_campaign_id(name)

def test_non_integer_coordinates_rejected():
    with pytest.raises(ValueError,match='整数'):Area('ocean',0.,0,15.,15,200,200)

def test_invalid_cpu_count_before_start():
    from bench.session import resource_environment
    with pytest.raises(ValueError,match='1/2/4'):
        with resource_environment(3):pass
