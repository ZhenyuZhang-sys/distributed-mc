"""单轮状态机、冻结协议、矩阵和中断恢复。"""
from datetime import datetime,timezone
import csv
import json
import os
from pathlib import Path
import signal
import shutil
import statistics
import time
from analysis.mca import read_chunk,read_world_metadata,iter_chunk_positions,HASH_SCHEMA
from .archive import seal,verify
from .environment import configure,preflight,software
from .events import LogStream
from .metrics import Cgroup,Sampler,metrics
from .models import ROOT,atomic_json,canonical,digest,load_areas,matrix,protocol_id
from .server import compose,run,start_chunky
from .session import WorldSession,resource_environment

RUNS=ROOT/'analysis/results/runs'

def write_csv(path,rows):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    if not rows:raise ValueError('没有可导出的行')
    fields=list(dict.fromkeys(k for row in rows for k in row))
    tmp=path.with_name(path.name+'.tmp')
    with tmp.open('w',newline='') as out:
        writer=csv.DictWriter(out,fieldnames=fields);writer.writeheader();writer.writerows(rows)
    tmp.replace(path)

def warmup_for(p,cpus):
    """预热量。protocol 有 warmup_by_cpu（11-08 调度批准的按核数预热）就按核数取，否则用统一的 warmup_chunks。"""
    by=p.get('warmup_by_cpu')
    n=by[str(cpus)] if by else p['warmup_chunks']
    if type(n) is not int or n<=0 or n%256:raise ValueError('预热量必须是 256 的正整数倍')
    return n

def protocol(areas_path,environment,warmup=256):
    return dict(schema_version=1,seed=424242,dimension='minecraft:overworld',areas_digest=digest(areas_path),
                software=json.loads(Path(areas_path).read_text())['software'],
                environment=environment,python=run(str(ROOT/'.venv/bin/python'),'-m','pip','freeze'),
                hash_schema=HASH_SCHEMA,timing_enabled=False,target_chunks=256,warmup_chunks=warmup,
                settle_seconds=5,sample_interval=.25,timeout_seconds=900,idle_cores=.15,
                matrix_seed=424242,repeats=5,cv_threshold=.10,uncertainty_fraction=.02,
                difference_few_columns=4,guard_chunks=1,
                gamerules=dict(randomTickSpeed=0,doMobSpawning=False,doDaylightCycle=False,doWeatherCycle=False),
                time='noon',weather='clear',view_distance=10,simulation_distance=6)

def freeze(path,data):
    data={**data,'protocol_id':protocol_id(data)}
    atomic_json(path,data);return data

def settle(cg,p):
    start=time.monotonic();stable=None;before=cg.sample()
    while time.monotonic()-start<120:
        time.sleep(.5);after=cg.sample()
        cores=(after['usage_usec']-before['usage_usec'])*1000/(after['time_ns']-before['time_ns'])
        before=after
        if cores<=p['idle_cores']:
            if stable is None:stable=time.monotonic()
            if time.monotonic()-stable>=p['settle_seconds']:return
        else:stable=None
    raise TimeoutError('预热后 120 秒内没有连续空闲稳定窗口')

def empty(snapshot,area,guard=1):
    positions=set(iter_chunk_positions(snapshot))
    forbidden={(x,z) for x in range(area.min_cx-guard,area.max_cx+guard+1)
                      for z in range(area.min_cz-guard,area.max_cz+guard+1)}
    overlap=sorted(positions & forbidden)
    if overlap:raise RuntimeError(f'目标或保护区已存在 chunk：{overlap[:10]}')
    return sorted(positions)

def select(server,area):
    name=f'l1-{time.time_ns()}'
    from .server import TEST_DATA
    path=TEST_DATA/'config/chunky'/f'{name}.csv'
    path.write_text(''.join(f'{x},{z}\n' for x,z in area.positions()))
    shutil.copy2(path,server.artifact_dir/'target.csv')
    commands=['chunky world minecraft:overworld','chunky shape square',
              f'chunky corners {area.min_cx*16} {area.min_cz*16} {(area.max_cx+1)*16-1} {(area.max_cz+1)*16-1}',
              f'chunky pattern csv {name}']
    for command in commands:server.rcon(command)
    selection=server.rcon('chunky selection')
    atomic_json(server.artifact_dir/'selection.json',dict(selection=selection,csv_sha256=digest(path),coordinates=area.positions()))
    return path

