"""test_03_multi 里"什么时候 kill worker2"的判断（纯逻辑，不起服务器）。

11-09 kexu 完整回归：12 个 tile 全由一个 donor 做完，"两个 donor 都干过活"失败。原因：/donor status 的 done 是这条连接
累计的（前面的测试里 worker2 已经做过），只看 done >= 1 时这一条测试里 worker2 可能一个都没做，等待立刻就满足了。
"""

from step6.test_03_multi import busy_after_one


def ws(done2, task2):
    return {1: {"id": 1, "done": 5, "task": 7}, 2: {"id": 2, "done": done2, "task": task2}}


def test_done_from_earlier_tests_does_not_count():
    # worker2 在前面的测试里做过 3 个，这条测试里还一个没做、手上有任务：不能 kill
    assert not busy_after_one(ws(3, 9), 1, 2, done0=3)


def test_one_done_here_and_busy():
    assert busy_after_one(ws(4, 9), 1, 2, done0=3)


def test_idle_after_one_is_not_mid_task():
    assert not busy_after_one(ws(4, -1), 1, 2, done0=3)
