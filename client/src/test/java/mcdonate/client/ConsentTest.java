package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 9 步（docs/step9-plan.md 1.1）：同意记录。默认没同意；玩家点了同意才写。 */
class ConsentTest {
    @Test
    void defaultIsNo(@TempDir Path dir) {
        Path f = dir.resolve("config/mc-donate.json");
        assertFalse(Consent.load(f).given());
        assertFalse(Files.exists(f), "读不会创建文件");
    }

    @Test
    void acceptWritesAndReadsBack(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("config/mc-donate.json");
        Instant at = Instant.parse("2026-11-09T03:00:00Z");
        Consent.accept(f, at);
        Consent c = Consent.load(f);
        assertTrue(c.given());
        assertEquals(at, c.eulaAcceptedAt());
        assertTrue(Files.readString(f).contains("\"consent\": true"));
    }

    @Test
    void revokeTurnsOff(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mc-donate.json");
        Consent.accept(f, Instant.EPOCH);
        Consent.revoke(f);
        assertFalse(Consent.load(f).given());
    }

    @Test
    void garbageOrPartialFileMeansNo(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mc-donate.json");
        Files.writeString(f, "{\"consent\": true}");  // 没有 EULA 同意时间：不算
        assertFalse(Consent.load(f).given());
        Files.writeString(f, "not json");
        assertFalse(Consent.load(f).given());
        Files.writeString(f, "{\"consent\": \"true\", \"eulaAcceptedAt\": \"2026-11-09T03:00:00Z\"}");
        assertFalse(Consent.load(f).given(), "只认布尔 true");
    }
}
