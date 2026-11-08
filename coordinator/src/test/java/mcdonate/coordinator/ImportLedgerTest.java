package mcdonate.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportLedgerTest {
    static long key(int x, int z) {
        return ((long) z << 32) | (x & 0xffffffffL);
    }

    @Test
    void ownerUntilServerLoads() {
        ImportLedger l = new ImportLedger();
        l.recordImport("w1", 3, -2, "", List.of(key(12, -8), key(13, -8)), 1000);
        assertEquals("w1", l.owner(key(12, -8)));
        assertNull(l.owner(key(0, 0)));
        l.serverSaved(key(12, -8));
        assertNull(l.owner(key(12, -8)), "服务器加载过就算服务器的");
        assertEquals("w1", l.owner(key(13, -8)));
        assertTrue(l.get(key(12, -8)).serverLoaded());
    }

    @Test
    void survivesFlushAndLoad(@TempDir Path dir) throws Exception {
        ImportLedger a = new ImportLedger();
        // 跨 region（负坐标、region 边界两边）
        a.recordImport("w1", -1, -1, "tok", List.of(key(-1, -1), key(-33, 40), key(31, 31), key(32, 32)), 42);
        a.serverSaved(key(31, 31));
        a.flush(dir);
        try (var files = Files.list(dir)) {
            assertEquals(4, files.filter(f -> f.toString().endsWith(".dat")).count());
        }
        ImportLedger b = new ImportLedger();
        assertEquals(4, b.load(dir));
        assertEquals("w1", b.owner(key(-33, 40)));
        assertNull(b.owner(key(31, 31)));
        ImportLedger.Entry e = b.get(key(-1, -1));
        assertEquals(-1, e.tileX);
        assertEquals("tok", e.token);
        assertEquals(42, e.importedAt);
        assertFalse(e.serverLoaded());
    }

    @Test
    void onlyDirtyRegionsAreRewritten(@TempDir Path dir) throws Exception {
        ImportLedger a = new ImportLedger();
        a.recordImport("w1", 0, 0, "", List.of(key(0, 0)), 1);
        a.flush(dir);
        Path f = dir.resolve("r.0.0.dat");
        long t0 = Files.getLastModifiedTime(f).toMillis();
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t0 - 10_000));
        a.recordImport("w1", 40, 0, "", List.of(key(160, 0)), 2);
        a.flush(dir);
        assertEquals(t0 - 10_000, Files.getLastModifiedTime(f).toMillis(), "没改的 region 不重写");
        assertTrue(Files.exists(dir.resolve("r.5.0.dat")));
    }

    @Test
    void hooksFire() {
        ImportLedger l = new ImportLedger();
        List<ImportLedger.Imported> imports = new ArrayList<>();
        List<Long> first = new ArrayList<>();
        l.onImport(imports::add);
        l.onFirstServerLoad((c, e) -> first.add(c));
        l.recordImport("w1", 0, 0, "", List.of(key(1, 1)), 5);
        l.serverSaved(key(1, 1));
        l.serverSaved(key(1, 1)); // 第二次存不算
        l.serverSaved(key(9, 9)); // 不是导入的不算
        assertEquals(1, imports.size());
        assertEquals(List.of(key(1, 1)), first);
    }

    @Test
    void missingDirIsEmpty(@TempDir Path dir) throws Exception {
        assertEquals(0, new ImportLedger().load(dir.resolve("nope")));
    }
}
