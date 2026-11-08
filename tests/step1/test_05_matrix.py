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

def test_escape_in_manifest_rejected(tmp_path):
    outside=tmp_path/'outside';outside.write_text('x')
    archive=tmp_path/'archive';archive.mkdir()
    atomic_json(archive/'manifest.json',{'status':'passed','protocol_id':'p','artifacts':{'../outside':'fake'}})
    with pytest.raises(ValueError,match='摘要'):verify(archive,'p')

def test_cleanup_not_finished_is_not_reusable(tmp_path):
    (tmp_path/'result.json').write_text('{}')
    seal(tmp_path,{'status':'passed','protocol_id':'p','cleanup_completed':False})
    with pytest.raises(ValueError,match='清理'):verify(tmp_path,'p')
