package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 第 9 步：一次性 token 在 worker 这边怎么用：一个 token 只换一次连接；断线重连、被拒都要客户端再要一个。 */
class TokenStateTest {
    @Test
    void dockerWorkerNeverNeedsToken() {
        TokenState t = new TokenState("");
        assertFalse(t.needsNew());
        assertEquals("", t.forHello());
        t.connected();
        assertFalse(t.needsNew(), "Docker 里的本地 worker 没有 token，一直免 token 重连");
    }

    @Test
    void tokenIsUsedOncePerConnection() {
        TokenState t = new TokenState("tok1");
        assertFalse(t.needsNew());
        assertEquals("tok1", t.forHello());
        t.connected();
        assertTrue(t.needsNew(), "连上过一次，token 就作废了，断线重连要新的");
        assertTrue(t.offer("tok2"));
        assertFalse(t.needsNew());
        assertEquals("tok2", t.forHello());
    }

    @Test
    void rejectedTokenNeedsNew() {
        TokenState t = new TokenState("tok1");
        t.forHello();
        t.rejected();
        assertTrue(t.needsNew());
        assertFalse(t.offer(""), "空的不算");
        assertTrue(t.needsNew());
    }

    @Test
    void sameTokenTwiceIsNotFresh() {
        TokenState t = new TokenState("tok1");
        t.forHello();
        t.connected();
        assertFalse(t.offer("tok1"), "用过的再给一次不算新的");
        assertTrue(t.needsNew());
    }
}
