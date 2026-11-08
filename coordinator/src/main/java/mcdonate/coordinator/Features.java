package mcdonate.coordinator;

import mcdonate.common.Config;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * 第 7 步的调度优化开关（docs/step7-plan.md 0.1）。全部关闭就是 v0（第 6 步）。
 * 启动时用系统属性 mcdonate.features=a,b,c 设初值，运行中用 /donor feature 切换。
 */
public final class Features {
    /** 按领地派任务（7.1）。 */
    public static final String TERRITORY = "territory";
    /** 预测 + deadline（7.2）。 */
    public static final String DEADLINE = "deadline";
    /** 按实测算力分配（7.3）。 */
    public static final String THROUGHPUT = "throughput";
    /** 对冲：快到期就交给服务器（7.4）。 */
    public static final String HEDGE = "hedge";
    /** 等 donor（7.6）。 */
    public static final String WAITDONOR = "waitdonor";

    public static final Set<String> ALL = Set.of(TERRITORY, DEADLINE, THROUGHPUT, HEDGE, WAITDONOR);

    private final Set<String> on = new ConcurrentSkipListSet<>();

    /** 从逗号分隔的字符串设初值（比如系统属性）；不认识的名字抛异常。 */
    public Features(String initial) {
        if (initial != null) {
            for (String name : initial.split(",")) {
                if (!name.isBlank()) {
                    set(name.trim(), true);
                }
            }
        }
    }

    public static Features fromSystemProperty() {
        return new Features(Config.get("features", ""));
    }

    public boolean on(String name) {
        return on.contains(name);
    }

    public void set(String name, boolean value) {
        if (!ALL.contains(name)) {
            throw new IllegalArgumentException("不认识的开关：" + name + "，只有 " + new TreeSet<>(ALL));
        }
        if (value) {
            on.add(name);
        } else {
            on.remove(name);
        }
    }

    /** ["deadline","territory"]：按名字排序，写进 /donor status 和结果文件。 */
    public String json() {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (String n : on) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(n).append('"');
        }
        return sb.append(']').toString();
    }
}
