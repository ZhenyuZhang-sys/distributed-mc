package mcdonate.coordinator.verify;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcdonate.common.chunk.ChunkBlocks;
import mcdonate.common.chunk.SectionBlocks;
import mcdonate.coordinator.verify.DonorVerifier.Action;
import mcdonate.coordinator.verify.DonorVerifier.Policy;
import mcdonate.coordinator.verify.DonorVerifier.State;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * 第 8 步：coordinator 里验证的纯逻辑（比较器、阈值、符号检验、抽查状态机）。
 * 真实数据：第 1 步 mountain 区域同一个 chunk（1335, 3706）在两个原版世界里各一份，两份之间有 19 处钻石矿差异。
 */
class VerifyTest {
    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final DecorRules RULES = DecorRules.load();
    static final ChunkComparator CMP = new ChunkComparator(RULES);

    static CompoundTag chunk(String run) throws IOException {
        try (InputStream in = VerifyTest.class.getResourceAsStream("/chunks/" + run + "-1335-3706.nbt")) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
    }

    static JsonObject json(String rel) throws IOException {
        return JsonParser.parseString(Files.readString(REPO.resolve(rel))).getAsJsonObject();
    }

    static ChunkComparison clean() {
        return new ChunkComparison(0, 0, 0, 10, 0, 0, 0, 0, Map.of("diamond", 1), Map.of("diamond", 1), List.of());
    }

    static ChunkComparison strict() {
        return new ChunkComparison(0, 0, 3, 0, 0, 0, 0, 0, Map.of(), Map.of(), List.of("x"));
    }

    static ChunkComparison stuffed(int extra) {
        return new ChunkComparison(0, 0, 0, extra, 0, 0, 0, 0, Map.of("diamond", extra), Map.of("diamond", 0), List.of());
    }

    // ---- 清单 ----

    @Test
    void decorRulesMatchSharedCases() throws IOException {
        // 和 Python（analysis/decor_rules.py）、fish 的 decor.py 在同一批样例上结论一样。
        int n = 0;
        for (JsonElement e : json("tests/step8/data/decor-cases.json").getAsJsonArray("cases")) {
            JsonObject c = e.getAsJsonObject();
            String expected = c.get("expected").getAsString().toUpperCase();
            assertEquals(expected, RULES.classify(c.get("a").getAsString(), c.get("b").getAsString()).name(), c.toString());
            n++;
        }
        assertTrue(n > 300);
    }

    // ---- 比较器 ----

    @Test
    void twoVanillaWorldsHaveNoStrictDifference() throws IOException {
        ChunkComparison c = CMP.compare(chunk("mountain-c1-r1"), chunk("mountain-c1-r2"));
        assertEquals(0, c.strict(), c.examples().toString());
        assertEquals(0, c.biomeDiffSections());
        assertTrue(c.decor() > 0, "这对原版 chunk 有装饰类差异");
        assertEquals(19, c.extra().get("diamond") + c.missing().get("diamond"));
        assertEquals(Verdict.Outcome.CLEAN, Verdict.of(c, Thresholds.defaults()).outcome());
    }

    @Test
    void identicalChunkIsAllZero() throws IOException {
        ChunkComparison c = CMP.compare(chunk("mountain-c1-r1"), chunk("mountain-c1-r1"));
        assertEquals(List.of(0, 0, 0, 0, 0, 0), List.of(c.strict(), c.decor(), c.terrain(), c.biomeDiffSections(), c.heightDiffColumns(), c.valuedExtra()));
    }

    @Test
    void stuffedDiamondsAreDecorButCountedAsExtra() throws IOException {
        CompoundTag donor = chunk("mountain-c1-r1");
        ChunkBlocks b = new ChunkBlocks(donor);
        int put = 0;
        for (int y = -50; y < 10 && put < 30; y++)
            for (int x = 0; x < 16 && put < 30; x++)
                if (b.name(x, y, 0).equals("minecraft:stone") || b.name(x, y, 0).equals("minecraft:deepslate")) {
                    b.set(x, y, 0, SectionBlocks.block(b.name(x, y, 0).equals("minecraft:deepslate") ? "minecraft:deepslate_diamond_ore" : "minecraft:diamond_ore"));
                    put++;
                }
        b.write();
        ChunkComparison c = CMP.compare(donor, chunk("mountain-c1-r1"));
        assertEquals(30, put);
        assertEquals(0, c.strict(), "塞矿在严格层看不出来");
        assertEquals(30, c.extra().get("diamond"));
        Verdict v = Verdict.of(c, Thresholds.defaults());
        assertEquals(Verdict.Outcome.SUSPICIOUS, v.outcome());
        assertTrue(v.reasons().get(0).contains("diamond"));
    }

