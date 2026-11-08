"""离线重算统计、27 对比较和静态图；不访问 Docker。"""
from collections import Counter
import csv
import json
from pathlib import Path
import shutil
import sys
from analysis.mca import read_chunk,read_world_metadata,HASH_SCHEMA
from bench.archive import verify
from bench.determinism import compare_chunk
from bench.models import ROOT,atomic_json,digest,load_areas,matrix
from bench.statistics import summarize


def read_csv(path):
    with Path(path).open() as stream:return list(csv.DictReader(stream))


def export(path,rows):
    from bench.layer1 import write_csv
    write_csv(path,rows)


def summarize_campaign(campaign,publish=False):
    campaign=Path(campaign)
    p=json.loads((campaign/'protocol.json').read_text())
    areas={a.id:a for a in load_areas(campaign/'areas.json')}
    rows=read_csv(campaign/'layer1.csv')
    by_slot={(r['area_id'],int(r['cpu_count']),int(r['repeat'])):r for r in rows}
    if len(rows)!=45 or set(by_slot)!=set(matrix(p['matrix_seed'])):raise ValueError('正式槽位缺失或重复')
    summaries=[];hashes={};directories={}
    for slot,row in by_slot.items():
        directory=campaign/row['run_id']/row['attempt_id']
        verify(directory,p['protocol_id'])
        sealed_result=json.loads((directory/'result.json').read_text())
        if any(row.get(k)!=('' if v is None else str(v)) for k,v in sealed_result.items()):
            raise ValueError('CSV 与已封存 result.json 不一致')
        meta=read_world_metadata(directory/'snapshot')
        if meta.seed!=p['seed'] or meta.data_version!=3955:raise ValueError('比较快照 seed/版本错误')
        h={}
        for line in (directory/'hashes.jsonl').read_text().splitlines():
            c=json.loads(line)
            if c['status']!='minecraft:full' or c['hash_schema']!=HASH_SCHEMA or len(c['column_hashes'])!=256:
                raise ValueError('hash 数据不完整')
            key=c['cx'],c['cz']
            if key in h:raise ValueError('重复 chunk hash')
            h[key]=c
        if set(h)!=set(areas[slot[0]].positions()):raise ValueError('hash 目标坐标不符')
        hashes[slot]=h;directories[slot]=directory
    for kind in areas:
        base=None
        for cpu in (1,2,4):
            group=[by_slot[k] for k in by_slot if k[:2]==(kind,cpu)]
            throughput=summarize([float(r['chunks_per_s']) for r in group])
            cost=summarize([float(r['cpu_ms_per_chunk']) for r in group])
            if cpu==1:base=throughput['mean']
            summaries.append(dict(area_id=kind,cpu_count=cpu,**throughput,speedup=throughput['mean']/base,
                                  cpu_ms_per_chunk_mean=cost['mean'],cpu_ms_per_chunk_median=cost['median'],cpu_ms_per_chunk_std=cost['std']))
    export(campaign/'layer1-summary.csv',summaries)
    differences=[];block_types=Counter();y_levels=Counter();boundary=Counter()
    details_path=campaign/'block-differences.jsonl'
    with details_path.open('w') as details_out:
        for kind,area in areas.items():
            pairings=[('repeat_1cpu',(kind,1,a),(kind,1,b)) for a,b in ((1,2),(3,4))]
            pairings += [('repeat_4cpu',(kind,4,a),(kind,4,b)) for a,b in ((1,2),(3,4))]
            pairings += [('cross_1_4cpu',(kind,1,r),(kind,4,r)) for r in range(1,6)]
            for group,left,right in pairings:
                tiers=Counter();ncols=0;nblocks=0
                for pos in area.positions():
                    aa,bb=hashes[left][pos],hashes[right][pos]
                    cols=sum(a!=b for a,b in zip(aa['column_hashes'],bb['column_hashes']))
                    if not cols:
                        if aa['block_hash']!=bb['block_hash']:raise ValueError('整块 hash 与列 hash 矛盾')
                        tiers['same']+=1;continue
                    result,details=compare_chunk(read_chunk(directories[left]/'snapshot',*pos),read_chunk(directories[right]/'snapshot',*pos))
                    tiers[result['tier']]+=1;ncols+=result['different_columns'];nblocks+=result['different_blocks']
                    for detail in details:
                        detail.update(run_a=by_slot[left]['run_id'],run_b=by_slot[right]['run_id'],area_id=kind,comparison_group=group)
                        details_out.write(json.dumps(detail,ensure_ascii=False,sort_keys=True)+'\n')
                        block_types[kind,detail['before'][0],detail['after'][0]]+=1
                        y_levels[kind,detail['y']]+=1
                        distance=min(detail['x']-area.min_cx*16,(area.max_cx+1)*16-1-detail['x'],
                                     detail['z']-area.min_cz*16,(area.max_cz+1)*16-1-detail['z'])
                        boundary[kind,distance]+=1
                differences.append(dict(area_id=kind,comparison_group=group,run_a=by_slot[left]['run_id'],run_b=by_slot[right]['run_id'],
                    same=tiers['same'],few=tiers['few'],many=tiers['many'],different_columns=ncols,different_blocks=nblocks,
                    chunk_diff_rate=(256-tiers['same'])/256,column_diff_rate=ncols/(256*256),block_diff_rate=nblocks/(256*16*16*384)))
    export(campaign/'determinism.csv',differences)
    atomic_json(campaign/'difference-summary.json',dict(
        block_types=[dict(area_id=k[0],before=k[1],after=k[2],count=v) for k,v in sorted(block_types.items())],
        y=[dict(area_id=k[0],y=k[1],count=v) for k,v in sorted(y_levels.items())],
        boundary=[dict(area_id=k[0],distance=k[1],count=v) for k,v in sorted(boundary.items())]))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(1,2,figsize=(11,4))
    for kind in areas:
        group=[s for s in summaries if s['area_id']==kind]
        axes[0].errorbar([s['cpu_count'] for s in group],[s['mean'] for s in group],yerr=[s['std'] for s in group],marker='o',label=kind)
        axes[1].errorbar([s['cpu_count'] for s in group],[s['cpu_ms_per_chunk_mean'] for s in group],yerr=[s['cpu_ms_per_chunk_std'] for s in group],marker='o',label=kind)
    for ax,label in zip(axes,('Throughput (chunk/s)','Amortized CPU (ms/chunk)')):
        ax.set(xlabel='CPU quota / physical cores',ylabel=label,xticks=[1,2,4]);ax.legend();ax.grid(alpha=.3)
    fig.tight_layout();fig.savefig(campaign/'throughput-cpu.png',dpi=180);plt.close(fig)
    fig,ax=plt.subplots(figsize=(8,4))
    for i,tier in enumerate(('same','few','many')):
        bottom=[sum(sum(int(r[t]) for t in ('same','few','many')[:i]) for r in differences if r['area_id']==kind)/(9*256) for kind in areas]
        values=[sum(int(r[tier]) for r in differences if r['area_id']==kind)/(9*256) for kind in areas]
        ax.bar(list(areas),values,bottom=bottom,label=tier)
    ax.set(ylabel='Fraction of compared chunks (paired runs)',ylim=(0,1));ax.legend();fig.tight_layout()
    fig.savefig(campaign/'determinism.png',dpi=180);plt.close(fig)
    profile=campaign/'profile'
    profile_result=json.loads((profile/'result.json').read_text())
    if profile_result['status']!='passed' or profile_result['runs']!=9 or profile_result['parent_protocol_id']!=p['protocol_id']:
        raise ValueError('阶段分析缺失或协议错误')
    profiles=read_csv(profile/'runs.csv')
    if len(profiles)!=9 or len({(r['area_id'],r['cpu_count']) for r in profiles})!=9:raise ValueError('阶段分析槽位不完整')
    for row in profiles:verify(profile/row['run_id']/row['attempt_id'],profile_result['protocol_id'])
    shutil.copy2(profile/'stages.csv',campaign/'stages.csv')
    report=['# 第 1 步：原版生成基线','',f'Campaign：`{campaign.name}`；协议：`{p["protocol_id"]}`。',
        '', '硬件为 AMD EPYC 7542，使用同一物理核池的 1/2/4 核配额，容器 6g、堆 4G；这不是 N100 实测。',
        'Minecraft 1.21.1 / Java 21 / Fabric 0.16.14，固定工具模组和 bench mod。主矩阵阶段计时关闭，计数保留。',
        '', '| 区域 | 核数 | chunk/s 均值 | CV | CPU ms/chunk 均值 | 吞吐倍数 |','|---|---:|---:|---:|---:|---:|']
    for s in summaries:report.append(f'| {s["area_id"]} | {s["cpu_count"]} | {s["mean"]:.3f} | {s["cv"]:.2%} | {s["cpu_ms_per_chunk_mean"]:.3f} | {s["speedup"]:.3f} |')
    report+=['','45 轮独立新世界；每配置 n=5。误差条为 run 间样本标准差，CV 门槛为 10%。原始慢样本未剔除。',
        '','27 对预先固定的比较：每区域同核 1 CPU 两对、4 CPU 两对，跨 1/4 CPU 五对。三档为 0、1–4、5–256 列不同。',
        '仅比较完整高度 block states，不包含实体、方块实体、光照或 tick；配对复用部分世界，chunk 不视为独立重复。',
        '','| 区域 | 比较组 | 平均 chunk 差异率 | 平均列差异率 |','|---|---|---:|---:|']
    for kind in areas:
        for group in ('repeat_1cpu','repeat_4cpu','cross_1_4cpu'):
            rr=[r for r in differences if r['area_id']==kind and r['comparison_group']==group]
            report.append(f'| {kind} | {group} | {sum(r["chunk_diff_rate"] for r in rr)/len(rr):.3%} | {sum(r["column_diff_rate"] for r in rr)/len(rr):.3%} |')
    report+=['','阶段分析另做 9 轮 timing on，仅单次描述性分析。stages.csv 的 cpu_share 分母为已记录阶段 CPU 合计；wall_ns 为多次调用累计，light_engine.count 为批次数。',
        '','X 的候选初始目标为 2 个 donor 时 p95 就绪延迟改善 2 倍，尚待第 4/6 步验证。采用该目标的条件是 donor 有效吞吐覆盖探索需求，且通信、排队、验证和写入开销没有耗尽收益；本次吞吐倍数不等于已测得的 X。Y/Z 仍待测。',
        '', '运行归档保留协议、命令、日志、CPU 原始采样、完整快照、hash 和逐方块差异。离线重算不会访问 Docker。']
    (campaign/'baseline.md').write_text('\n'.join(report)+'\n')
    passed=all(s['cv']<p['cv_threshold'] for s in summaries)
    atomic_json(campaign/'result.json',dict(status='passed' if passed else 'failed',protocol_id=p['protocol_id'],runs=45,comparisons=27,profile_runs=9))
    if not passed:raise ValueError('CV 不达标；结果只保留在本 campaign')
    if publish:
        out=ROOT/'analysis/results';out.mkdir(exist_ok=True)
        names=('layer1.csv','layer1-summary.csv','determinism.csv','stages.csv','baseline.md','throughput-cpu.png','determinism.png')
        for name in names:
            tmp=out/(name+'.tmp');shutil.copy2(campaign/name,tmp);tmp.replace(out/name)
        atomic_json(out/'latest.json',dict(campaign_id=campaign.name,protocol_id=p['protocol_id'],artifacts={name:digest(out/name) for name in names}))
    return summaries,differences

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('--campaign',required=True);args=parser.parse_args()
    from bench.models import validate_campaign_id
    validate_campaign_id(args.campaign)
    summarize_campaign(ROOT/'analysis/results/runs'/args.campaign)
