"""按独立 run 统计，失败 campaign 也保留原始点。"""
from statistics import mean,stdev,median

def summarize(values):
    if len(values)!=5 or any(v<=0 for v in values):raise ValueError('需要五个有效正数样本')
    avg=mean(values);sd=stdev(values)
    return dict(n=5,mean=avg,median=median(values),std=sd,cv=sd/avg,
                relative_range=(max(values)-min(values))/avg,min=min(values),max=max(values))
