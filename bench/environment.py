"""可复查的运行前检查与软件产物身份。"""
import json
from pathlib import Path
from .models import ROOT, atomic_json, digest, code_digest
from .server import SCRIPTS, TEST_DATA, compose, limits, run
from .session import cpu_pool, require_managed, shell_function

STAMP=ROOT/'build/bench-build.json'

def java_sources():
    files=list(ROOT.glob('*.gradle'))+list(ROOT.glob('gradle.properties'))
    for module in ('common','bench','coordinator'):
        files.extend((ROOT/module/'src').rglob('*'))
        files.extend((ROOT/module).glob('*.gradle'))
    return {str(p.relative_to(ROOT)):digest(p) for p in sorted(files) if p.is_file()}

def stamp():
    jars={p.name:digest(p) for p in sorted((ROOT/'build/mods').glob('*.jar'))}
    if len(jars)<2: raise RuntimeError('缺少 bench/coordinator 构建产物')
    atomic_json(STAMP,dict(java_sources=java_sources(),jars=jars))

def preflight():
    require_managed()
    shell_function('prepare_heavy')
    run(str(SCRIPTS/'test_config.sh'),'check')
    cpu_pool()
    if not STAMP.exists(): raise RuntimeError('请先运行 scripts/bench.sh prepare')
    saved=json.loads(STAMP.read_text())
    if saved['java_sources']!=java_sources() or saved['jars']!={p.name:digest(p) for p in sorted((ROOT/'build/mods').glob('*.jar'))}:
        raise RuntimeError('源码/构建产物改变，请重新 prepare')
    image=json.loads(run('docker','image','inspect','itzg/minecraft-server:java21'))[0]
    return dict(code_digest=code_digest(),build=saved,image_id=image['Id'],image_digests=image.get('RepoDigests',[]),
                limits=limits(),cpu_pool=cpu_pool(),hardware=next(line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')),frequency=run(str(SCRIPTS/'test_config.sh'),'status'))

def configure(server):
    for rule,value in (('randomTickSpeed','0'),('doMobSpawning','false'),('doDaylightCycle','false'),('doWeatherCycle','false')):
        server.rcon(f'gamerule {rule} {value}')
        actual=server.rcon(f'gamerule {rule}')
        if not actual.endswith(': '+value):raise RuntimeError(f'gamerule 不符：{actual}')
    server.rcon('time set noon');server.rcon('weather clear')
    server.rcon('bench timing off')
    if json.loads(server.rcon('bench stages'))['timing']:raise RuntimeError('阶段计时未关闭')
    if not server.rcon('list').startswith('There are 0 of'):raise RuntimeError('实验世界中存在玩家')


def software(server):
    info=json.loads(run('docker','inspect',compose('ps','-q','minecraft').strip()))[0]
    return dict(image_id=info['Image'],java=compose('exec','-T','minecraft','sh','-c','java -version 2>&1'),
                mods={p.name:digest(p) for p in sorted((TEST_DATA/'mods').glob('*.jar'))})
