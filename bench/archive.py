"""结果和摘要原子提交，恢复时逐文件复核。"""
import json
from pathlib import Path
from .models import atomic_json,digest

def seal(directory,manifest):
    directory=Path(directory)
    manifest={**manifest,'artifacts':{str(p.relative_to(directory)):digest(p) for p in sorted(directory.rglob('*'))
        if p.is_file() and p.name!='manifest.json' and not p.name.endswith('.tmp')}}
    atomic_json(directory/'manifest.json',manifest)
    return manifest

def verify(directory,protocol):
    directory=Path(directory)
    m=json.loads((directory/'manifest.json').read_text())
    if m['protocol_id']!=protocol:raise ValueError('归档协议不符')
    if m['status']!='passed':raise ValueError('归档不是成功状态')
    if m.get('cleanup_completed') is False:raise ValueError('归档后的清理未完成')
    for relative,expected in m['artifacts'].items():
        path=(directory/relative).resolve()
        if not path.is_relative_to(directory.resolve()) or not path.is_file() or digest(path)!=expected:
            raise ValueError(f'归档产物摘要不符：{relative}')
    if not m['artifacts']:raise ValueError('归档产物为空')
    return m
