package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldIdTest {
    @Test
    void sameWorldKeepsId(@TempDir Path world) throws IOException {
        // worker 重启、存档还在：编号不变
        String a = WorldId.loadOrCreate(world);
        assertEquals(a, WorldId.loadOrCreate(world));
    }

    @Test
    void newWorldGetsNewId(@TempDir Path a, @TempDir Path b) throws IOException {
        // 存档清掉重建：编号变
        assertNotEquals(WorldId.loadOrCreate(a), WorldId.loadOrCreate(b));
    }
}
