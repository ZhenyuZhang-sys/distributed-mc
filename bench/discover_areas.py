"""按预先固定 biome 类别选区；选区不读取吞吐结果。"""
from collections import Counter
import json
from pathlib import Path
import re
from analysis.mca import read_chunk
from .environment import configure,software
from .models import ROOT, Area, atomic_json
from .session import WorldSession

BIOMES={
 'ocean':{'minecraft:'+s for s in ('ocean','deep_ocean','cold_ocean','deep_cold_ocean','frozen_ocean','deep_frozen_ocean','lukewarm_ocean','deep_lukewarm_ocean','warm_ocean')},
 'mountain':{'minecraft:'+s for s in ('jagged_peaks','frozen_peaks','stony_peaks','snowy_slopes','grove','meadow')},
 'jungle':{'minecraft:'+s for s in ('jungle','sparse_jungle','bamboo_jungle')},
}
LOCATE={'ocean':'deep_ocean','mountain':'jagged_peaks','jungle':'jungle'}
AIR={'minecraft:air','minecraft:cave_air','minecraft:void_air'}

def sample_chunk(chunk):
    counts=Counter();heights=[]
    for z in (2,6,10,14):
        for x in (2,6,10,14):
            y=chunk.y_max_exclusive-1
            while y>chunk.y_min and chunk.block_state(x,y,z).name in AIR:y-=1
            counts[chunk.biome(x,y,z)]+=1;heights.append(y)
    return counts,min(heights),max(heights)

def discover(directory,output=ROOT/'bench/areas.json'):
    world=WorldSession(directory,prefix='discovery')
    results=[]; attempts=[]
    try:
        server=world.start();configure(server)
        actual_software=software(server)
        for index,kind in enumerate(LOCATE):
            chosen=None
            for attempt in range(12):
                origin=(20000+attempt*20000,(index+1)*30000)
                command=f'execute positioned {origin[0]} 128 {origin[1]} run locate biome minecraft:{LOCATE[kind]}'
                response=server.rcon(command)
                found=re.search(r'\[(-?\d+),\s*(?:~|-?\d+),\s*(-?\d+)\]',response)
                if not found:
                    attempts.append(dict(kind=kind,command=command,response=response,error='locate 未命中'))
                    continue
                bx,bz=map(int,found.groups());cx,cz=bx//16,bz//16
                x0,z0=cx-16,cz-16
                server.chunky_square(x0,z0,32)
                snap=server.snapshot(world.directory/f'candidate-{kind}-{attempt}')
                samples={}
                for x in range(x0,x0+32):
                    for z in range(z0,z0+32):
                        c=read_chunk(snap,x,z)
                        if c is None or c.status!='minecraft:full':raise RuntimeError('勘探区域不完整')
                        samples[x,z]=sample_chunk(c)
                candidates=[]
                for dx in (0,8,16):
                    for dz in (0,8,16):
                        counts=Counter();ys=[]
                        for x in range(x0+dx,x0+dx+16):
                            for z in range(z0+dz,z0+dz+16):
                                counts.update(samples[x,z][0]);ys.extend(samples[x,z][1:])
                        fraction=sum(n for name,n in counts.items() if name in BIOMES[kind])/4096
                        candidates.append((fraction,x0+dx,z0+dz,dict(counts),[min(ys),max(ys)]))
                best=max(candidates,key=lambda row:row[0])
                attempts.append(dict(kind=kind,command=command,response=response,best_fraction=best[0]))
                atomic_json(world.directory/'search.json',attempts)
                if best[0]>=.70:
                    fraction,x,z,counts,height=best
                    area=Area(kind,x,z,x+15,z+15,x+256,z+256)
                    chosen=dict(area=area.json(),search_command=command,search_response=response,hit=[bx,bz],
                                biome_fraction=fraction,biomes=counts,height_range=height,whitelist=sorted(BIOMES[kind]))
                    break
            if chosen is None:raise RuntimeError(f'{kind} 经过 12 个固定搜索原点仍达不到 70%')
            results.append(chosen)
            print(f'选区 {kind}: {chosen["area"]}，biome {chosen["biome_fraction"]:.1%}',flush=True)
        world.finish()
        atomic_json(output,dict(schema_version=1,seed=424242,dimension='minecraft:overworld',areas=results,
                                sampling='x/z=2,6,10,14; highest non-air including water/leaves',source=str(world.directory),software=actual_software))
        world.cleanup()
    finally:
        if not world.finished:world.finish()
