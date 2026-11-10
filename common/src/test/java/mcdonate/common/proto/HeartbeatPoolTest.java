package mcdonate.common.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * 11-10 kexu：测试的 docker worker 不走客户端，没有 -XX:ActiveProcessorCount，生成线程池按容器核数 − 1，
 * 和真客户端（池 = 设的线程数）不一样。worker 在心跳里报实际看到的核数和生成池大小，/donor status 显示，
 * 测量驱动每轮核对，防止哪个槽位漏改。旧 worker 不报：0（不知道）。
 */
class HeartbeatPoolTest {
    @Test
    void roundTrip() {
        Heartbeat h = new Heartbeat(2, 7, false, true, 6, 9, 8);
        byte[] f = Frame.encode(h);
        Heartbeat got = (Heartbeat) new FrameDecoder().feed(f, 0, f.length).get(0);
        assertEquals(h, got);
        assertEquals(9, got.cpus());
        assertEquals(8, got.genPool());
    }

    @Test
    void oldHeartbeatHasUnknownPool() {
        CompoundTag old = new CompoundTag();   // 11-09 的 worker：有 boost、threads，没有 cpus、genPool
        old.putString("type", "heartbeat");
        old.putLong("sentAtMs", 10);
        old.putLong("taskId", 3);
        old.putBoolean("paused", false);
        old.putBoolean("boost", true);
        old.putInt("threads", 3);
        Heartbeat h = (Heartbeat) MessageCodec.fromTag(old);
        assertEquals(0, h.cpus());
        assertEquals(0, h.genPool());
        assertEquals(new Heartbeat(10, 3, false, true, 3), h, "五个参数的构造：核数和生成池不知道");
    }
}
