"""新的受管世界中真实生成 2×2，包含计量和离线读取。"""
import os
from pathlib import Path
from analysis.mca import read_chunk,read_world_metadata
from bench.environment import configure
from bench.metrics import Cgroup
from bench.server import compose,run,limits
from bench.session import WorldSession

def test_fresh_world_and_cgroup(server_manager):
    server_manager.enter_benchmark()
    world=WorldSession(Path(os.environ['MCD_ARTIFACT_DIR'])/'step1-smoke',prefix='smoke')
    try:
        server=world.start();configure(server)
        cg=Cgroup(compose('ps','-q','minecraft').strip(),run)
        cg.verify(int(limits()['MC_CPUS']),limits()['MC_CPUSET'])
        before=server.snapshot(world.directory/'before')
        assert read_chunk(before,-400,-400) is None
        c0=cg.sample();task=server.chunky_square(-400,-400,2);c1=cg.sample()
        assert task['count']==4 and c1['usage_usec']>c0['usage_usec']
        world.finish()
        snapshot=server._copy_world(world.directory/'snapshot')
        assert read_world_metadata(snapshot).seed==424242
        for x,z in task['expected']:
            c=read_chunk(snapshot,x,z)
            assert c is not None and c.status=='minecraft:full'
            assert c.biome(2,64,2).startswith('minecraft:')
        world.cleanup()
    finally:
        if not world.finished:world.finish()
