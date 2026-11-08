"""兼容旧测试导入，所有公共能力来自 bench.server。"""
import sys
from bench import server as _server
sys.modules[__name__] = _server
