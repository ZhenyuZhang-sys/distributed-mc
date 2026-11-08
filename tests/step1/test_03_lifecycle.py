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

def test_stale_chunky_tasks_quarantined(monkeypatch,tmp_path):
    # 中断的那轮会留下 Chunky 任务文件；换新世界前要移走（留作证据），不然新世界的 chunky start 被拒绝。
    import bench.server as srv
    tasks=tmp_path/'tasks';(tasks/'minecraft').mkdir(parents=True)
    (tasks/'minecraft'/'overworld.properties').write_text('cancelled=false\n')
    monkeypatch.setattr(srv,'CHUNKY_TASKS',tasks)
    moved=srv.quarantine_chunky_tasks(tmp_path/'run')
    assert moved==['minecraft/overworld.properties']
    assert not (tasks/'minecraft'/'overworld.properties').exists()
    assert (tmp_path/'run'/'stale-chunky-tasks'/'minecraft'/'overworld.properties').read_text()=='cancelled=false\n'
    assert srv.quarantine_chunky_tasks(tmp_path/'run2')==[]
    monkeypatch.setattr(srv,'CHUNKY_TASKS',tmp_path/'missing')
    assert srv.quarantine_chunky_tasks(tmp_path/'run3')==[]

def test_chunky_start_fails_fast_on_stale_task():
    import bench.server as srv
    class Fake:
        def __init__(self,reply):self.reply=reply;self.sent=[]
        def rcon(self,c):self.sent.append(c);return self.reply
    busy=Fake("[Chunky] A task was already started for this world. To continue running it, type '/chunky continue'. To start a new task, type '/chunky confirm'.\n")
    with pytest.raises(RuntimeError,match='残留任务'):srv.start_chunky(busy)
    assert busy.sent==['chunky start']
    ok=Fake('[Chunky] Task started for minecraft:overworld.\n')
    assert 'Task started' in srv.start_chunky(ok)
