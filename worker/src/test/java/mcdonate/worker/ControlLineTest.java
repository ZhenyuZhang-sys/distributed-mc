package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 第 9 步：游戏（客户端 mod）和 worker 子进程之间的本地控制通道，一行一条（docs/step9-plan.md 2.1）。 */
class ControlLineTest {
    @Test
    void parsesCommands() {
        assertEquals(new ControlLine.Command(ControlLine.Kind.PAUSE, ""), ControlLine.parse("PAUSE"));
        assertEquals(new ControlLine.Command(ControlLine.Kind.RESUME, ""), ControlLine.parse(" resume "));
        assertEquals(new ControlLine.Command(ControlLine.Kind.STOP, ""), ControlLine.parse("STOP"));
        assertEquals(new ControlLine.Command(ControlLine.Kind.STATUS, ""), ControlLine.parse("STATUS"));
        assertEquals(new ControlLine.Command(ControlLine.Kind.TOKEN, "abc_DEF-1"), ControlLine.parse("TOKEN abc_DEF-1"));
    }

    @Test
    void rejectsGarbage() {
        assertNull(ControlLine.parse(""));
        assertNull(ControlLine.parse("TOKEN"));
        assertNull(ControlLine.parse("TOKEN a b"));
        assertNull(ControlLine.parse("RM -RF"));
    }

    @Test
    void outputLinesArePrefixedAndHideTokens() {
        // worker 的标准输出里还有 Minecraft 自己的日志：给客户端看的行都带固定前缀
        assertEquals("MCDONATE NEED_TOKEN", ControlLine.out("NEED_TOKEN"));
        assertTrue(ControlLine.isOut("MCDONATE STATUS {}"));
        assertFalse(ControlLine.isOut("[12:00:00] [Server thread/INFO]: Done"));
        assertEquals("STATUS {}", ControlLine.body("MCDONATE STATUS {}"));
        assertEquals("TOKEN ****", ControlLine.redact("TOKEN secret"));
    }
}
