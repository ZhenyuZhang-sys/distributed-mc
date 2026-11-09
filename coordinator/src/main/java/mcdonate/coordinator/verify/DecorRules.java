package mcdonate.coordinator.verify;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 装饰类清单（docs/step8-plan.md 第 7 节第 6 条）。读 common 里共用的 /mcdonate/decor.json，
 * 判定顺序和 Python 的 analysis/decor_rules.py、fish 的 decor.py 一样。
 */
public final class DecorRules {
    public enum Kind { DECOR, TERRAIN, STRICT }

    private final Set<String> decor, terrainProps;
    private final Set<String> terrainPairs, excusedPairs;
    private final int terrainClusterMax;
    private final double terrainAlpha;

    DecorRules(JsonObject o) {
        if (o.get("schema").getAsInt() != 1) throw new IllegalArgumentException("decor.json schema 不是 1");
        decor = names(o.getAsJsonArray("decor"));
        terrainProps = names(o.getAsJsonArray("terrain_props"));
        terrainPairs = pairs(o.getAsJsonArray("terrain_pairs"));
        excusedPairs = pairs(o.getAsJsonArray("excused_pairs"));
        terrainClusterMax = o.get("terrain_cluster_max").getAsInt();
        terrainAlpha = o.get("terrain_alpha").getAsDouble();
        if (terrainClusterMax < 1 || !(terrainAlpha > 0 && terrainAlpha < 1))
            throw new IllegalArgumentException("地形互换判法参数不合法");
    }

    /** 地形互换 6 邻接连成一处，每处最多这么多方块；超过整处算严格（丢一大片海不能当成湖）。 */
    public int terrainClusterMax() {
        return terrainClusterMax;
    }

    /** 导入部分地形互换处数的单侧二项检验阈值（fish 的导入验收用；donor 抽查只用上面的每处上限）。 */
    public double terrainAlpha() {
        return terrainAlpha;
    }

    public static DecorRules load() {
        try (InputStream in = DecorRules.class.getResourceAsStream("/mcdonate/decor.json")) {
            if (in == null) throw new IllegalStateException("classpath 上没有 /mcdonate/decor.json");
            return new DecorRules(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new IllegalStateException("读 decor.json 失败", e);
        }
    }

    public boolean isDecor(String name) {
        return decor.contains(name);
    }

    /** a、b 是同一位置上两边不同的方块状态；名字一样表示只有属性不同。 */
    public Kind classify(String a, String b) {
        if (decor.contains(a) || decor.contains(b)) return Kind.DECOR;
        if (a.equals(b)) return terrainProps.contains(a) ? Kind.TERRAIN : Kind.STRICT;
        String pair = pair(a, b);
        if (excusedPairs.contains(pair)) return Kind.DECOR;
        if (terrainPairs.contains(pair)) return Kind.TERRAIN;
        return Kind.STRICT;
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static Set<String> names(JsonArray a) {
        Set<String> out = new HashSet<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return Set.copyOf(out);
    }

    private static Set<String> pairs(JsonArray a) {
        Set<String> out = new HashSet<>();
        for (JsonElement e : a) {
            JsonArray p = e.getAsJsonArray();
            if (p.size() != 2 || p.get(0).getAsString().equals(p.get(1).getAsString())) throw new IllegalArgumentException("每对必须是两个不同的方块");
            out.add(pair(p.get(0).getAsString(), p.get(1).getAsString()));
        }
        return Set.copyOf(out);
    }
}
