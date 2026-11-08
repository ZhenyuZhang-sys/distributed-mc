package mcdonate.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 可调参数。和机器有关的数（租约、提前量、并发数……）都从这里取，部署到别的机器时再调。
 *
 * <p>优先级：JVM 参数 {@code -Dmcdonate.<key>} &gt; 配置文件 &gt; 代码里的默认值。
 * 配置文件默认是服务器目录下的 {@code config/mcdonate.properties}，可用 {@code -Dmcdonate.config} 换路径；
 * 不存在就全用默认值。第一次取值时读一次，之后不再读。
 */
public final class Config {
    private static volatile Properties file;

    private Config() {
    }

    public static Path path() {
        return Path.of(System.getProperty("mcdonate.config", "config/mcdonate.properties"));
    }

    private static Properties file() {
        Properties p = file;
        if (p == null) {
            p = read(path());
            file = p;
        }
        return p;
    }

    static Properties read(Path path) {
        Properties p = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("读不了配置文件 " + path, e);
            }
        }
        return p;
    }

    /** 测试用：换一份配置（null 表示下次重新读文件）。 */
    static void reset(Properties p) {
        file = p;
    }

    public static String get(String key, String def) {
        String v = System.getProperty("mcdonate." + key);
        if (v == null) {
            v = file().getProperty(key);
        }
        return v == null ? def : v.trim();
    }

    public static long getLong(String key, long def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v.replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置 " + key + " 不是整数：" + v, e);
        }
    }

    public static int getInt(String key, int def) {
        return Math.toIntExact(getLong(key, def));
    }

    public static double getDouble(String key, double def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("配置 " + key + " 不是数字：" + v, e);
        }
    }
}
