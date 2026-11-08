"""历史会话不能操作新实例；失败结果不能重试成通过。"""
from pathlib import Path
import pytest
from bench.server import Server

def test_retired_cannot_send_command(monkeypatch,tmp_path):
    s=Server('t',424242,'now',artifact_dir=tmp_path)
    calls=[]
    monkeypatch.setattr('bench.server.compose',lambda *a,**kw:calls.append(a))
    monkeypatch.setattr(s,'archive_logs',lambda dest:dest)
    monkeypatch.setattr(s,'error_lines',lambda:[])
    assert s.finish()==tmp_path
    s.finish()
    assert calls==[('stop',)]
    with pytest.raises(RuntimeError,match='会话已结束'): s.rcon('seed')

def test_failed_finish_stays_failed(monkeypatch,tmp_path):
    s=Server('t',424242,'now',artifact_dir=tmp_path)
    monkeypatch.setattr('bench.server.compose',lambda *a,**kw:None)
    monkeypatch.setattr(s,'archive_logs',lambda dest:dest)
    monkeypatch.setattr(s,'error_lines',lambda:['early ERROR'])
    for _ in range(2):
        with pytest.raises(RuntimeError,match='early ERROR'): s.finish()

def test_manager_preserves_failed_finish(monkeypatch,tmp_path):
    from bench.session import ServerManager
    s=Server('t',424242,'now',artifact_dir=tmp_path)
    monkeypatch.setattr('bench.server.compose',lambda *a,**kw:None)
    monkeypatch.setattr(s,'archive_logs',lambda dest:dest)
    monkeypatch.setattr(s,'error_lines',lambda:['earlier ERROR'])
    manager=ServerManager(lambda:iter([s]));manager.active=s;manager.phase=0
    for _ in range(2):
        with pytest.raises(RuntimeError,match='earlier ERROR'):manager.finish()

def test_stop_failure_still_archives(monkeypatch,tmp_path):
    s=Server('t',424242,'now',artifact_dir=tmp_path)
    calls=[]
    def stop(*args,**kwargs):raise RuntimeError('stop failed')
    monkeypatch.setattr('bench.server.compose',stop)
    monkeypatch.setattr(s,'archive_logs',lambda path:calls.append(path))
    with pytest.raises(RuntimeError,match='stop failed'):s.finish()
    assert calls

def test_interruption_archives_and_restores_cpu_environment(monkeypatch,tmp_path):
    import json,os
    from bench.models import Area
    from bench.layer1 import one_run
    closed=[]
    class FakeSession:
        finished=False
        def __init__(self,*a,**kw):pass
        def start(self):raise KeyboardInterrupt('test interrupt')
        def finish(self):closed.append(True);self.finished=True
    monkeypatch.setattr('bench.layer1.WorldSession',FakeSession)
    monkeypatch.setattr('bench.session.cpu_pool',lambda:[12,13,14,15])
    previous=os.environ.get('MC_CPUSET')
    with pytest.raises(KeyboardInterrupt):
        one_run(tmp_path/'attempt',Area('ocean',0,0,15,15,200,200),1,1,{'protocol_id':'p'})
    assert os.environ.get('MC_CPUSET')==previous
    assert closed==[True]
    assert json.loads((tmp_path/'attempt/manifest.json').read_text())['status']=='interrupted'
