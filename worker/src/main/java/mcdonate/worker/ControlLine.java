package mcdonate.worker;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 第 9 步：游戏（客户端 mod）和 worker 子进程之间的本地控制通道（docs/step9-plan.md 2.1），纯逻辑。
 *
 * <ul>
 *   <li>游戏 → worker（worker 的标准输入，一行一条）：{@code PAUSE}、{@code RESUME}（帧率限速）、{@code STOP}（退出世界）、
 *       {@code STATUS}、{@code TOKEN <token>}（再要来的一次性 token，worker 拿它重连）、
 *       {@code BOOST on|off}（11-09 加速模式：空闲时做后台 tile）。
 *   <li>worker → 游戏（worker 的标准输出）：标准输出里还有 Minecraft 自己的日志，所以给游戏看的行都以 {@code MCDONATE } 开头：
 *       {@code MCDONATE NEED_TOKEN}（token 用过了或被拒，要一个新的）、{@code MCDONATE STATUS {...}}、{@code MCDONATE CONNECTED <编号>}。
 *   <li>token 不进日志：记日志用 {@link #redact}。
 * </ul>
 */
final class ControlLine {
    enum Kind { PAUSE, RESUME, STOP, STATUS, TOKEN, BOOST }

    record Command(Kind kind, String arg) {}

    static final String PREFIX = "MCDONATE ";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private ControlLine() {}

    /** 解析一行命令；读不懂的返回 null（忽略）。 */
    static Command parse(String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) {
            return null;
        }
        Kind k;
        try {
            k = Kind.valueOf(p[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (k == Kind.BOOST) {
            if (p.length != 2) {
                return null;
            }
            String v = p[1].toLowerCase(Locale.ROOT);
            return v.equals("on") || v.equals("off") ? new Command(k, v) : null;
        }
        if (k == Kind.TOKEN) {
            return p.length == 2 && TOKEN.matcher(p[1]).matches() ? new Command(k, p[1]) : null;
        }
        return p.length == 1 ? new Command(k, "") : null;
    }

    static String out(String body) {
        return PREFIX + body;
    }

    static boolean isOut(String line) {
        return line.startsWith(PREFIX);
    }

    static String body(String line) {
        return line.substring(PREFIX.length());
    }

    static String redact(String line) {
        return line.startsWith("TOKEN ") ? "TOKEN ****" : line;
    }
}
