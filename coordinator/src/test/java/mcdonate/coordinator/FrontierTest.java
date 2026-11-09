package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 服务器的"前沿"：玩家前进方向上，服务器已经占住（有 holder）的 chunk 最远到第几圈。
 * 11-09 kexu 演示 B 轮：被接受的 tile 交回时区域离玩家都 ≥ 14 圈，被丢的 ≤ 13 圈；
 * 找需求的 inner（视距 + 11 + 1 = 22）比真正的前沿远 8 圈，按 inner 算"来不及"会把能用上的也拦掉。
 */
class FrontierTest {
    /** 以 (0,0) 为中心、切比雪夫半径 r 以内都有 holder。 */
    private static java.util.function.LongPredicate square(int r) {
        return k -> Math.max(Math.abs((int) k), Math.abs((int) (k >> 32))) <= r;
    }

    @Test
    void alongX() {
        assertEquals(13, Frontier.ahead(0, 0, 30, 0, 22, square(13)));
    }

    @Test
    void diagonal() {
        assertEquals(9, Frontier.ahead(0, 0, -20, 20, 22, square(9)));
    }

    @Test
    void cappedAtMax() {
        assertEquals(22, Frontier.ahead(0, 0, 0, 30, 22, square(40)));
    }

    @Test
    void notMovingIsUnknown() {
        assertEquals(-1, Frontier.ahead(0, 0, 0, 0, 22, square(13)));
    }

    @Test
    void relativeToPlayer() {
        java.util.function.LongPredicate around = k -> Math.max(Math.abs((int) k - 100), Math.abs((int) (k >> 32) + 50)) <= 12;
        assertEquals(12, Frontier.ahead(100, -50, 0, -30, 22, around));
    }
}
