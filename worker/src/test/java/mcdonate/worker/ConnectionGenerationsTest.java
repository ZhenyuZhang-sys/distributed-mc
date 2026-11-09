package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 11-09 codex-audit A1：一次领了 A/B/C/D，断线时只取消了正在做的那个；重连以后旧任务的结果会经新连接（新编号）发回去，
 * coordinator 重启后任务编号又从 1 开始，旧结果甚至能撞上别的 tile 的新任务。
 * 每个任务记下是哪一次连接收到的；断线时这一代的任务全部作废，结果不发。
 */
class ConnectionGenerationsTest {
    @Test
    void tasksFromOldConnectionAreStale() {
        ConnectionGenerations g = new ConnectionGenerations();
        g.connected();
        g.received(1);
        g.received(2);
        assertTrue(g.isCurrent(1));
        List<Long> dropped = g.disconnected();
        assertEquals(List.of(1L, 2L), dropped, "断线：这一代的任务全部作废");
        g.connected();
        g.received(1);                 // coordinator 重启后编号又从 1 开始：是新连接的任务
        assertTrue(g.isCurrent(1));
        assertFalse(g.isCurrent(2), "旧连接的任务不能经新连接发结果");
    }

    @Test
    void doneTasksForgotten() {
        ConnectionGenerations g = new ConnectionGenerations();
        g.connected();
        g.received(7);
        g.finished(7);
        assertEquals(List.of(), g.disconnected());
    }

    @Test
    void generationOfTaskIsWhereItWasReceived() {
        // codex-audit G2：发送时按任务收到时的那一代比，不按发送那一刻的
        ConnectionGenerations g = new ConnectionGenerations();
        g.connected();
        g.received(5);
        long gen = g.generationOf(5);
        g.disconnected();
        g.connected();
        assertTrue(g.current() != gen);
        assertEquals(-1, g.generationOf(99));
    }
}
