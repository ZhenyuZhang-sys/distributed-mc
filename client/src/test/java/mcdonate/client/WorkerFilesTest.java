package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第 9 步 b：首次准备 .minecraft/mc-donate/worker/（不联网，用假的下载器）。 */
class WorkerFilesTest {
    private static final byte[] SERVER = "fake server jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LAUNCHER = "fake fabric launcher".getBytes(StandardCharsets.UTF_8);

    private static String sha1(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b));
    }

    private final List<String> fetched = new ArrayList<>();

    private WorkerFiles.Downloader fake(byte[] server) throws Exception {
        String manifest = "{\"versions\":[{\"id\":\"1.21.1\",\"url\":\"https://piston-meta.mojang.com/v/1.21.1.json\"}]}";
        String version = "{\"downloads\":{\"server\":{\"url\":\"https://piston-data.mojang.com/s.jar\",\"sha1\":\""
                + sha1(SERVER) + "\",\"size\":" + SERVER.length + "}}}";
        Map<String, byte[]> net = Map.of(ServerJar.MANIFEST_URL, manifest.getBytes(), "https://piston-meta.mojang.com/v/1.21.1.json",
                version.getBytes(), "https://piston-data.mojang.com/s.jar", server, WorkerFiles.FABRIC_LAUNCHER_URL, LAUNCHER);
        return url -> {
            fetched.add(url);
            byte[] b = net.get(url);
            if (b == null) {
                throw new IOException("没有 " + url);
            }
            return b;
        };
    }

    private static final WorkerFiles.Bundled BUNDLED = name -> switch (name) {
        case "index.txt" -> new ByteArrayInputStream("mcdonate-worker-0.1.0.jar\nfabric-api-0.116.6+1.21.1.jar\n".getBytes());
        case "mcdonate-worker-0.1.0.jar" -> new ByteArrayInputStream("worker".getBytes());
        case "fabric-api-0.116.6+1.21.1.jar" -> new ByteArrayInputStream("api".getBytes());
        default -> null;
    };

    @Test
    void preparesEverythingOnceAndChecksSha1(@TempDir Path dir) throws Exception {
        WorkerFiles w = new WorkerFiles(dir.resolve("mc-donate/worker"));
        w.prepare(fake(SERVER), BUNDLED);
        Path root = dir.resolve("mc-donate/worker");
        assertTrue(Files.exists(root.resolve("server.jar")));
        assertTrue(Files.exists(root.resolve("fabric-server-launch.jar")));
        assertEquals("serverJar=server.jar\n", Files.readString(root.resolve("fabric-server-launcher.properties")));
        assertEquals(List.of("fabric-api-0.116.6+1.21.1.jar", "mcdonate-worker-0.1.0.jar"),
                Files.list(root.resolve("mods")).map(p -> p.getFileName().toString()).sorted().toList());
        assertTrue(Files.readString(root.resolve("eula.txt")).contains("eula=true"));
        assertTrue(w.prepared());
        int first = fetched.size();
        w.prepare(fake(SERVER), BUNDLED);
        assertEquals(first, fetched.size(), "第二次不再下载（sha1 对得上）");
    }

    @Test
    void badDownloadIsDeleted(@TempDir Path dir) throws Exception {
        WorkerFiles w = new WorkerFiles(dir.resolve("w"));
        assertThrows(IOException.class, () -> w.prepare(fake("tampered".getBytes()), BUNDLED));
        assertFalse(Files.exists(dir.resolve("w/server.jar")), "校验不过的删掉");
        assertFalse(w.prepared());
    }

    @Test
    void serverPropertiesAreLocalAndNoWatchdog() {
        String p = WorkerFiles.serverProperties(41234);
        assertTrue(p.contains("server-ip=127.0.0.1\n"), "只听本机");
        assertTrue(p.contains("server-port=41234\n"));
        assertTrue(p.contains("max-tick-time=-1\n"), "worker 关看门狗");
        assertTrue(p.contains("online-mode=false\n"));
        assertTrue(p.contains("enable-rcon=false\n"));
        assertTrue(p.contains("view-distance=2\n"));
    }
}
