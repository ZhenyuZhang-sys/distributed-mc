package mcdonate.common.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/** docs/step5-plan.md 1.4 的 common 验收。 */
class ProtoTest {
    /** 和真实 tile 差不多大：36 个 chunk，每个 24 个 section，方块数据是随机数（压不小），压缩后约 1.8 MiB。 */
    static CompoundTag realisticTile(long seed) {
        Random rng = new Random(seed);
        ListTag chunks = new ListTag();
        for (int c = 0; c < 36; c++) {
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("xPos", c % 6 - 1);
            chunk.putInt("zPos", c / 6 - 1);
            chunk.putString("Status", c < 16 ? "minecraft:full" : "minecraft:features");
            ListTag sections = new ListTag();
            for (int s = 0; s < 24; s++) {
                CompoundTag section = new CompoundTag();
                section.putByte("Y", (byte) (s - 4));
                ListTag palette = new ListTag();
                for (String name : List.of("minecraft:stone", "minecraft:deepslate", "minecraft:air", "minecraft:water")) {
                    CompoundTag state = new CompoundTag();
                    state.putString("Name", name);
                    palette.add(state);
                }
                CompoundTag blocks = new CompoundTag();
                blocks.put("palette", palette);
                long[] data = new long[256];
                for (int i = 0; i < data.length; i++) {
                    data[i] = rng.nextLong();
                }
                blocks.put("data", new LongArrayTag(data));
                section.put("block_states", blocks);
                sections.add(section);
            }
            chunk.put("sections", sections);
            chunks.add(chunk);
        }
        CompoundTag tile = new CompoundTag();
        tile.putInt("DataVersion", 3955);
        tile.putLong("seed", 424242L);
        tile.put("chunks", chunks);
        return tile;
    }

    static final CompoundTag TILE = realisticTile(1);

    static List<Message> all() {
        return List.of(
                new Hello(Protocol.VERSION, "donor-1"),
                new Hello(Protocol.VERSION, ""),
                new Hello(Protocol.VERSION, "donor-2", "s3cret-token"),
                new Welcome(Protocol.VERSION, 3, 424242L, 3955),
                new Welcome(Protocol.VERSION, Welcome.BAD_VERSION, Long.MIN_VALUE, 0),
                new Welcome(Protocol.VERSION, Welcome.BAD_TOKEN, 424242L, 3955),
                new Task(9, -2, 5, 30_000),
                new Task(10, 3, -4, 30_000, Task.Kind.RECOMPUTE),
                new Task(Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 0),
                Result.success(9, TILE),
                Result.failure(10, "生成失败：boom"),
                new Cancel(9),
                new Heartbeat(1_731_000_000_000L, Heartbeat.NO_TASK),
                new Heartbeat(123L, 9),
                new Heartbeat(124L, 9, true));
    }

