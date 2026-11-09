package mcdonate.client.overlay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import mcdonate.common.net.ProvenanceUpdate.Summary;

/**
 * 算力面板的几行字（docs/overlay-plan.md 第 5 节），纯逻辑。worker 那几行来自 worker 自己报的 STATUS JSON
 * （fish 定的字段：state、threads、cpuLoad、cpus、heapUsedMb、heapMaxMb、tiles、chunks、bytes），服务器那几行来自来源数据里的摘要。
 * 缺的字段写"—"；cpuLoad 是 -1（拿不到）写"—"。
 */
public final class HudLines {
    private HudLines() {}

    public static List<String> of(String statusJson, Summary summary) {
        List<String> out = new ArrayList<>();
        JsonObject s = parse(statusJson);
        if (s == null) {
            out.add("worker 没在跑");
        } else {
            String state = str(s, "state");
            out.add("worker：" + switch (state == null ? "" : state) {
                case "running" -> "在跑";
                case "paused" -> "暂停（帧率限速）";
                case "stopping" -> "正在关";
                default -> state == null ? "—" : state;
            } + "，" + num(s, "threads", "%.0f 线程"));
            double load = dbl(s, "cpuLoad"), cpus = dbl(s, "cpus");
            String cpu = load < 0 || Double.isNaN(load) ? "CPU —"
                    : Double.isNaN(cpus) ? String.format(Locale.ROOT, "CPU %.0f%%", load * 100)
                    : String.format(Locale.ROOT, "CPU %.1f 核", load * cpus);
            out.add(cpu + "，内存 " + num(s, "heapUsedMb", "%.0f") + " / " + num(s, "heapMaxMb", "%.0f") + " MB");
            out.add("交回 " + num(s, "tiles", "%.0f tile") + "，" + num(s, "chunks", "%.0f chunk") + "，" + bytes(dbl(s, "bytes")));
        }
        if (summary == null) {
            out.add("在线 donor —，你的贡献 —");
        } else {
            String share = summary.importedTotal() > 0
                    ? String.format(Locale.ROOT, "%.0f%%", 100.0 * summary.importedByYou() / summary.importedTotal()) : "—";
            out.add("在线 donor " + summary.donorsOnline() + "，你的贡献 " + share + "（" + summary.importedByYou() + " / "
                    + summary.importedTotal() + " chunk）");
        }
        return out;
    }

    private static JsonObject parse(String json) {
        if (json == null) return null;
        try {
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static double dbl(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : Double.NaN;
        } catch (RuntimeException ex) {
            return Double.NaN;
        }
    }

    private static String num(JsonObject o, String k, String fmt) {
        double v = dbl(o, k);
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, fmt, v);
    }

    private static String bytes(double b) {
        if (Double.isNaN(b)) return "— 字节";
        if (b >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", b / (1 << 20));
        if (b >= 1 << 10) return String.format(Locale.ROOT, "%.1f KB", b / (1 << 10));
        return String.format(Locale.ROOT, "%.0f 字节", b);
    }
}
