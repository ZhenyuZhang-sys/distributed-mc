"""日志检查：白名单放过的异常，紧跟的堆栈行也一起放过。不需要服务器。

Carpet 假玩家上线时服务器会去 Mojang 查玩家资料，网络慢就超时，带一整段堆栈。
离线模式也挡不住（请求来自 SkullBlockEntity）。
"""

from mcd import check_log_text

TIMEOUT = """[04:39:49] [Worker-Main-3/WARN]: Couldn't look up profile properties for 336161bf-ada0-3ce1-ae37-9abdc60036bb
com.mojang.authlib.exceptions.MinecraftClientException: Failed to read from https://sessionserver.mojang.com/session/minecraft/profile/1022bec81e4146688014708e819ca690?unsigned=false due to Read timed out
\tat knot/com.mojang.authlib.minecraft.client.MinecraftClient.readInputStream(MinecraftClient.java:108) ~[authlib-6.0.54.jar:?]
\tat java.base/java.util.concurrent.ForkJoinWorkerThread.run(Unknown Source) [?:?]
Caused by: java.net.SocketTimeoutException: Read timed out
\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(Unknown Source) ~[?:?]
\t... 12 more
[03:12:02] [Server thread/INFO]: Done"""


def test_whitelisted_exception_with_stack_is_allowed():
    assert check_log_text(TIMEOUT) == []


def test_stack_after_other_exception_still_reported():
    text = "java.lang.IllegalStateException: broken\n\tat com.example.A.run(A.java:1)"
    assert len(check_log_text(text)) == 2


def test_block_ends_at_normal_line():
    # 白名单异常之后，正常行结束这个块；再出现的堆栈要报
    text = TIMEOUT + "\n\tat com.example.Orphan.run(Orphan.java:3)"
    assert check_log_text(text) == ["\tat com.example.Orphan.run(Orphan.java:3)"]
