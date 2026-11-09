package mcdonate.common.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** 11-09 加速模式：心跳里报 donor 愿不愿意做后台活（boost）和它的生成线程数（threads，定在途上限用）。 */
class HeartbeatBoostTest {
    @Test
    void roundTrip() {
        Heartbeat h = new Heartbeat(2, 7, false, true, 6);
        byte[] f = Frame.encode(h);
        Heartbeat got = (Heartbeat) new FrameDecoder().feed(f, 0, f.length).get(0);
        assertEquals(h, got);
        assertTrue(got.boost());
        assertEquals(6, got.threads());
    }

    @Test
    void defaultsOff() {
        assertFalse(new Heartbeat(1, 5).boost());
        assertEquals(0, new Heartbeat(1, 5, true).threads(), "不知道线程数是 0");
    }

    @Test
    void oldHeartbeatWithoutBoostIsOff() {
        CompoundTag old = new CompoundTag();   // 旧版本 worker：有 paused，没有 boost、threads
        old.putString("type", "heartbeat");
        old.putLong("sentAtMs", 10);
        old.putLong("taskId", 3);
        old.putBoolean("paused", true);
        Heartbeat h = (Heartbeat) MessageCodec.fromTag(old);
        assertEquals(new Heartbeat(10, 3, true), h);
        assertFalse(h.boost());
        assertEquals(0, h.threads());
    }
}
