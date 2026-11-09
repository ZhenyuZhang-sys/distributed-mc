"""11-09 放宽玩家票节流（playerTicketThrottle）：mixin 只改原版 DistanceManager 里 ticketThrottler 的 maxTasks（默认 4 不动）。
/donor status 读回每个维度实际的 maxTasks。开着的对照：MCD_JVM_EXTRA=-Dmcdonate.playerTicketThrottle=16。
"""
import json
import os
import re


def test_status_reads_back_actual_max_tasks(server):
    t = json.loads(server.rcon("donor status"))["playerTicketThrottle"]
    m = re.search(r"-Dmcdonate\.playerTicketThrottle=(\d+)", os.environ.get("MCD_JVM_EXTRA", ""))
    want = int(m.group(1)) if m else 4
    assert t["configured"] == want, t
    assert t["actual"]["minecraft:overworld"] == want, f"实际生效的 maxTasks 不对：{t}"
    assert set(t["actual"].values()) == {want}, f"每个维度都一样：{t}"