    @Test
    void flattenedTerrainIsStrictAndChangesHeight() throws IOException {
        CompoundTag donor = chunk("mountain-c1-r1");
        ChunkBlocks b = new ChunkBlocks(donor);
        for (int y = 100; y < b.maxY(); y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) b.set(x, y, z, SectionBlocks.block("minecraft:air"));
        b.write();
        ChunkComparison c = CMP.compare(donor, chunk("mountain-c1-r2"));
        assertTrue(c.strict() > 0);
        assertTrue(c.heightDiffColumns() > 0);
        assertEquals(Verdict.Outcome.STRICT_MISMATCH, Verdict.of(c, Thresholds.defaults()).outcome());
    }

    @Test
    void changedBiomeIsStrict() throws IOException {
        CompoundTag donor = chunk("mountain-c1-r1");
        ListTag sections = donor.getList("sections", Tag.TAG_COMPOUND);
        CompoundTag biomes = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:desert"));
        biomes.put("palette", palette);
        sections.getCompound(10).put("biomes", biomes);
        ChunkComparison c = CMP.compare(donor, chunk("mountain-c1-r1"));
        assertEquals(1, c.biomeDiffSections());
        assertEquals(0, c.strict());
        assertEquals(Verdict.Outcome.STRICT_MISMATCH, Verdict.of(c, Thresholds.defaults()).outcome());
    }

    @Test
    void valueCountsSeeDiamonds() throws IOException {
        Map<String, Integer> counts = ChunkComparator.valueCounts(chunk("mountain-c1-r1"));
        assertTrue(counts.getOrDefault("diamond", 0) > 0);
        assertFalse(Verdict.valueFlag(counts, Thresholds.defaults()), "原版 chunk 不该被按价值审计标出来");
        assertTrue(Verdict.valueFlag(Map.of("diamond", 55), Thresholds.defaults()));
    }

    // ---- 阈值和原版数据对得上 ----

    @Test
    void thresholdsComeFromVanillaData() throws IOException {
        JsonObject max = json("tests/step8/data/vanilla-checks.json").getAsJsonObject("chunk_max");
        Thresholds.VANILLA_EXTRA.forEach((k, v) -> assertEquals(max.get("extra." + k).getAsInt(), v, k));
        Thresholds.VANILLA_COUNT.forEach((k, v) -> assertEquals(max.get("count." + k).getAsInt(), v, k));
        assertEquals(max.get("height_diff_columns").getAsInt(), Thresholds.VANILLA_HEIGHT_DIFF_COLUMNS);
        Thresholds t = Thresholds.defaults();
        assertEquals(17, t.extra().get("diamond")); // ⌈10 × 1.5⌉ + 2
        assertEquals(5, t.extra().get("emerald"));
        assertThrows(IllegalArgumentException.class, () -> Thresholds.withMargin(0.9, 0));
    }

    // ---- 符号检验 ----

    @Test
    void signTestCriticalValueAndAlpha() {
        SignTest s = new SignTest(20, 1e-4);
        assertEquals(19, s.critical()); // P(X ≥ 19) = 21 / 2^20 ≈ 2.0e-5 ≤ 1e-4；P(X ≥ 18) ≈ 2.0e-4 > 1e-4
        assertEquals(21.0 / (1 << 20), s.actualAlpha(), 1e-12);
        assertEquals(21, new SignTest(5, 1e-4).critical() + 15); // m 太小时临界值 = m + 1，永远不拒绝
    }

    @Test
    void signTestIgnoresTiesAndResetsEachBatch() {
        SignTest s = new SignTest(3, 0.2);
        assertEquals(3, s.critical()); // P(X = 3) = 1/8 ≤ 0.2
        assertEquals(SignTest.Result.PENDING, s.feed(2, 2));
        assertEquals(SignTest.Result.PENDING, s.feed(1, 0));
        assertEquals(SignTest.Result.PENDING, s.feed(1, 0));
        assertEquals(SignTest.Result.REJECT, s.feed(1, 0));
        assertEquals(SignTest.Result.PENDING, s.feed(0, 1)); // 新批次
        assertEquals(SignTest.Result.PENDING, s.feed(1, 0));
        assertEquals(SignTest.Result.PASS, s.feed(1, 0));
    }

