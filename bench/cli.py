"""受共享锁管理的命令入口。"""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
from .models import ROOT
from .environment import preflight,stamp
from .session import require_managed

def main():
    import signal
    def interrupted(signum, frame):
        raise KeyboardInterrupt(f"收到信号 {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    p=argparse.ArgumentParser()
    p.add_argument('action',choices=['stamp','preflight','discover','pilot','run','profile'])
    p.add_argument('--seed',type=int,default=424242)
    p.add_argument('--output',type=Path,default=ROOT/'bench/areas.json')
    p.add_argument('--areas',type=Path,default=ROOT/'bench/areas.json')
    p.add_argument('--protocol',type=Path,default=ROOT/'bench/protocol.json')
    p.add_argument('--resume');p.add_argument('--baseline')
    args=p.parse_args();require_managed()
    if args.action=='stamp':stamp();return
    env=preflight()
    if args.action=='preflight':print(json.dumps(env,ensure_ascii=False,indent=2));return
    if args.seed!=424242:raise ValueError('当前协议只支持 seed=424242')
    if args.action=='discover':
        from .discover_areas import discover
        path=ROOT/'artifacts'/('discovery-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S'))
        discover(path,args.output)
    else:
        from .layer1 import execute
        execute(args.action,args.areas,args.protocol,args.resume or args.baseline)

if __name__=='__main__':main()
