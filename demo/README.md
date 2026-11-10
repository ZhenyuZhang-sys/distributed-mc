演示服务器用的脚本和未合进 main 的客户端补丁（黄框走隐藏聊天消息，要进 main 需改 payload）。不含 jar 和真实 env：`cp env.example .env` 后自己填 Tailscale 地址，读过 EULA 再把 EULA 改成 TRUE。`demo.sh fly` 调的 `fly.py` 没放进来，用 `benchfly.py`。

# 演示服务器交接（11-10，distributed-mc-75）

演示服务器：/home/zhenyu/mc-demo（compose 项目 mc-demo，cpuset 104-105，1 核 3G，视距 6，只绑 <你的 Tailscale IP>:25590/25591）。
coordinator 和 bench 都是 2736827 加下面的 bench 补丁自己构建的（common 版本 0.1.0-b2736827.dirty，两者一致）。

## 补丁（都没提交，在 scratch worktree 里）
- client-demo-overlay-e4413da.patch（基于 e4413da）：
  - 小地图探索模式（只画客户端加载过的 chunk；/mcdonate map explore|all）——main 的 v2.3 已有自己的版本，这份只做参考
  - 黄框：服务器发隐藏聊天消息 "[demo-prep] x0 z0 x1 z1"（chunk 坐标）画预算区域（kexu 收进 main 时改走 payload）
  - 别的玩家的箭头（按名字固定颜色，不标名字）
  - 只有一个 donor 时 RESUMED 用它的颜色（kexu bcd6426 服务器端修好后可删）
  - 界面文字改英文、MAP_PX 128→84、图例右对齐
- bench-lockyaw-2736827.patch：-Dmcdonate.benchLockYaw / benchLockPitch 锁飞行视角

## 脚本
- bot.py：聊天命令 !server !donor !warm !live !fly !group !duo !ab !reset !stats !last（说明见 !help）
- benchfly.py：/bench fly 包装（注意 tp 的朝向写 270，不能写 -90：rcon-cli 会把 -90 当成参数）
- demo.sh：up/down/reset/stats/bot/bot-stop
- !duo：多 donor 按 x 大段轮流 /donor assign，按 lanes/avgMs 加权，最少 2/3 平均份额；起点 VIEW+16 chunk 只给一个 donor 预算

## 关键数据（都是修好 tp 以后的）
- 1 核视距 6、20 格/秒：只服务器 void 74%、卡 24 s；!donor 预算 1.6%；!warm 3%～7.5%
- 两台（M5 + Ryzen 9）：!group 37%（接缝多）；!duo 21% → 14%（0 丢弃、vanilla 0）
