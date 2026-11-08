# 第 0 步详细计划：服务器和测试环境

目标：一条命令起一台干净的测试服务器，自动确认各组件都正常，生成一小块地形再从存档里读出来。

和 `DEV_PLAN.md` 的一处不同：`new_world.sh` 不改平时玩的服务器，而是操作一台单独的测试服务器（compose 项目 `mc-test`，端口 25566，存档在 `docker/test-data/`）。这样测试从代码上就碰不到 `world`。

## 分工

| 谁 | 做什么 | 状态 |
| --- | --- | --- |
| fish | `scripts/` 下的脚本、测试服务器配置、pytest 骨架、第 0 步测试 | 已完成，13 个测试通过 |
| kexu | `analysis/mca/` 存档读取器和它的自测 | 未开始 |

## 小任务

- [x] 0.1 `docker/compose.yaml` 的存档目录和重启策略改成可配置（默认值不变）
- [x] 0.2 `scripts/lib.sh`：重活锁、检查平时的服务器已停、生成 `.env.test`、核对存档目录
- [x] 0.3 `scripts/rcon.sh`、`scripts/test_server.sh`
- [x] 0.4 `scripts/new_world.sh`
- [x] 0.5 `scripts/test.sh`、`pytest.ini`、`tests/conftest.py`、`tests/mcd.py`
- [x] 0.6 第 0 步测试（`tests/step0/`）
- [x] 0.7 同意 EULA，实跑 `scripts/test.sh step0 --allow-skip`：13 个通过，2 个等读取器
- [ ] 0.8 kexu 的读取器合进 `main` 后，补上下面标"等读取器"的两条，去掉 `pytest.skip`
- [ ] 0.9 `scripts/test.sh step0` 全过（不加 `--allow-skip`），合进 `main`

## 验收和对应的测试

| 验收 | 测试 |
| --- | --- |
| 容器 healthy；Fabric Loader 0.16.14、Fabric API、Carpet、Chunky、spark 都已加载 | `test_01_server.py::test_healthy`、`test_mod_loaded` |
| `seed` 返回 424242；`spark tps`、`chunky help` 有正常输出 | `test_01_server.py::test_seed`、`test_spark_tps`、`test_chunky_help` |
| 假玩家 bot1 能生成、能删掉 | `test_02_carpet.py` |
| Chunky 生成一块方形区域，读取器读到每个 chunk 都是 `minecraft:full` | `test_03_chunky.py`（生成部分已写；读取部分等读取器） |
| 读取器自测：同一个 chunk 读两次 hash 相同；不存在的 chunk 返回"不存在" | kexu 写在 `analysis/mca/` 的测试里 |
| 新世界里只有出生点附近的 chunk | `test_01_server.py::test_new_world_has_only_spawn_chunks`（等读取器） |
| 停服再启动，世界还在，日志里没有 ERROR | `test_09_restart.py` |