    static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    static byte[] frameOf(byte[] body) {
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(raw);
        }
        return out.toByteArray();
    }

    static byte[] compressed(CompoundTag tag) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    // ---- 1. 编码再解码，内容不变 ----

    @Test
    void codecRoundTripEveryMessage() {
        for (Message m : all()) {
            assertEquals(m, MessageCodec.fromTag(MessageCodec.toTag(m)), m.getClass().getSimpleName());
        }
    }

    @Test
    void frameRoundTripEveryMessage() {
        for (Message m : all()) {
            byte[] f = Frame.encode(m);
            assertEquals(f.length - 4, ByteBuffer.wrap(f).getInt(), "长度前缀是消息体字节数");
            assertEquals(List.of(m), new FrameDecoder().feed(f, 0, f.length));
        }
    }

    @Test
    void realisticTileIsRealisticSize() {
        int n = Frame.encode(Result.success(1, TILE)).length;
        assertTrue(n > 1024 * 1024 && n < 3 * 1024 * 1024, "压缩后 " + n + " 字节，应在 1～3 MiB");
    }

    @Test
    void typeNamesAreLowercaseAsAgreed() {
        List<String> got = new ArrayList<>();
        for (Message m : List.of(new Hello(1, "a"), new Welcome(1, 1, 1, 1), new Task(1, 1, 1, 1),
                Result.failure(1, "x"), new Cancel(1), new Heartbeat(1, -1))) {
            got.add(MessageCodec.toTag(m).getString("type"));
        }
        assertEquals(List.of("hello", "welcome", "task", "result", "cancel", "heartbeat"), got);
    }

    // ---- 2. 任意拆块、粘包 ----

    @Test
    void everyFrameFedOneByteAtATime() {
        FrameDecoder d = new FrameDecoder();
        List<Message> got = new ArrayList<>();
        for (Message m : all()) {
            byte[] f = Frame.encode(m);
            for (int i = 0; i < f.length; i++) {
                got.addAll(d.feed(f, i, 1));
            }
        }
        assertEquals(all(), got);
    }

    @Test
    void streamCutAtRandomPlaces() {
        List<byte[]> frames = new ArrayList<>();
        all().forEach(m -> frames.add(Frame.encode(m)));
        byte[] stream = concat(frames);
        Random rng = new Random(424242);
        for (int round = 0; round < 20; round++) {
            FrameDecoder d = new FrameDecoder();
            List<Message> got = new ArrayList<>();
            int pos = 0;
            while (pos < stream.length) {
                int n = Math.min(stream.length - pos, 1 + rng.nextInt(round % 2 == 0 ? 7 : 200_000));
                got.addAll(d.feed(stream, pos, n));
                pos += n;
            }
            assertEquals(all(), got, "第 " + round + " 轮");
        }
    }

    @Test
    void twoFramesInOneFeed() {
        byte[] both = concat(List.of(Frame.encode(new Cancel(1)), Frame.encode(new Heartbeat(5, 1))));
        assertEquals(List.of(new Cancel(1), new Heartbeat(5, 1)), new FrameDecoder().feed(both, 0, both.length));
    }

    @Test
    void wholeStreamInOneFeed() {
        List<byte[]> frames = new ArrayList<>();
        all().forEach(m -> frames.add(Frame.encode(m)));
        byte[] stream = concat(frames);
        assertEquals(all(), new FrameDecoder().feed(stream, 0, stream.length));
    }

    @Test
    void feedHonoursOffsetAndLength() {
        byte[] f = Frame.encode(new Cancel(7));
        byte[] padded = new byte[f.length + 10];
        System.arraycopy(f, 0, padded, 5, f.length);
        assertEquals(List.of(new Cancel(7)), new FrameDecoder().feed(padded, 5, f.length));
        assertThrows(IndexOutOfBoundsException.class, () -> new FrameDecoder().feed(f, 1, f.length));
    }

    @Test
    void partialFrameYieldsNothingYet() {
        byte[] f = Frame.encode(new Task(1, 2, 3, 4));
        FrameDecoder d = new FrameDecoder();
        assertEquals(List.of(), d.feed(f, 0, 3));
        assertEquals(List.of(), d.feed(f, 3, f.length - 4));
        assertEquals(List.of(new Task(1, 2, 3, 4)), d.feed(f, f.length - 1, 1));
    }

    // ---- 2b. 每条消息在线上的字节数（调度 11-09：fish 第 7 步按线上字节数算传输时间） ----

    @Test
    void feedFramesReportsWireBytesPerMessage() {
        // 线上字节数 = 长度前缀里的 N + 4 字节头 = Frame.encode 出来的长度
        for (Message m : all()) {
            byte[] f = Frame.encode(m);
            assertEquals(List.of(new FrameDecoder.Decoded(m, f.length)), new FrameDecoder().feedFrames(f, 0, f.length));
        }
        byte[] tile = Frame.encode(Result.success(1, TILE));
        FrameDecoder.Decoded d = new FrameDecoder().feedFrames(tile, 0, tile.length).get(0);
        assertEquals(ByteBuffer.wrap(tile).getInt() + 4, d.wireBytes());
    }

    @Test
    void feedFramesSeparatesFramesInOneFeedAndAcrossFeeds() {
        byte[] a = Frame.encode(new Cancel(1)), b = Frame.encode(Result.success(2, TILE)), c = Frame.encode(new Heartbeat(5, 1));
        byte[] stream = concat(List.of(a, b, c));
        List<FrameDecoder.Decoded> want = List.of(new FrameDecoder.Decoded(new Cancel(1), a.length),
                new FrameDecoder.Decoded(Result.success(2, TILE), b.length), new FrameDecoder.Decoded(new Heartbeat(5, 1), c.length));
        assertEquals(want, new FrameDecoder().feedFrames(stream, 0, stream.length));
        // 任意拆块：每条的字节数照样对，和怎么到达无关
        Random rng = new Random(7);
        FrameDecoder d = new FrameDecoder();
        List<FrameDecoder.Decoded> got = new ArrayList<>();
        for (int pos = 0; pos < stream.length; ) {
            int n = Math.min(stream.length - pos, 1 + rng.nextInt(50_000));
            got.addAll(d.feedFrames(stream, pos, n));
            pos += n;
        }
        assertEquals(want, got);
    }

    @Test
    void feedAndFeedFramesShareStateAndFailure() {
        // feed 照旧只返回消息；两个入口可以混用（同一个缓冲）；出错后两个都不能再用
        byte[] f = Frame.encode(new Task(1, 2, 3, 4));
        FrameDecoder d = new FrameDecoder();
        assertEquals(List.of(), d.feed(f, 0, 5));
        assertEquals(List.of(new FrameDecoder.Decoded(new Task(1, 2, 3, 4), f.length)), d.feedFrames(f, 5, f.length - 5));
        FrameDecoder bad = new FrameDecoder();
        byte[] junk = ByteBuffer.allocate(4).putInt(-1).array();
        assertThrows(ProtocolException.class, () -> bad.feedFrames(junk, 0, 4));
        assertThrows(ProtocolException.class, () -> bad.feed(f, 0, f.length));
    }

    // ---- 3. 长度超限：只看 4 字节前缀就抛，不等数据、不分配 ----

    @Test
    void oversizedLengthPrefixRejectedImmediately() {
        for (int n : new int[] {Frame.MAX_BYTES + 1, Integer.MAX_VALUE, -1, Integer.MIN_VALUE, 0}) {
            byte[] f = ByteBuffer.allocate(4).putInt(n).array();
            assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, 4), "长度 " + n);
        }
    }

    @Test
    void lengthAtLimitIsAcceptedUntilDataArrives() {
        byte[] f = ByteBuffer.allocate(4).putInt(Frame.MAX_BYTES).array();
        assertEquals(List.of(), new FrameDecoder().feed(f, 0, 4));
    }

    @Test
    void encoderRefusesOversizedMessage() {
        byte[] noise = new byte[Frame.MAX_BYTES + 1024];
        new Random(7).nextBytes(noise);
        CompoundTag tile = new CompoundTag();
        tile.put("noise", new ByteArrayTag(noise));
        assertThrows(ProtocolException.class, () -> Frame.encode(Result.success(1, tile)));
    }

    // ---- 4. 消息体坏了 ----

    @Test
    void bodyNotGzip() {
        byte[] f = frameOf("this is not gzip".getBytes());
        assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
    }

    @Test
    void gzipButNotNbt() throws IOException {
        byte[] f = frameOf(gzip(new byte[] {(byte) 0xff, 1, 2, 3, 4, 5}));
        assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
    }

    @Test
    void truncatedGzip() {
        byte[] good = Frame.encode(Result.success(1, TILE));
        byte[] body = new byte[(good.length - 4) / 2];
        System.arraycopy(good, 4, body, 0, body.length);
        byte[] f = frameOf(body);
        assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
    }

    @Test
    void rootNotCompound() throws IOException {
        // NBT 根是 String 标签（类型 8）而不是 Compound。
        byte[] raw = {8, 0, 0, 0, 1, 'x'};
        byte[] f = frameOf(gzip(raw));
        assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
    }

    @Test
    void unknownOrMissingType() throws IOException {
        CompoundTag unknown = new CompoundTag();
        unknown.putString("type", "nope");
        CompoundTag missing = new CompoundTag();
        missing.putLong("taskId", 1);
        CompoundTag wrongType = new CompoundTag();
        wrongType.putInt("type", 1);
        for (CompoundTag t : List.of(unknown, missing, wrongType)) {
            assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
            byte[] f = frameOf(compressed(t));
            assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
        }
    }

    /** 可以缺的字段（缺了有默认值）。 */
    static final java.util.Set<String> OPTIONAL = java.util.Set.of("token", "kind", "paused", "worldId");

    // ---- 第 9 步：hello.token、welcome.workerId = -2 ----

    @Test
    void helloWithoutTokenFieldDecodesAsEmpty() {
        CompoundTag old = new CompoundTag();   // 旧版本 worker 发的 hello，没有 token
        old.putString("type", "hello");
        old.putInt("protocol", Protocol.VERSION);
        old.putString("name", "old-worker");
        assertEquals(new Hello(Protocol.VERSION, "old-worker", ""), MessageCodec.fromTag(old));
        assertEquals(new Hello(Protocol.VERSION, "old-worker"), MessageCodec.fromTag(old));
    }

    // ---- 11-09：hello.worldId（worker 一连上就报自己的 donor 世界，coordinator 不用等第一个结果）----

    @Test
    void helloWorldIdRoundTripsAndOldDecodesAsEmpty() {
        Hello h = new Hello(Protocol.VERSION, "w", "abc", "c99dcff6");
        assertEquals(h, MessageCodec.fromTag(MessageCodec.toTag(h)));
        assertEquals("c99dcff6", ((Hello) MessageCodec.fromTag(MessageCodec.toTag(h))).worldId());
        assertEquals("", new Hello(Protocol.VERSION, "w", "abc").worldId(), "三个参数的照旧，世界不知道");
        CompoundTag old = MessageCodec.toTag(new Hello(Protocol.VERSION, "old", "t"));
        old.remove("worldId");                 // 旧版本 worker：没有这个字段
        assertEquals("", ((Hello) MessageCodec.fromTag(old)).worldId());
        assertThrows(NullPointerException.class, () -> new Hello(1, "w", "", null));
    }

    @Test
    void tokenRoundTripsAndWrongTypeRejected() {
        Hello h = new Hello(Protocol.VERSION, "w", "abc");
        assertEquals(h, MessageCodec.fromTag(MessageCodec.toTag(h)));
        CompoundTag t = MessageCodec.toTag(h);
        t.putInt("token", 5);
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
        assertThrows(NullPointerException.class, () -> new Hello(1, "w", null));
    }

    @Test
    void welcomeRejectionCodes() {
        for (int id : new int[] {Welcome.BAD_VERSION, Welcome.BAD_TOKEN}) {
            Welcome w = new Welcome(Protocol.VERSION, id, 0, 0);
            byte[] f = Frame.encode(w);
            Welcome got = (Welcome) new FrameDecoder().feed(f, 0, f.length).get(0);
            assertEquals(id, got.workerId());
            assertTrue(!got.accepted());
        }
        assertEquals(-1, Welcome.BAD_VERSION);
        assertEquals(-2, Welcome.BAD_TOKEN);
        assertTrue(new Welcome(Protocol.VERSION, 0, 0, 0).accepted());
        assertEquals(1, Protocol.VERSION);
    }

    // ---- 第 8 步：task.kind（重算任务）、welcome.workerId = -3（拉黑） ----

    @Test
    void taskKindDefaultsToGenerateAndRoundTrips() {
        assertEquals(Task.Kind.GENERATE, new Task(1, 2, 3, 4).kind());
        Task r = new Task(7, -1, 9, 5_000, Task.Kind.RECOMPUTE);
        byte[] f = Frame.encode(r);
        assertEquals(List.of(r), new FrameDecoder().feed(f, 0, f.length));
        assertEquals("recompute", MessageCodec.toTag(r).getString("kind"));
        assertThrows(NullPointerException.class, () -> new Task(1, 2, 3, 4, null));
    }

    @Test
    void taskWithoutKindFieldIsGenerate() {
        CompoundTag old = new CompoundTag();   // 旧版本 coordinator 发的 task，没有 kind
        old.putString("type", "task");
        old.putLong("taskId", 5);
        old.putInt("tx", 1);
        old.putInt("tz", 2);
        old.putLong("leaseMs", 30_000);
        assertEquals(new Task(5, 1, 2, 30_000), MessageCodec.fromTag(old));
        assertEquals(Task.Kind.GENERATE, ((Task) MessageCodec.fromTag(old)).kind());
    }

    @Test
    void taskUnknownOrWrongTypedKindRejected() {
        CompoundTag t = MessageCodec.toTag(new Task(1, 2, 3, 4));
        t.putString("kind", "mine-diamonds");
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
        t.putInt("kind", 1);
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
        t.putString("kind", "RECOMPUTE");   // 只认小写，和 type 一样
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
    }

    @Test
    void welcomeBlacklistedCode() {
        assertEquals(-3, Welcome.BLACKLISTED);
        Welcome w = new Welcome(Protocol.VERSION, Welcome.BLACKLISTED, 0, 0);
        byte[] f = Frame.encode(w);
        Welcome got = (Welcome) new FrameDecoder().feed(f, 0, f.length).get(0);
        assertEquals(-3, got.workerId());
        assertTrue(!got.accepted());
    }

    // ---- 第 9 步：heartbeat.paused（限速暂停，调度 11-09 定：单独的字段，不占 taskId） ----

    @Test
    void heartbeatPausedDefaultsFalseAndKeepsTask() {
        assertFalse(new Heartbeat(1, 5).paused());
        Heartbeat h = new Heartbeat(2, 7, true);   // 暂停中手上还有任务：两样都要报出来
        byte[] f = Frame.encode(h);
        Heartbeat got = (Heartbeat) new FrameDecoder().feed(f, 0, f.length).get(0);
        assertTrue(got.paused());
        assertEquals(7, got.taskId());
        assertTrue(new Heartbeat(3, Heartbeat.NO_TASK, true).paused());
    }

    @Test
    void heartbeatWithoutPausedFieldIsNotPaused() {
        CompoundTag old = new CompoundTag();   // 旧版本 worker 发的心跳，没有 paused
        old.putString("type", "heartbeat");
        old.putLong("sentAtMs", 10);
        old.putLong("taskId", 3);
        assertEquals(new Heartbeat(10, 3), MessageCodec.fromTag(old));
        assertFalse(((Heartbeat) MessageCodec.fromTag(old)).paused());
    }

    @Test
    void heartbeatPausedWrongTypeRejected() {
        CompoundTag t = MessageCodec.toTag(new Heartbeat(1, 2, true));
        t.putString("paused", "yes");
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
        t.putByte("paused", (byte) 2);
        assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
    }

    @Test
    void helloToStringHidesToken() {
        String s = new Hello(1, "w", "s3cret").toString();
        assertTrue(!s.contains("s3cret") && s.contains("***"), s);
    }

    @Test
    void everyMissingFieldRejected() {
        for (Message m : all()) {
            CompoundTag full = MessageCodec.toTag(m);
            for (String key : full.getAllKeys()) {
                if (OPTIONAL.contains(key)) {
                    continue;
                }
                CompoundTag t = full.copy();
                t.remove(key);
                assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t),
                        m.getClass().getSimpleName() + " 缺 " + key);
            }
        }
    }

    @Test
    void everyWrongFieldTypeRejected() {
        for (Message m : all()) {
            CompoundTag full = MessageCodec.toTag(m);
            for (String key : full.getAllKeys()) {
                if (key.equals("type")) {
                    continue;
                }
                CompoundTag t = full.copy();
                // 统一换成 String；本来就是 String 的换成 Int。
                t.put(key, full.get(key) instanceof StringTag ? IntTag.valueOf(1) : StringTag.valueOf("1"));
                assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t),
                        m.getClass().getSimpleName() + " 的 " + key + " 类型不对");
            }
        }
    }

    @Test
    void inconsistentResultRejected() {
        CompoundTag okWithError = MessageCodec.toTag(Result.success(1, TILE));
        okWithError.putString("error", "怎么又成功又有错");
        CompoundTag failWithTile = MessageCodec.toTag(Result.failure(1, "x"));
        failWithTile.put("tile", TILE);
        CompoundTag badBool = MessageCodec.toTag(Result.failure(1, "x"));
        badBool.putByte("ok", (byte) 2);
        for (CompoundTag t : List.of(okWithError, failWithTile, badBool)) {
            assertThrows(ProtocolException.class, () -> MessageCodec.fromTag(t));
        }
        assertThrows(IllegalArgumentException.class, () -> new Result(1, true, "x", TILE));
        assertThrows(NullPointerException.class, () -> new Hello(1, null));
    }

    @Test
    void extraFieldsIgnored() {
        CompoundTag t = MessageCodec.toTag(new Cancel(3));
        t.putString("future", "以后加的字段");
        assertEquals(new Cancel(3), MessageCodec.fromTag(t));
    }

    @Test
    void decoderUnusableAfterError() {
        FrameDecoder d = new FrameDecoder();
        byte[] bad = frameOf("garbage".getBytes());
        assertThrows(ProtocolException.class, () -> d.feed(bad, 0, bad.length));
        byte[] good = Frame.encode(new Cancel(1));
        assertThrows(ProtocolException.class, () -> d.feed(good, 0, good.length));
    }

    // ---- 5. 压缩炸弹 ----

    @Test
    void compressionBombRejected() throws IOException {
        CompoundTag bomb = new CompoundTag();
        bomb.putString("type", "result");
        bomb.putLong("taskId", 1);
        bomb.putBoolean("ok", true);
        bomb.putString("error", "");
        CompoundTag tile = new CompoundTag();
        tile.put("zeros", new ByteArrayTag(new byte[80 * 1024 * 1024]));
        bomb.put("tile", tile);
        byte[] body = compressed(bomb);
        assertTrue(body.length < Frame.MAX_BYTES, "压缩后只有 " + body.length + " 字节，能过长度检查");
        byte[] f = frameOf(body);
        ProtocolException e = assertThrows(ProtocolException.class, () -> new FrameDecoder().feed(f, 0, f.length));
        assertInstanceOf(RuntimeException.class, e.getCause());
    }

    @Test
    void justUnderNbtLimitStillDecodes() throws IOException {
        CompoundTag tile = new CompoundTag();
        tile.put("zeros", new ByteArrayTag(new byte[32 * 1024 * 1024]));
        Result r = Result.success(1, tile);
        byte[] f = Frame.encode(r);
        assertEquals(List.of(r), new FrameDecoder().feed(f, 0, f.length));
    }
}
