"""冻结协议、坐标集合与实验槽位。"""
from dataclasses import asdict, dataclass
import hashlib
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AREA_IDS = ('ocean', 'mountain', 'jungle')

def canonical(data):
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()

def protocol_id(data):
    return hashlib.sha256(canonical({k:v for k,v in data.items() if k != 'protocol_id'})).hexdigest()

def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for buf in iter(lambda: stream.read(1024*1024), b''): h.update(buf)
    return h.hexdigest()

def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    with tmp.open('wb') as stream:
        stream.write(canonical(value) + b'\n')
        stream.flush()
        import os
        os.fsync(stream.fileno())
    tmp.replace(path)

@dataclass(frozen=True)
class Area:
    id: str
    min_cx: int
    min_cz: int
    max_cx: int
    max_cz: int
    warm_cx: int
    warm_cz: int
    def __post_init__(self):
        if any(type(v) is not int for v in (self.min_cx,self.min_cz,self.max_cx,self.max_cz,self.warm_cx,self.warm_cz)):
            raise ValueError('chunk 坐标必须是整数')
        if self.id not in AREA_IDS or self.max_cx-self.min_cx != 15 or self.max_cz-self.min_cz != 15:
            raise ValueError('区域必须是命名的 16×16 chunk 方形')
        dx = max(self.min_cx-(self.warm_cx+15), self.warm_cx-self.max_cx, 0)
        dz = max(self.min_cz-(self.warm_cz+15), self.warm_cz-self.max_cz, 0)
        if max(dx,dz) < 64: raise ValueError('预热区距离目标不足 64 chunk')
    def positions(self):
        return tuple((x,z) for x in range(self.min_cx,self.max_cx+1) for z in range(self.min_cz,self.max_cz+1))
    def json(self): return asdict(self)

def load_areas(path=ROOT/'bench/areas.json'):
    data=json.loads(Path(path).read_text())
    if data['seed'] != 424242 or data['dimension'] != 'minecraft:overworld': raise ValueError('区域协议不符')
    result=[Area(**row['area']) for row in data['areas']]
    if sorted(a.id for a in result) != sorted(AREA_IDS): raise ValueError('必须恰好三个区域')
    for row in data['areas']:
        if row['biome_fraction'] < .70: raise ValueError('区域 biome 比例不足')
    return result

def matrix(seed,repeats=5):
    """轮数来自 protocol 的 repeats；少跑几轮是完整矩阵的前缀。"""
    if type(repeats) is not int or repeats<2:raise ValueError('每配置至少 2 轮才能算 CV')
    rng=random.Random(seed)
    result=[]
    for repeat in range(1,repeats+1):
        group=[(area,cpu,repeat) for area in AREA_IDS for cpu in (1,2,4)]
        rng.shuffle(group)
        result.extend(group)
    return result

def code_digest():
    files=[]
    for base in ('bench','analysis','scripts','docker'):
        files.extend(p for p in (ROOT/base).rglob('*') if p.is_file() and
                     p.suffix in ('.py','.sh','.java','.gradle','.yaml','.env') and
                     not any(x in p.parts for x in ('build','test-data','data','__pycache__')) and
                     not p.name.startswith('.'))
    return hashlib.sha256(b''.join(str(p.relative_to(ROOT)).encode()+b'\0'+p.read_bytes() for p in sorted(files))).hexdigest()

def validate_campaign_id(value):
    import re
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}',value):raise ValueError('非法 campaign ID')
    return value
