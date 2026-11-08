package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
    @AfterEach
    void clear() {
        System.clearProperty("mcdonate.testKey");
        Config.reset(null);
    }

    @Test
    void missingFileUsesDefaults() {
        Config.reset(Config.read(Path.of("does/not/exist.properties")));
        assertEquals(42, Config.getInt("testKey", 42));
        assertEquals("x", Config.get("testKey", "x"));
    }

    @Test
    void fileOverridesDefaultAndPropertyOverridesFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("mcdonate.properties");
        Files.writeString(f, "# 注释\ntestKey = 30_000\n");
        Config.reset(Config.read(f));
        assertEquals(30_000L, Config.getLong("testKey", 1));
        System.setProperty("mcdonate.testKey", "7");
        assertEquals(7L, Config.getLong("testKey", 1));
    }

    @Test
    void badNumberFailsLoudly() {
        Properties p = new Properties();
        p.setProperty("testKey", "abc");
        Config.reset(p);
        assertThrows(IllegalArgumentException.class, () -> Config.getInt("testKey", 1));
    }

    @Test
    void doubles() {
        Properties p = new Properties();
        p.setProperty("testKey", "1.5");
        Config.reset(p);
        assertEquals(1.5, Config.getDouble("testKey", 0), 1e-9);
    }
}
