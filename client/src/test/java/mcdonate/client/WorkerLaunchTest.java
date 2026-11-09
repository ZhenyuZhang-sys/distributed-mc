package mcdonate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 第 9 步：worker 子进程的启动命令。 */
class WorkerLaunchTest {
    private final WorkerLaunch.Options opts =
            new WorkerLaunch.Options(Path.of("/jre/bin/java"), 2048, 2, 4242, "mc.example.org:25580", -77);

    @Test
    void linuxUsesNice() {
        List<String> c = WorkerLaunch.command(opts, "Linux");
        assertEquals(List.of("nice", "-n", "10", "/jre/bin/java"), c.subList(0, 4));
        assertTrue(c.contains("-Xmx2048M"));
        assertTrue(c.contains("-XX:ActiveProcessorCount=3"), "2 个生成线程 = 3 个可见核");
        assertTrue(c.contains("-Dmcdonate.parentPid=4242"));
        assertTrue(c.contains("-Dmcdonate.coordinator=mc.example.org:25580"));
        assertTrue(c.contains("-Dmcdonate.inviteSeed=-77"), "建世界用邀请里的 seed");
    }

    @Test
    void macUsesNice() {
        assertEquals("nice", WorkerLaunch.command(opts, "Mac OS X").get(0));
    }

    @Test
    void windowsUsesStartLow() {
        List<String> c = WorkerLaunch.command(opts, "Windows 11");
        assertEquals(List.of("cmd", "/c", "start", "\"mc-donate worker\"", "/low", "/b", "/wait"), c.subList(0, 7));
        assertEquals("/jre/bin/java", c.get(7));
    }

    @Test
    void tokenNotOnCommandLine() {
        String token = "s3cr3t-token-value";
        List<String> c = WorkerLaunch.command(opts, "Linux");
        assertFalse(String.join(" ", c).contains(token));
        assertEquals(token, WorkerLaunch.environment(token).get(WorkerLaunch.TOKEN_ENV));
    }
}
