package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** /donor imports 分页：RCON 一次回复约 4096 字节，按字节分页（11-09 条目变长后固定 30 条一页会被截断）。 */
class ImportsPageTest {
    @Test
    void pagesFitInBudgetAndCoverEverything() {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add("{\"tile\":[" + (-123456 + i) + ",654321],\"world\":\"0123456789abcdef0123456789abcdef\","
                    + "\"accepted\":true,\"written\":36,\"bytes\":3141592,\"wire_bytes\":271828,\"ms\":1234}");
        }
        int from = 0, seen = 0;
        while (from < all.size()) {
            String page = CoordinatorServer.importsPage(all, from, CoordinatorServer.IMPORTS_PAGE_BYTES);
            assertTrue(page.length() < 4000, "一页 " + page.length() + " 字节");
            int n = page.split("\\{\"tile\"").length - 1;
            assertTrue(n >= 1);
            seen += n;
            from += n;
        }
        assertEquals(100, seen);
    }

    @Test
    void oneHugeItemStillReturned() {
        String big = "{\"x\":\"" + "a".repeat(5000) + "\"}";
        assertTrue(CoordinatorServer.importsPage(List.of(big), 0, 3000).contains(big), "至少一条，不卡死翻页");
        assertEquals("{\"total\":1,\"items\":[]}", CoordinatorServer.importsPage(List.of(big), 1, 3000));
    }
}
