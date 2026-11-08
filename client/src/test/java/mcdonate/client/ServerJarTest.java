package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 9 步：服务器 jar 的地址和校验。不联网，用本地准备好的清单和假 jar。 */
class ServerJarTest {
    // 格式照 Mojang 的 version_manifest_v2.json，内容是假的
    private static final String MANIFEST = """
            {"latest": {"release": "1.21.4"},
             "versions": [
               {"id": "1.21.4", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/aaa/1.21.4.json"},
               {"id": "1.21.1", "type": "release", "url": "https://piston-meta.mojang.com/v1/packages/bbb/1.21.1.json"}
             ]}""";

    private static final String VERSION = """
            {"id": "1.21.1",
             "downloads": {
               "client": {"url": "https://piston-data.mojang.com/v1/objects/ccc/client.jar", "sha1": "ccc", "size": 1},
               "server": {"url": "https://piston-data.mojang.com/v1/objects/ddd/server.jar",
                          "sha1": "a9993e364706816aba3e25717850c26c9cd0d89d", "size": 3}
             }}""";

    @TempDir
    Path dir;

    @Test
    void findsVersionUrl() {
        assertEquals("https://piston-meta.mojang.com/v1/packages/bbb/1.21.1.json",
                ServerJar.versionJsonUrl(MANIFEST, "1.21.1"));
        assertThrows(IllegalArgumentException.class, () -> ServerJar.versionJsonUrl(MANIFEST, "1.20"));
    }

    @Test
    void findsServerDownload() {
        ServerJar.Download d = ServerJar.serverDownload(VERSION);
        assertEquals("https://piston-data.mojang.com/v1/objects/ddd/server.jar", d.url());
        assertEquals(3, d.size());
    }

    @Test
    void goodFileKept() throws IOException {
        Path jar = dir.resolve("server.jar");
        Files.writeString(jar, "abc"); // sha1("abc") = a9993e36...
        ServerJar.verifyOrDelete(jar, ServerJar.serverDownload(VERSION));
        assertTrue(Files.exists(jar));
    }

    @Test
    void badFileDeleted() throws IOException {
        Path jar = dir.resolve("server.jar");
        Files.writeString(jar, "abd");
        assertThrows(IOException.class, () -> ServerJar.verifyOrDelete(jar, ServerJar.serverDownload(VERSION)));
        assertFalse(Files.exists(jar));
    }

    @Test
    void wrongSizeDeleted() throws IOException {
        Path jar = dir.resolve("server.jar");
        Files.writeString(jar, "abcd");
        assertThrows(IOException.class, () -> ServerJar.verifyOrDelete(jar, ServerJar.serverDownload(VERSION)));
        assertFalse(Files.exists(jar));
    }
}
