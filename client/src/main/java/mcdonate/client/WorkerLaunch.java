package mcdonate.client;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 第 9 步：拼 worker 子进程的启动命令（纯逻辑）。
 *
 * <ul>
 *   <li>用游戏自己的 Java；内存上限 -Xmx；
 *   <li>生成线程数：专用服务器的生成线程池 = 可见核数 − 1，运行中改不了，所以用 -XX:ActiveProcessorCount 在启动时定；
 *   <li>降低优先级：Linux、macOS 用 nice -n 10；Windows 用 cmd /c start /low /b /wait；
 *   <li>父进程 PID 用系统属性传，worker 每秒检查它还在不在（另一道保险是标准输入 EOF）；
 *   <li>token 走环境变量 MCDONATE_TOKEN，不放进命令行参数：同一台机器上的其他用户能用 ps 看到命令行。
 * </ul>
 */
public final class WorkerLaunch {
    public static final int NICE = 10;
    public static final String TOKEN_ENV = "MCDONATE_TOKEN";

    /** inviteSeed：邀请里的 seed，worker 建世界直接用它，不为了 seed 单独握手（调度 11-09，docs/step9-plan.md 3.2b）。 */
    public record Options(Path java, int xmxMb, int genThreads, long parentPid, String coordinator, long inviteSeed, boolean boost) {
        /** 不带加速。 */
        public Options(Path java, int xmxMb, int genThreads, long parentPid, String coordinator, long inviteSeed) {
            this(java, xmxMb, genThreads, parentPid, coordinator, inviteSeed, false);
        }
    }

    private WorkerLaunch() {}

    public static List<String> command(Options o, String osName) {
        List<String> java = new ArrayList<>(List.of(
                o.java().toString(),
                "-Xmx" + o.xmxMb() + "M",
                "-XX:ActiveProcessorCount=" + (o.genThreads() + 1),
                "-Dmcdonate.coordinator=" + o.coordinator(),
                "-Dmcdonate.parentPid=" + o.parentPid(),
                "-Dmcdonate.parentStartMillis=" + ProcessHandle.of(o.parentPid())
                        .flatMap(p -> p.info().startInstant()).map(Instant::toEpochMilli).orElse(-1L),
                "-Dmcdonate.inviteSeed=" + o.inviteSeed(),
                "-Dmcdonate.boost=" + o.boost(),
                "-jar", "fabric-server-launch.jar", "nogui"));
        List<String> cmd = new ArrayList<>();
        if (osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            // 第一个带引号的参数是窗口标题；/wait 让 cmd 等 worker 结束，父子关系和退出码才对
            cmd.addAll(List.of("cmd", "/c", "start", "\"mc-donate worker\"", "/low", "/b", "/wait"));
        } else {
            cmd.addAll(List.of("nice", "-n", Integer.toString(NICE)));
        }
        cmd.addAll(java);
        return cmd;
    }

    /** 子进程额外的环境变量。 */
    public static Map<String, String> environment(String token) {
        return Map.of(TOKEN_ENV, token);
    }
}
