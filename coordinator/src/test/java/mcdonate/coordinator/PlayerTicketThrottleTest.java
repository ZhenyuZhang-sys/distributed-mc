package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 11-09 放宽玩家票节流（playerTicketThrottle）：原版 DistanceManager 的 ticketThrottler 同时最多 4 张玩家票在途，
 * 每张等 chunk 能 entity tick（5×5 都 FULL）才放名额。烟花直飞每秒约 44 个新 chunk 进视野，4 个名额不够，
 * donor 早就导入好的 chunk 也要排几秒队（codex-server：进视距→票够 p50 3.5 秒，拿到票到 FULL 只要 0.15 秒）。
 */
class PlayerTicketThrottleTest {
    @Test
    void unsetKeepsVanilla() {
        assertEquals(4, PlayerTicketThrottle.maxTasks(4, null), "没配：原版的值不动");
    }

    @Test
    void configuredReplacesVanilla() {
        assertEquals(16, PlayerTicketThrottle.maxTasks(4, "16"));
        assertEquals(8, PlayerTicketThrottle.maxTasks(4, " 8 "));
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> PlayerTicketThrottle.maxTasks(4, "0"), "0 个名额玩家票永远加不上");
        assertThrows(IllegalArgumentException.class, () -> PlayerTicketThrottle.maxTasks(4, "abc"));
    }
}
