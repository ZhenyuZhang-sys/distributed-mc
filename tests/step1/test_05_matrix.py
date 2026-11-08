"""恢复必须检查所有产物，不能把损坏数据当成功槽位。"""
import json
import pytest
from bench.models import atomic_json
from bench.archive import seal, verify
from bench.statistics import summarize

def test_artifact_corruption(tmp_path):
    (tmp_path/'result.json').write_text('{}')
    seal(tmp_path,{'status':'passed','protocol_id':'p'})
    assert verify(tmp_path,'p')['status']=='passed'
    (tmp_path/'result.json').write_text('broken')
    with pytest.raises(ValueError,match='摘要'): verify(tmp_path,'p')

def test_protocol_mismatch(tmp_path):
    seal(tmp_path,{'status':'passed','protocol_id':'p'})
    with pytest.raises(ValueError,match='协议'): verify(tmp_path,'q')

def test_variability_not_hidden():
    r=summarize([100,100,100,100,100])
    assert r['cv']==0
    assert summarize([1,1,1,1,100])['cv']>.1
    with pytest.raises(ValueError): summarize([1,1])
    # 粗测：每配置至少 2 轮也能算 CV，n 如实记录。
    assert summarize([100,110],minimum=2)['n']==2
    assert summarize([100,110,90],minimum=2)['n']==3
    with pytest.raises(ValueError): summarize([100],minimum=1)


def test_comparison_pairs_formal_and_coarse():
    from analysis.summarize_layer1 import comparison_pairs
    full={('ocean',c,r) for c in (1,2,4) for r in range(1,6)}
    formal=comparison_pairs('ocean',full)
    assert [g for g,_,_ in formal].count('repeat_1cpu')==2 and [g for g,_,_ in formal].count('repeat_4cpu')==2
    assert [g for g,_,_ in formal].count('cross_1_4cpu')==5 and len(formal)==9
    # 粗测只用已有轮次：同核 1 对 2，跨核同 repeat；2 核单列一组，不混进主比较。
    have={('ocean',1,1),('ocean',1,2),('ocean',2,1),('ocean',2,2),('ocean',2,3),('ocean',4,1),('ocean',4,2)}
    coarse=comparison_pairs('ocean',have,coarse=True)
    assert coarse==[('repeat_1cpu',('ocean',1,1),('ocean',1,2)),('repeat_4cpu',('ocean',4,1),('ocean',4,2)),
                    ('cross_1_4cpu',('ocean',1,1),('ocean',4,1)),('cross_1_4cpu',('ocean',1,2),('ocean',4,2)),
                    ('repeat_2cpu',('ocean',2,1),('ocean',2,2))]
    with pytest.raises(ValueError,match='缺'):comparison_pairs('ocean',have)
    with pytest.raises(ValueError,match='比较'):comparison_pairs('ocean',{('ocean',1,1),('ocean',4,2)},coarse=True)


def test_profile_slots_configurable():
    from bench.layer1 import profile_slots
    assert len(profile_slots({}))==9
    assert profile_slots({'profile_slots':[['ocean',4]]})==[('ocean',4)]
    for bad in ([['lava',4]],[['ocean',3]],[['ocean',4],['ocean',4]]):
        with pytest.raises(ValueError):profile_slots({'profile_slots':bad})

def test_escape_in_manifest_rejected(tmp_path):
    outside=tmp_path/'outside';outside.write_text('x')
    archive=tmp_path/'archive';archive.mkdir()
    atomic_json(archive/'manifest.json',{'status':'passed','protocol_id':'p','artifacts':{'../outside':'fake'}})
    with pytest.raises(ValueError,match='摘要'):verify(archive,'p')

def test_cleanup_not_finished_is_not_reusable(tmp_path):
    (tmp_path/'result.json').write_text('{}')
    seal(tmp_path,{'status':'passed','protocol_id':'p','cleanup_completed':False})
    with pytest.raises(ValueError,match='清理'):verify(tmp_path,'p')


def test_warmup_by_cpu():
    # 11-08 调度批准：1 核单侧判据不满足时按核数预热，写进 protocol。
    from bench.layer1 import warmup_for
    p = {'warmup_chunks': 2048, 'warmup_by_cpu': {'1': 4096, '2': 2048, '4': 2048}}
    assert [warmup_for(p, c) for c in (1, 2, 4)] == [4096, 2048, 2048]
    assert warmup_for({'warmup_chunks': 1024}, 1) == 1024
    with pytest.raises(KeyError):
        warmup_for({'warmup_chunks': 2048, 'warmup_by_cpu': {'1': 4096}}, 2)
    for bad in (0, 300, -256, 2048.0):
        with pytest.raises(ValueError):
            warmup_for({'warmup_chunks': bad}, 1)
