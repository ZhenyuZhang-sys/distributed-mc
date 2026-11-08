"""串行世界上下文；全部外部操作仍经公共脚本。"""
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import time
from .server import REPO_ROOT, SCRIPTS, Server, compose, limits, quarantine_chunky_tasks, run, wait_until
from .models import atomic_json, digest


def require_managed():
    try:
        lock=Path('/tmp/mc-donate-heavy.lock').stat()
        fd=os.fstat(9)
        if (lock.st_dev,lock.st_ino)!=(fd.st_dev,fd.st_ino): raise RuntimeError('锁文件身份不符')
        fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except (OSError,RuntimeError) as exc:
        raise RuntimeError('必须经 scripts/bench.sh 或 scripts/test.sh 持共享锁运行') from exc
    if os.environ.get('MCD_LOCK_HELD')!='1': raise RuntimeError('缺少持锁上下文')


def cpu_pool():
    text=limits()['MC_CPUSET']; out=[]
    for part in text.split(','):
        if '-' in part:
            lo,hi=map(int,part.split('-'));out.extend(range(lo,hi+1))
        else:out.append(int(part))
    if len(out)!=4 or len(set(out))!=4: raise ValueError('需要四个物理核的固定 CPU 池')
    cores=[]
    caches=[];nodes=[]
    for cpu in out:
        base=Path(f'/sys/devices/system/cpu/cpu{cpu}/topology')
        cores.append(((base/'physical_package_id').read_text(),(base/'core_id').read_text()))
        cpu_dir=base.parent
        online=cpu_dir/'online'
        if online.exists() and online.read_text().strip()!='1':raise ValueError('CPU 池存在离线核')
        caches.append((cpu_dir/'cache/index3/shared_cpu_list').read_text().strip())
        nodes.append(tuple(p.name for p in cpu_dir.glob('node[0-9]*')))
    if len(set(cores))!=4: raise ValueError('CPU 池包含 SMT 兄弟')
    if len(set(caches))!=1 or len(set(nodes))!=1:raise ValueError('CPU 池跨越 L3 或 NUMA 节点')
    return out


@contextmanager
def resource_environment(count):
    if count not in (1,2,4):raise ValueError('配额只允许 1/2/4 核')
    pool=cpu_pool()
    cpuset=str(pool[0]) if count==1 else ','.join(map(str,pool[:count]))
    # Docker 保留区间表示，规范化到连续范围，方便核验实际值。
    if count>1 and pool[:count]==list(range(pool[0],pool[0]+count)): cpuset=f'{pool[0]}-{pool[count-1]}'
    saved={key:os.environ.get(key) for key in ('MC_CPUS','MC_CPUSET')}
    os.environ.update(MC_CPUS=str(count),MC_CPUSET=cpuset)
    try:yield cpuset
    finally:
        for key,value in saved.items():
            if value is None:os.environ.pop(key,None)
            else:os.environ[key]=value


def shell_function(name,*args):
    if name not in ('assert_test_instance','delete_test_world','prepare_heavy'):raise ValueError(name)
    return run('bash','-c','source "$1"; shift; '+name+' "$@"','bash',str(SCRIPTS/'lib.sh'),*args)


class WorldSession:
    def __init__(self,directory,prefix='l1',seed=424242):
        require_managed()
        self.directory=Path(directory);self.directory.mkdir(parents=True,exist_ok=False)
        self.level=f'{prefix}_{time.time_ns()}'
        self.server=Server(self.level,seed,datetime.now(timezone.utc).isoformat(),artifact_dir=self.directory)
        self.finished=False;self.error=None;self.stale_chunky_tasks=[]
    def start(self):
        try:
            # 服务器停着时才清：每轮都是全新世界，这时的 Chunky 任务文件一定是残留。
            if not compose('ps','-q','minecraft').strip():
                self.stale_chunky_tasks=quarantine_chunky_tasks(self.directory)
            run(str(SCRIPTS/'new_world.sh'),self.level,str(self.server.seed),timeout=900)
            wait_until(lambda:'Done (' in self.server.logs(),timeout=60,what='服务器启动完成')
            if self.server.health()!='healthy': raise RuntimeError('服务器未就绪')
            if self.server.rcon('seed')!=f'Seed: [{self.server.seed}]': raise RuntimeError('seed 不符')
            return self.server
        except BaseException:
            self.finish()
            raise
    def finish(self):
        if self.finished:
            if self.error:raise RuntimeError(self.error)
            return
        try:self.server.finish()
        except BaseException as exc:
            self.error=str(exc)
            raise
        finally:
            self.finished=True
            atomic_json(self.directory/'session.json',dict(level=self.level,finished=True,error=self.error,stale_chunky_tasks=self.stale_chunky_tasks))
    def cleanup(self):
        if not self.finished or self.error:raise RuntimeError('未成功归档的世界不能删除')
        shell_function('assert_test_instance')
        shell_function('delete_test_world',self.level)


class ServerManager:
    def __init__(self,factory):
        self.factory=factory;self.active=None;self.phase=-1;self.generator=None
    def acquire(self,phase):
        if phase<self.phase:raise RuntimeError('不能重新使用已结束的测试阶段')
        if phase!=self.phase:
            self.finish()
            self.phase=phase
            if phase==0:
                self.generator=self.factory()
                self.active=next(self.generator)
            else:
                base=Path(os.environ['MCD_ARTIFACT_DIR'])/f'phase-{phase}'
                self.world=WorldSession(base,prefix=f's{phase}')
                self.active=self.world.start()
        return self.active
    def enter_benchmark(self):
        if self.phase > 1: raise RuntimeError('不能从后续阶段回到基线实验')
        self.finish()
        self.phase = 1
    def finish(self):
        if self.active is not None:self.active.finish()
        if self.generator:
            gen,self.generator=self.generator,None
            gen.close()
        self.active=None
