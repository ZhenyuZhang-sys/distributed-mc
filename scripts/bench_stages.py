#!/usr/bin/env python3
"""把测试服务器上 /bench stages 的结果写成 CSV，用来算各生成阶段的 CPU 占比。

用法：scripts/bench_stages.py [输出文件，默认 analysis/results/stages.csv]

前提：测试服务器在跑，生成前已经 `scripts/rcon.sh bench reset` 和 `bench timing on`。
列：stage, count, cpu_ms, wall_ms, max_wall_ms, cpu_share（占各阶段 CPU 合计的比例）。
light_engine 的 count 是光照线程的批次数，不是 chunk 数。
"""

import csv
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "analysis" / "results" / "stages.csv"
    raw = subprocess.run([str(REPO / "scripts" / "rcon.sh"), "bench", "stages"],
                         capture_output=True, text=True, check=True).stdout
    data = json.loads(raw)
    if not data["timing"]:
        print("注意：计时现在是关的，结果可能是空的。先 scripts/rcon.sh bench timing on", file=sys.stderr)
    stages = data["stages"]
    total = sum(s["cpu_ns"] for s in stages.values()) or 1
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["stage", "count", "cpu_ms", "wall_ms", "max_wall_ms", "cpu_share"])
        for name, s in stages.items():
            w.writerow([name, s["count"], f"{s['cpu_ns'] / 1e6:.3f}", f"{s['wall_ns'] / 1e6:.3f}",
                        f"{s['max_wall_ns'] / 1e6:.3f}", f"{s['cpu_ns'] / total:.4f}"])
    print(f"已写入 {out}")


if __name__ == "__main__":
    main()
