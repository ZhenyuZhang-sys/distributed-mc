"""按独立 run 统计，失败 campaign 也保留原始点。"""
from statistics import mean,stdev,median

def summarize(values,minimum=5):
    """正式矩阵每配置 5 轮；粗测传 minimum=2。"""
    if minimum<2 or len(values)<minimum or any(v<=0 for v in values):raise ValueError(f'需要至少 {max(minimum,2)} 个有效正数样本')
    avg=mean(values);sd=stdev(values)
    return dict(n=len(values),mean=avg,median=median(values),std=sd,cv=sd/avg,
                relative_range=(max(values)-min(values))/avg,min=min(values),max=max(values))
