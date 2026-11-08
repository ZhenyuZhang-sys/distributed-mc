# 测试方法

## 怎么跑

```bash
scripts/test.sh step0                  # 跑第 0 步
scripts/test.sh step2                  # 跑第 0、1、2 步
scripts/test.sh step2 --slow           # 连性能测试一起跑
scripts/test.sh step0 -k carpet        # 只跑名字里有 carpet 的（排查用）
MCD_KEEP_SERVER=1 scripts/test.sh step0  # 跑完不关服务器，方便看日志
```

退出码 0 = 通过，其他 = 失败。**有测试被跳过也算失败**，因为那说明这一步还没做完；排查时可以加 `--allow-skip`，但验收不能加。

## 测试放在哪

```
tests/
  conftest.py         公共设置：server fixture、日志检查、跳过算失败
  mcd.py              工具函数：rcon、读日志、重启、等待
  log_whitelist.txt   已知无害的日志行（正则，每行一个，要写原因）
  step0/              第 0 步的测试
  step1/ ...          每一步一个目录
```

- 文件名用 `test_NN_xxx.py`，按编号顺序跑。要放在最后的（比如重启）用大编号。
- 单元测试（Gradle + JUnit）放在各模块的 `src/test/`，由 `test.sh` 调 Gradle 跑（第 2 步接上）。

## 一轮测试怎么进行

1. `test.sh` 拿重活锁，检查平时玩的服务器已停、EULA 已同意、存档目录确实是 `test-data`。
2. 第一个用到 `server` 的测试触发 `new_world.sh t<时间戳> 424242`：删旧测试世界，起一个全新的，等 healthy。
3. 测试通过 `server.rcon(...)` 发命令，通过 `server.logs()` 看日志，通过 `server.world_dir` 读存档。
4. 全部跑完，检查这一轮的服务器日志：有 `/ERROR]` 或异常堆栈，而且不在白名单里，就判失败。
5. `test.sh` 退出时 `down` 测试服务器。

## 写测试的规矩

- **先写测试再写功能**。新测试第一次跑应该失败。
- **判对错靠存档和命令输出**，不靠看游戏画面。chunk 对不对用 `analysis/mca/` 读 `.mca`，和同一个 seed 的对照世界比。
- **等待用 `wait_until`**，给一个超时，不要写死 `sleep`。
- **每轮都用全新世界、同一个 seed**（默认 424242）。不在旧世界上重复测。
- **性能测试**加 `@pytest.mark.slow`，不进日常回归。
- 还做不了的检查写成 `pytest.skip("等 xxx：要检查什么")`，不要删掉，也不要写成永远通过。
- 改完代码，**之前所有步骤的测试都要重跑**。
