package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 11-09 加速模式：游戏经标准输入告诉 worker 开关加速（BOOST on / BOOST off）。 */
class ControlLineBoostTest {
    @Test
    void parsesOnOff() {
        assertEquals(new ControlLine.Command(ControlLine.Kind.BOOST, "on"), ControlLine.parse("BOOST on"));
        assertEquals(new ControlLine.Command(ControlLine.Kind.BOOST, "off"), ControlLine.parse(" boost OFF "));
    }

    @Test
    void rejectsOthers() {
        assertNull(ControlLine.parse("BOOST"));
        assertNull(ControlLine.parse("BOOST maybe"));
        assertNull(ControlLine.parse("BOOST on now"));
    }
}
