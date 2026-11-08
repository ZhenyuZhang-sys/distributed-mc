"""差异必须定位到独立设定的方块，不能只统计 hash。"""
import nbtlib as n
from analysis.mca import read_chunk
from mca_fixtures import world, write_chunk, chunk, section
from bench.determinism import compare_chunk

def test_single_changed_block(tmp_path):
    a=world(tmp_path/'a');b=world(tmp_path/'b')
    write_chunk(a,tag=chunk(sections=[section(names=('minecraft:stone','minecraft:dirt'),words=[0]*256)]))
    write_chunk(b,tag=chunk(sections=[section(names=('minecraft:stone','minecraft:dirt'),words=[1]+[0]*255)]))
    result,details=compare_chunk(read_chunk(a,0,0),read_chunk(b,0,0))
    assert result['different_columns']==1 and result['different_blocks']==1
    assert result['tier']=='few'
    assert (details[0]['x'],details[0]['y'],details[0]['z'])==(0,-64,0)

def test_biome_palette_and_y(tmp_path):
    root=world(tmp_path)
    tag=chunk()
    # 64 个 biome cell，每 cell 4×4×4；2 bit / cell，每 long 32 项。
    tag['sections'][0]['biomes']=n.Compound({'palette':n.List[n.String](['minecraft:ocean','minecraft:jungle','minecraft:plains']),
                                           'data':n.LongArray([0,2])})
    write_chunk(root,tag=tag)
    c=read_chunk(root,0,0)
    assert c.biome(0,-64,0)=='minecraft:ocean'
    assert c.biome(0,-56,0)=='minecraft:plains'
