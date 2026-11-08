"""从宿主机读取 cgroup v2，计量不向容器注入采样进程。"""
import json
from pathlib import Path
import re
import threading
import time

def parse_cpu_stat(text):
    values={k:int(v) for k,v in (line.split() for line in text.splitlines())}
    if not {'usage_usec','user_usec','system_usec'} <= values.keys() or min(values.values()) < 0:
        raise ValueError('缺少有效的 cgroup CPU 计数')
    return values

def metrics(t0,t1,c0,c1,n,cpus):
    if t1<=t0 or c1<c0 or n<=0 or cpus<=0: raise ValueError('计数倒退、样本或时长无效')
    wall=(t1-t0)/1e9
    cpu=(c1-c0)/1000
    return dict(wall_s=wall,cpu_ms=cpu,chunks_per_s=n/wall,cpu_ms_per_chunk=cpu/n,
                avg_cpu_cores=cpu/1000/wall,quota_utilization=cpu/1000/wall/cpus)

class Cgroup:
    def __init__(self, cid, run):
        info=json.loads(run('docker','inspect',cid))[0]
        self.cid=info['Id']; self.pid=info['State']['Pid']
        self._read_lock=threading.Lock()
        if self.pid<=0: raise RuntimeError('容器未运行')
        entry=next(line.split(':',2)[2] for line in Path(f'/proc/{self.pid}/cgroup').read_text().splitlines() if line.startswith('0::'))
        # 按 mountinfo 的 root 和 mountpoint 解析，不假设 Docker 的 cgroup 命名。
        self.path=None
        for line in Path('/proc/self/mountinfo').read_text().splitlines():
            left,right=line.split(' - ',1)
            if right.split()[0]!='cgroup2': continue
            cols=left.split(); root=cols[3]; mount=cols[4]
            if root=='/' or entry==root or entry.startswith(root+'/'):
                relative=entry.lstrip('/') if root=='/' else entry[len(root):].lstrip('/')
                self.path=Path(mount)/relative
                break
        if self.path is None or not (self.path/'cpu.stat').is_file(): raise RuntimeError('无法定位 cgroup v2')
        self.identity=(self.path.stat().st_dev,self.path.stat().st_ino)
        self.run=run
    def sample(self):
        with self._read_lock:
            t0=time.monotonic_ns()
            if self.identity!=(self.path.stat().st_dev,self.path.stat().st_ino): raise RuntimeError('cgroup 身份改变')
            cpu=parse_cpu_stat((self.path/'cpu.stat').read_text())
            t1=time.monotonic_ns()
            return dict(time_ns=(t0+t1)//2,read_start_ns=t0,read_end_ns=t1,**cpu)

    def resources(self):
        result={}
        for name in ('cpu.max','cpuset.cpus.effective','memory.current','memory.peak','memory.swap.max','memory.events'):
            p=self.path/name
            result[name]=p.read_text().strip() if p.exists() else None
        result['host_cpu']=Path('/proc/stat').read_text()
        return result
    def verify(self, cpus, cpuset):
        actual=json.loads(self.run('docker','inspect',self.cid))[0]
        if actual['State']['Pid']!=self.pid or not actual['State']['Running']: raise RuntimeError('容器已重启')
        quota,period=map(int,(self.path/'cpu.max').read_text().split())
        if abs(quota/period-cpus)>.001 or (self.path/'cpuset.cpus.effective').read_text().strip()!=cpuset:
            raise RuntimeError('CPU 配额或绑定不符')
        if actual['State']['OOMKilled']: raise RuntimeError('容器 OOM')

class Sampler:
    def __init__(self,cgroup,interval=.25):
        self.cg=cgroup; self.interval=interval; self.rows=[]; self.error=None
        self.done=threading.Event(); self.thread=threading.Thread(target=self._loop,daemon=True)
    def _loop(self):
        try:
            while not self.done.is_set():
                self.rows.append(self.cg.sample())
                self.done.wait(self.interval)
        except Exception as exc: self.error=exc
    def __enter__(self): self.thread.start(); return self
    def __exit__(self,*args):
        self.done.set(); self.thread.join()
        if self.error: raise self.error
        if any(b['usage_usec']<a['usage_usec'] for a,b in zip(self.rows,self.rows[1:])):
            raise RuntimeError('采样计数倒退')
