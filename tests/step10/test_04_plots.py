"""第 10 步 10.4：画图只读汇总表。合成数据，不起服务器。"""
import shutil

from analysis.matrix_summary import write_tables
from analysis.plots import FIGURES, plot_all
from analysis.run_matrix import run, validate


def metrics(cell, out):
    p = cell['params']
    e = cell['experiment']
    if e == 'flight':
        d = p['donors']
        return dict(latency_p50_ms=2000 / (1 + d), latency_p95_ms=9000 / (1 + d) + cell['repeat'], latency_p99_ms=12000 / (1 + d),
                    void_mean=0.3 / (1 + d), void_p95=0.5 / (1 + d), mspt_p95=40.0 - d, mspt_max=48.0,
                    cpu_cores_mean=1.8 - 0.2 * d, ring_overhead=0.1 * p.get('tile_size', 4), hedge_wasted_tiles=3)
    if e == 'capacity':
        return dict(max_bots=1 + p['donors'] + (1 if p['features'] == 'all' else 0))
    return dict(detected=p['cheat'] != 'none', chunks_before_blacklist=40 if p['cheat'] != 'none' else None,
                false_positives=0, y=0.05 + p['check_rate'], verify_cpu_ms=100 * p['check_rate'])


def campaign(tmp_path, full=True):
    flight = [{'donors': [0, 1, 2, 4], 'speed': ['walk', 'elytra'], 'server_cpus': [2], 'features': ['v0', 'all'], 'duration_s': 60}]
    if full:
        flight += [{'donors': [2], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['all'], 'latency_ms': [50, 150], 'duration_s': 60},
                   {'donors': [2], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['all'], 'tile_size': [1, 2, 8], 'duration_s': 60},
                   {'donors': [2], 'speed': ['elytra'], 'server_cpus': [2], 'features': ['predict', 'hedge'], 'duration_s': 60}]
    exps = {'flight': flight, 'verify': [{'cheat': ['none', 'ore', 'flat'], 'check_rate': [0.02, 0.2], 'duration_s': 60}]}
    if full:
        exps['capacity'] = [{'donors': [2, 4], 'features': ['v0', 'all'], 'server_cpus': [2]}]
    cfg = validate({'name': 'p', 'seed': 1, 'repeats': 2, 'experiments': exps})
    run(cfg, {'flight': metrics, 'verify': metrics, 'capacity': metrics}, tmp_path)
    c = tmp_path / 'matrix-p'
    write_tables(c)
    return c


def test_eleven_figures_listed():
    assert len(FIGURES) == 11 and len(set(FIGURES)) == 11


def test_all_figures_from_tables_only(tmp_path):
    c = campaign(tmp_path)
    # 只留汇总表：删掉每轮的归档，画图照样能画（画图只读表）
    for d in c.iterdir():
        if d.is_dir():
            shutil.rmtree(d)
    status = plot_all(c, c / 'figures')
    assert set(status) == set(FIGURES)
    no_data = {k for k, v in status.items() if v != 'ok'}
    assert no_data == {'fig09_frametime'}, status          # 帧时间只能来自真实电脑
    for name in FIGURES:
        png = c / 'figures' / f'{name}.png'
        assert png.exists() and png.stat().st_size > 2000, name


def test_missing_experiments_give_no_data_not_errors(tmp_path):
    c = campaign(tmp_path, full=False)
    status = plot_all(c, c / 'figures')
    for name in ('fig03_capacity', 'fig05_tile_size', 'fig06_latency', 'fig09_frametime'):
        assert status[name] == '无数据', (name, status[name])
        assert (c / 'figures' / f'{name}.png').exists()
    assert status['fig01_void_latency_vs_donors'] == 'ok'


def test_empty_campaign(tmp_path):
    c = tmp_path / 'matrix-empty'
    c.mkdir()
    write_tables(c)
    status = plot_all(c, c / 'figures')
    assert status['fig10_baseline'] == 'ok'                 # 第 1 步的数据在仓库里
    assert all(v == '无数据' for k, v in status.items() if k != 'fig10_baseline')
