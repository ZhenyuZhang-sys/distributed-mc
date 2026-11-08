"""第 1 步（kexu）的测试还没写。

scripts/test.sh 要求 tests/step0..N 每个目录都在。先占个位：跳过算失败，
所以第 1 步完成前，第 2 步及以后都不能正式验收。开发时可以加 --allow-skip。
kexu 写第 1 步的测试时，删掉这个文件。
"""

import pytest


def test_step1_pending():
    pytest.skip("等第 1 步（kexu）：基线测量的测试")