def one_run(directory,area,cpus,repeat,p,order_index=0,profile=False,warmup=None):
    directory=Path(directory)
    manifest=dict(status='running',protocol_id=p['protocol_id'],area_id=area.id,cpu_count=cpus,repeat=repeat,
                  order_index=order_index,attempt_id=directory.name,started_utc=datetime.now(timezone.utc).isoformat())
    directory.mkdir(parents=True,exist_ok=False);atomic_json(directory/'manifest.json',manifest)
    world=None;stream=None;csv_path=None
    try:
        with resource_environment(cpus) as cpuset:
            world=WorldSession(directory/'server',prefix=f'l1{area.id[0]}{cpus}')
            server=world.start();configure(server)
            actual=software(server)
            if actual['image_id']!=p['environment']['image_id']:raise RuntimeError('容器镜像改变')
            expected_mods=p.get('software')
            if expected_mods is not None and actual!=expected_mods:raise RuntimeError('实际 Java/mod 清单改变')
            atomic_json(directory/'software.json',actual)
            cid=compose('ps','-q','minecraft').strip();cg=Cgroup(cid,run);cg.verify(cpus,cpuset)
            manifest.update(container_id=cg.cid,cgroup_path=str(cg.path),world_name=world.level,cpuset=cpuset)
            atomic_json(directory/'manifest.json',manifest)
            n_warm=warmup or warmup_for(p,cpus)
            side=16
            if n_warm%256:raise ValueError('预热量必须是 256 的倍数')
            for offset in range(n_warm//256):server.chunky_square(area.warm_cx+offset*32,area.warm_cz,side)
            settle(cg,p)
            before=server.snapshot(directory/'pre-snapshot')
            positions=empty(before,area,p['guard_chunks'])
            atomic_json(directory/'pre-index.json',dict(coordinates=positions,seed=read_world_metadata(before).seed))
            csv_path=select(server,area)
            server.rcon('bench timing off');server.rcon('bench reset')
            if profile:server.rcon('bench timing on')
            if json.loads(server.rcon('bench stages'))['timing'] != profile:raise RuntimeError('计时开关不符')
            stream=LogStream(directory/'events.jsonl')
            # 日志订阅握手：先发一条会写日志的命令并等流收到它。
            server.rcon('save-all flush')
            deadline=time.monotonic()+15
            while not stream.rows:
                if time.monotonic()>deadline:raise TimeoutError('日志流订阅未就绪')
                time.sleep(.02)
            resources0=cg.resources();mark=stream.mark()
            with Sampler(cg,p['sample_interval']) as samples:
                c0=cg.sample();start_chunky(server)
                event=stream.wait_task(mark,p['timeout_seconds']);c1=cg.sample()
            if event['count']!=256:raise RuntimeError(f'目标数不符：{event["count"]}')
            # c1 是当前完成事件之后的首个结束采样；保存窗口单列。
            server.rcon('save-all flush');c2=cg.sample()
            if profile:
                stats=json.loads(server.rcon('bench stats'));stages=json.loads(server.rcon('bench stages'))
                if not stages['timing'] or any(s['count']<=0 or s['cpu_ns']<=0 for s in stages['stages'].values()):
                    raise RuntimeError('阶段分析数据为空或计时开关错误')
                atomic_json(directory/'bench-stats.json',stats);atomic_json(directory/'bench-stages.json',stages)
                server.rcon('bench timing off')
            server.rcon('chunky pattern loop')
            cg.verify(cpus,cpuset)
            resources1=cg.resources()
            write_csv(directory/'cpu_samples.csv',sorted([dict(kind='start',**c0),*[dict(kind='sample',**r) for r in samples.rows],dict(kind='end',**c1),dict(kind='durable',**c2)],key=lambda row:row['time_ns']))
            atomic_json(directory/'resource_samples.json',dict(before=resources0,after=resources1))
            result=metrics(c0['time_ns'],c1['time_ns'],c0['usage_usec'],c1['usage_usec'],256,cpus)
            durable=metrics(c0['time_ns'],c2['time_ns'],c0['usage_usec'],c2['usage_usec'],256,cpus)
            uncertainty=(c0['read_end_ns']-c0['read_start_ns']+c1['read_end_ns']-c1['read_start_ns']+event['notification_latency_ns'])/1e6
            if uncertainty/(result['wall_s']*1000)>p['uncertainty_fraction']:raise RuntimeError(f'计时边界误差过大：{uncertainty:.2f} ms')
            atomic_json(directory/'completion.json',event)
            world.finish();stream.close();stream=None
            snapshot=server._copy_world(directory/'snapshot')
            meta=read_world_metadata(snapshot)
            if (meta.seed,meta.data_version,meta.level_name)!=(p['seed'],3955,world.level):raise RuntimeError('落盘世界身份不符')
            hashes=[]
            for x,z in area.positions():
                chunk=read_chunk(snapshot,x,z)
                if chunk is None or chunk.status!='minecraft:full':raise RuntimeError(f'目标 chunk 不完整：{x},{z}')
                hashes.append(dict(cx=x,cz=z,status=chunk.status,data_version=chunk.data_version,
                                   hash_schema=HASH_SCHEMA,block_hash=chunk.block_hash(),column_hashes=chunk.column_hashes()))
            with (directory/'hashes.jsonl').open('w') as out:
                for row in hashes:out.write(json.dumps(row)+'\n')
            result.update(campaign_id=directory.parents[1].name,protocol_id=p['protocol_id'],run_id=directory.parent.name,
                attempt_id=directory.name,area_id=area.id,repeat=repeat,order_index=order_index,seed=p['seed'],dimension=p['dimension'],
                cpu_count=cpus,cpuset=cpuset,world_name=world.level,min_cx=area.min_cx,min_cz=area.min_cz,max_cx=area.max_cx,max_cz=area.max_cz,
                target_chunks=256,preexisting_target_chunks=0,verified_full_chunks=256,cpu_usage_start_usec=c0['usage_usec'],
                cpu_usage_end_usec=c1['usage_usec'],durable_wall_s=durable['wall_s'],durable_cpu_ms_per_chunk=durable['cpu_ms_per_chunk'],
                chunky_elapsed_s=event['elapsed_s'],nr_throttled_delta=c1.get('nr_throttled',0)-c0.get('nr_throttled',0),
                throttled_usec_delta=c1.get('throttled_usec',0)-c0.get('throttled_usec',0),memory_peak_bytes=resources1['memory.peak'],
                timing_uncertainty_ms=uncertainty,status='passed',invalid_reason='',manifest_path=str(directory/'manifest.json'),
                warmup_chunks=n_warm,profile=profile)
            atomic_json(directory/'result.json',result)
            manifest.update(status='passed',cleanup_completed=False,finished_utc=datetime.now(timezone.utc).isoformat())
            sealed=seal(directory,manifest)
            world.cleanup()
            # 已归档任务不再被当前世界使用；停服后只删除自己的引用和输入。
            if csv_path and csv_path.exists():
                from .server import TEST_DATA
                task=TEST_DATA/'config/chunky/tasks/minecraft/overworld.properties'
                if task.exists() and csv_path.stem in task.read_text():task.unlink()
                csv_path.unlink()
            sealed['cleanup_completed']=True
            atomic_json(directory/'manifest.json',sealed)
            print(f'{area.id} / {cpus} 核 / {repeat}: {result["chunks_per_s"]:.2f} chunk/s，{result["cpu_ms_per_chunk"]:.2f} CPU ms/chunk',flush=True)
            return result
    except BaseException as exc:
        manifest.update(status='interrupted' if isinstance(exc,(KeyboardInterrupt,SystemExit)) else 'failed',error=str(exc))
        if world and not world.finished:
            try:world.finish()
            except Exception as end_error:manifest['cleanup_error']=str(end_error)
        if stream:
            try:stream.close()
            except Exception as stream_error:manifest['stream_error']=str(stream_error)
        atomic_json(directory/'manifest.json',manifest)
        raise

def attempt(campaign,slot,area,cpus,repeat,p,order=0,profile=False,warmup=None,resume=False):
    slot_dir=campaign/slot
    previous=sorted(slot_dir.glob('attempt-*')) if slot_dir.exists() else []
    for old in previous:
        manifest=json.loads((old/'manifest.json').read_text())
        if manifest['status']=='passed':
            verify(old,p['protocol_id'])
            return json.loads((old/'result.json').read_text())
    if previous:
        if not resume:raise RuntimeError('槽位已有失败记录；必须显式 resume 并登记重跑原因')
        atomic_json(slot_dir/'retry.json',dict(reason='显式恢复：从新世界重跑未成功槽位',previous=[str(x) for x in previous]))
        for old in previous:
            path=old/'manifest.json';m=json.loads(path.read_text())
            if m['status']=='running':m.update(status='interrupted',error='恢复时发现未完成尝试');atomic_json(path,m)
    directory=slot_dir/f'attempt-{len(previous)+1:03}'
    return one_run(directory,area,cpus,repeat,p,order,profile,warmup)

def load_protocol(path,areas_path,env):
    p=json.loads(Path(path).read_text())
    if protocol_id(p)!=p['protocol_id']:raise ValueError('冻结协议摘要错误')
    if p['environment']!=env or p['areas_digest']!=digest(areas_path):raise ValueError('代码、环境或区域已改变，需重新 pilot')
    if not p.get('pilot_passed'):raise ValueError('尚未通过 pilot 校准')
    return p

def execute(action,areas_path,protocol_path,resume_id=None):
    env=preflight();areas={a.id:a for a in load_areas(areas_path)}
    RUNS.mkdir(parents=True,exist_ok=True)
    if resume_id:
        from .models import validate_campaign_id
        validate_campaign_id(resume_id)
    if action=='pilot':
        campaign=RUNS/('pilot-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S'))
        campaign.mkdir()
        warm=256
        # 门槛预先声明为 5%，校准记录全部保留，不能替换正式样本。
        for calibration in range(4):
            p=freeze(campaign/f'protocol-{warm}.json',protocol(areas_path,env,warm))
            rows=[]
            for area in areas.values():
                for cpus in (1,2,4):
                    rows.append(attempt(campaign,f'w{warm}-{area.id}-c{cpus}',area,cpus,1,p))
            controls=[]
            for area in areas.values():
                controls.append(attempt(campaign,f'w{warm*2}-{area.id}-control',area,4,1,p,warmup=warm*2))
            comparison=[]
            for c in controls:
                base=next(row for row in rows if row['area_id']==c['area_id'] and row['cpu_count']==4)
                comparison.append(dict(area_id=c['area_id'],relative_change=c['chunks_per_s']/base['chunks_per_s']-1))
            atomic_json(campaign/f'warmup-{warm}.json',comparison)
            write_csv(campaign/f'pilot-{warm}.csv',rows+controls)
            if all(abs(x['relative_change'])<=.05 for x in comparison):
                p=protocol(areas_path,env,warm)
                p.update(pilot_passed=True,pilot_campaign=campaign.name,warmup_control_threshold=.05)
                p=freeze(protocol_path,p)
                atomic_json(campaign/'result.json',dict(status='passed',protocol_id=p['protocol_id'],warmup_chunks=warm))
                print('pilot 通过；冻结协议 '+p['protocol_id'],flush=True)
                return campaign
            warm*=2
        raise RuntimeError('预热量对照未收敛；保留校准结果并排查环境，不开始正式矩阵')
    p=load_protocol(protocol_path,areas_path,env)
    if action=='profile':
        if not resume_id:raise ValueError('profile 必须指定 --baseline')
        baseline=RUNS/resume_id
        state=json.loads((baseline/'result.json').read_text())
        if state['status'] not in ('matrix_passed','passed') or state['protocol_id']!=p['protocol_id']:
            raise ValueError('baseline 未通过或协议不匹配')
        profile_p={**p,'timing_enabled':True,'parent_protocol_id':p['protocol_id']}
        profile_p['protocol_id']=protocol_id(profile_p)
        campaign=baseline/'profile';campaign.mkdir(exist_ok=True)
        atomic_json(campaign/'protocol.json',profile_p)
        rows=[];stages=[]
        for area in areas.values():
            for cpu in (1,2,4):
                r=attempt(campaign,f'{area.id}-c{cpu}',area,cpu,1,profile_p,profile=True,resume=True)
                rows.append(r)
                path=Path(r['manifest_path']).parent
                data=json.loads((path/'bench-stages.json').read_text())
                total=sum(s['cpu_ns'] for s in data['stages'].values())
                for stage,values in data['stages'].items():
                    stages.append(dict(baseline_campaign_id=resume_id,profile_protocol_id=profile_p['protocol_id'],
                        area_id=area.id,cpu_count=cpu,run_id=r['run_id'],stage=stage,**values,
                        cpu_ms=values['cpu_ns']/1e6,wall_ms=values['wall_ns']/1e6,cpu_share=values['cpu_ns']/total,
                        measured_stage_cpu_to_container=total/1e6/r['cpu_ms']))
        write_csv(campaign/'stages.csv',stages);write_csv(campaign/'runs.csv',rows)
        atomic_json(campaign/'result.json',dict(status='passed',parent_protocol_id=p['protocol_id'],protocol_id=profile_p['protocol_id'],runs=9))
        return baseline
    campaign=RUNS/(resume_id or ('baseline-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')))
    if campaign.exists():
        old=json.loads((campaign/'protocol.json').read_text())
        if old!=p:raise ValueError('恢复 campaign 的协议不符')
    else:
        campaign.mkdir();atomic_json(campaign/'protocol.json',p)
        shutil.copy2(areas_path,campaign/'areas.json')
        atomic_json(campaign/'matrix.json',matrix(p['matrix_seed']))
    rows=[]
    try:
        for index,(kind,cpu,repeat) in enumerate(matrix(p['matrix_seed'])):
            rows.append(attempt(campaign,f'{kind}-c{cpu}-r{repeat}',areas[kind],cpu,repeat,p,index,resume=bool(resume_id)))
            write_csv(campaign/'layer1.csv',rows)
        from .statistics import summarize
        summaries=[]
        for kind in areas:
            for cpu in (1,2,4):
                subset=[r for r in rows if r['area_id']==kind and r['cpu_count']==cpu]
                s=summarize([r['chunks_per_s'] for r in subset])
                s.update(area_id=kind,cpu_count=cpu,cpu_ms_per_chunk_mean=statistics.mean(r['cpu_ms_per_chunk'] for r in subset),
                         cpu_ms_per_chunk_median=statistics.median(r['cpu_ms_per_chunk'] for r in subset),
                         cpu_ms_per_chunk_std=statistics.stdev(r['cpu_ms_per_chunk'] for r in subset))
                summaries.append(s)
        write_csv(campaign/'layer1-summary.csv',summaries)
        if any(s['cv']>=p['cv_threshold'] for s in summaries):raise RuntimeError('正式矩阵 CV 不达标；保留全部数据，不发布本次结果')
        atomic_json(campaign/'result.json',dict(status='matrix_passed',protocol_id=p['protocol_id'],runs=45))
        execute('profile',areas_path,protocol_path,campaign.name)
        from analysis.summarize_layer1 import summarize_campaign
        summarize_campaign(campaign,publish=True)
        return campaign
    except BaseException as exc:
        atomic_json(campaign/'result.json',dict(status='failed',protocol_id=p['protocol_id'],error=str(exc),completed=len(rows)))
        raise