    @Test
    void signTestFalsePositiveRateUnderH0() {
        // 原版：两个方向各一半。20 万批 × 20 个 chunk，误报率应当接近实际 alpha（2.0e-5），远低于名义 1e-4。
        Random r = new Random(1);
        SignTest s = new SignTest(20, 1e-4);
        int rejects = 0, batches = 200_000;
        for (int i = 0; i < batches * 20; i++) {
            boolean up = r.nextBoolean();
            if (s.feed(up ? 1 : 0, up ? 0 : 1) == SignTest.Result.REJECT) rejects++;
        }
        assertTrue(rejects <= 15, "误报 " + rejects + " 次"); // 期望约 4 次
    }

    @Test
    void expectedFalsePositivesFormula() {
        assertEquals(0.1, SignTest.expectedFalsePositives(10, 100, 20, 20, 1e-4), 1e-12);
    }

    // ---- 抽查状态机 ----

    @Test
    void cleanTilesLowerRateToFloorSuspiciousDoubles() {
        DonorVerifier d = new DonorVerifier(Policy.defaults(), Thresholds.defaults());
        for (int i = 0; i < 1000; i++) assertEquals(Action.NONE, d.onRecomputed(List.of(clean())));
        assertEquals(0.025, d.rate(), 1e-12);
        assertEquals(Action.NONE, d.onRecomputed(List.of(stuffed(30))));
        assertEquals(0.05, d.rate(), 1e-12);
        assertEquals(1, d.suspicion());
        assertEquals(State.ACTIVE, d.state());
    }

    @Test
    void valueFlagBoostsCheckProbability() {
        DonorVerifier d = new DonorVerifier(Policy.defaults(), Thresholds.defaults());
        assertFalse(d.shouldCheck(0.1, false)); // 5%
        assertTrue(d.shouldCheck(0.1, true));   // 20%
        assertTrue(d.shouldCheck(0.04, false));
    }

    @Test
    void strictMismatchNeedsTwoAgreeingRecomputes() {
        DonorVerifier d = new DonorVerifier(Policy.defaults(), Thresholds.defaults());
        assertEquals(Action.RECOMPUTE_AGAIN, d.onRecomputed(List.of(clean(), strict())));
        assertEquals(State.AWAITING_SECOND, d.state());
        assertFalse(d.shouldCheck(0, true), "等第二次重算时不再派新的抽查");
        assertThrows(IllegalStateException.class, () -> d.onRecomputed(List.of(clean())));
        assertEquals(Action.BLACKLIST, d.onSecondRecompute(List.of(clean(), strict()), List.of(clean(), clean())));
        assertEquals(State.BLACKLISTED, d.state());
        assertFalse(d.shouldCheck(0, true));
    }

    @Test
    void recomputesDisagreeingMeansListIncompleteNotCheating() {
        DonorVerifier d = new DonorVerifier(Policy.defaults(), Thresholds.defaults());
        d.onRecomputed(List.of(strict()));
        assertEquals(Action.LOG_LIST_INCOMPLETE, d.onSecondRecompute(List.of(strict()), List.of(strict())));
        assertEquals(State.ACTIVE, d.state());
        assertEquals(0, d.suspicion());
        // 第二次重算和 donor 一致（只有第一次不一样）：也按清单不全处理
        d.onRecomputed(List.of(strict()));
        assertEquals(Action.LOG_LIST_INCOMPLETE, d.onSecondRecompute(List.of(clean()), List.of(clean())));
        assertEquals(State.ACTIVE, d.state());
    }

    @Test
    void steadySmallStuffingCaughtBySignTest() {
        // 每个 chunk 只多塞 3 颗钻石，单个 chunk 在阈值（17）以内，看不出来；累积 20 个 chunk 后符号检验拉黑。
        DonorVerifier d = new DonorVerifier(Policy.defaults(), Thresholds.defaults());
        List<Action> actions = new ArrayList<>();
        for (int i = 0; i < 20; i++) actions.add(d.onRecomputed(List.of(new ChunkComparison(0, 0, 0, 3, 0, 0, 0, 0,
                Map.of("diamond", 3), Map.of("diamond", 0), List.of()))));
        assertEquals(0, d.suspicion());
        assertEquals(Action.BLACKLIST, actions.get(19));
        assertEquals(State.BLACKLISTED, d.state());
    }

    @Test
    void policyValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Policy(0.05, 0.1, 1, 0.98, 4, 20, 1e-4));
        assertThrows(IllegalArgumentException.class, () -> new Policy(0.05, 0.01, 1, 1.5, 4, 20, 1e-4));
        assertThrows(IllegalArgumentException.class, () -> new Policy(0.05, 0.01, 1, 0.9, 0.5, 20, 1e-4));
    }
}
