"""只比较完整 block states；差异定位按列读取。"""
from analysis.mca import HASH_SCHEMA

def compare_chunk(a,b):
    if a is None or b is None: raise ValueError('缺失 chunk')
    if (a.cx,a.cz,a.data_version,a.y_min,a.y_max_exclusive)!=(b.cx,b.cz,b.data_version,b.y_min,b.y_max_exclusive):
        raise ValueError('chunk 坐标、版本或高度不一致')
    columns=[i for i,(x,y) in enumerate(zip(a.column_hashes(),b.column_hashes())) if x!=y]
    details=[]
    for i in columns:
        x,z=i%16,i//16
        for y in range(a.y_min,a.y_max_exclusive):
            aa,bb=a.block_state(x,y,z),b.block_state(x,y,z)
            if aa!=bb:
                details.append(dict(cx=a.cx,cz=a.cz,local_x=x,local_z=z,x=a.cx*16+x,y=y,z=a.cz*16+z,
                                    before=[aa.name,aa.properties],after=[bb.name,bb.properties]))
    return dict(cx=a.cx,cz=a.cz,different_columns=len(columns),different_blocks=len(details),
                tier='same' if not columns else 'few' if len(columns)<=4 else 'many',hash_schema=HASH_SCHEMA),details
