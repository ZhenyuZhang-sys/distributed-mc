package mcdonate.coordinator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 导入账本（7.7，第 8 步也用）：主世界里每个导入的 chunk 来自哪个 donor（稳定身份）、哪个 tile、
 * 什么时候写入、服务器有没有加载过。
 *
 * <p>donor 身份：第 9 步以前是 worker 编号 + donor 世界编号；第 9 步以后加玩家 UUID。不存 token：
 * token 是一次性的（用过就作废，认不出是谁），而且是凭证，不该写进磁盘（kexu 11-09 核对）。
 *
 * <p>按 region（32×32 chunk）存一个文件：世界目录下 {@code mcdonate/ledger/r.X.Z.dat}（gzip，自己的二进制格式，
 * 不依赖 Minecraft 类，单元测试能直接测）。改动先记成"脏"的 region，{@link #flush} 时写盘（定时和关服时调）；
 * 写临时文件再改名，写一半断电不会留下坏文件。服务器启动时 {@link #load} 读回来。
 *
 * <p>归属（第 6 步的接缝规则）：有记录、而且服务器还没加载过的 chunk 归它的 donor 世界；
 * 服务器存过它（说明加载过、可能改过）就算服务器的。原来只在内存里，重启后全部当服务器的（第 6 步 0.2），
 * 现在重启后还在。
 *
 * <p>钩子（给第 8 步）：导入成功、某个 chunk 第一次被服务器加载（存盘时发现），各可以挂监听。
 * 线程：导入和存盘都在主线程上；表用并发结构，命令和网络线程读也安全。
 */
public final class ImportLedger {
    /** 一个导入的 chunk。 */
    public static final class Entry {
        public final String world;
        public final int tileX;
        public final int tileZ;
        /** 哪个 worker（连接编号；重连会变，配合 world 认人）；不知道是 -1。 */
        public final int workerId;
        /** donor 的玩家 UUID（第 9 步起）；之前是空串。 */
        public final String player;
        public final long importedAt;
        volatile boolean serverLoaded;

        Entry(String world, int tileX, int tileZ, int workerId, String player, long importedAt, boolean serverLoaded) {
            this.world = world;
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.workerId = workerId;
            this.player = player;
            this.importedAt = importedAt;
            this.serverLoaded = serverLoaded;
        }

        public boolean serverLoaded() {
            return serverLoaded;
        }
    }

    /** 2：donor 身份（workerId、玩家 UUID）代替 token。读得了 1（token 读出来扔掉）。 */
    private static final int FORMAT = 2;
    private static final Pattern FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.dat");

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Imported>> importListeners = new CopyOnWriteArrayList<>();
    private final List<FirstLoadListener> firstLoadListeners = new CopyOnWriteArrayList<>();

    /** 一次导入写进去的 chunk，带 donor 身份（第 8 步的验证入口用）。 */
    public record Imported(String world, int tileX, int tileZ, int workerId, String player, List<Long> chunks) {}

    /** chunk 第一次被服务器加载。 */
    public interface FirstLoadListener {
        void firstLoad(long chunk, Entry entry);
    }

    public void onImport(Consumer<Imported> l) {
        importListeners.add(l);
    }

    public void onFirstServerLoad(FirstLoadListener l) {
        firstLoadListeners.add(l);
    }

    private static long chunkKey(int x, int z) {
        return ((long) z << 32) | (x & 0xffffffffL); // 和 ChunkPos.asLong 一样
    }

    private static int chunkX(long key) {
        return (int) key;
    }

    private static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    private static long regionOf(long chunk) {
        return chunkKey(chunkX(chunk) >> 5, chunkZ(chunk) >> 5);
    }

    /** 一次导入写进去的 chunk（导入成功后调一次）。player 第 9 步以前传空串。 */
    public void recordImport(String world, int tileX, int tileZ, int workerId, String player, List<Long> chunks, long now) {
        String p = player == null ? "" : player;
        for (long c : chunks) {
            entries.put(c, new Entry(world, tileX, tileZ, workerId, p, now, false));
            dirtyRegions.add(regionOf(c));
        }
        Imported imp = new Imported(world, tileX, tileZ, workerId, p, List.copyOf(chunks));
        importListeners.forEach(l -> l.accept(imp));
    }

    /**
     * 服务器加载过这个 chunk（变成成品时，或存盘时）：第一次的话标成"服务器加载过"并通知监听。
     * 可能在生成线程上调，监听器要线程安全。
     */
    public void serverSaved(long chunk) {
        Entry e = entries.get(chunk);
        if (e == null || e.serverLoaded) {
            return;
        }
        e.serverLoaded = true;
        dirtyRegions.add(regionOf(chunk));
        firstLoadListeners.forEach(l -> l.firstLoad(chunk, e));
    }

    /** 接缝规则用：服务器没加载过的导入 chunk 归它的 donor 世界，其他返回 null。 */
    public String owner(long chunk) {
        Entry e = entries.get(chunk);
        return e == null || e.serverLoaded ? null : e.world;
    }

    public Entry get(long chunk) {
        return entries.get(chunk);
    }

    public int size() {
        return entries.size();
    }

    /** 第 8 步回滚：这个 chunk 已经从存档删掉，账本里也去掉。 */
    public void remove(long chunk) {
        if (entries.remove(chunk) != null) {
            dirtyRegions.add(regionOf(chunk));
        }
    }

    /** 第 8 步：某个 donor（donor 世界 + worker 编号；workerId < 0 表示这个世界的全部）导入过的 chunk。 */
    public List<Long> chunksOf(String world, int workerId) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            if (v.world.equals(world) && (workerId < 0 || v.workerId == workerId)) {
                out.add(e.getKey());
            }
        }
        out.sort(null);
        return out;
    }

    /** 把改过的 region 写盘。 */
    public synchronized void flush(Path dir) throws IOException {
        if (dirtyRegions.isEmpty()) {
            return;
        }
        Files.createDirectories(dir);
        Set<Long> regions = new HashSet<>(dirtyRegions);
        dirtyRegions.removeAll(regions);
        Map<Long, List<Map.Entry<Long, Entry>>> byRegion = new HashMap<>();
        for (Map.Entry<Long, Entry> e : entries.entrySet()) {
            long r = regionOf(e.getKey());
            if (regions.contains(r)) {
                byRegion.computeIfAbsent(r, k -> new ArrayList<>()).add(e);
            }
        }
        for (long r : regions) {
            Path file = dir.resolve("r." + chunkX(r) + "." + chunkZ(r) + ".dat");
            List<Map.Entry<Long, Entry>> list = byRegion.getOrDefault(r, List.of());
            if (list.isEmpty()) {
                Files.deleteIfExists(file);
                continue;
            }
            Path tmp = dir.resolve(file.getFileName() + ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os))) {
                write(out, list);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static void write(DataOutputStream out, List<Map.Entry<Long, Entry>> list) throws IOException {
        out.writeInt(FORMAT);
        out.writeInt(list.size());
        for (Map.Entry<Long, Entry> e : list) {
            Entry v = e.getValue();
            out.writeLong(e.getKey());
            out.writeUTF(v.world);
            out.writeInt(v.tileX);
            out.writeInt(v.tileZ);
            out.writeInt(v.workerId);
            out.writeUTF(v.player);
            out.writeLong(v.importedAt);
            out.writeBoolean(v.serverLoaded);
        }
    }

    /** 启动时读回来（会清掉内存里原有的）。返回读到的 chunk 数。 */
    public synchronized int load(Path dir) throws IOException {
        entries.clear();
        dirtyRegions.clear();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher m = FILE.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                try (InputStream is = Files.newInputStream(f);
                     DataInputStream in = new DataInputStream(new GZIPInputStream(is))) {
                    int format = in.readInt();
                    if (format != 1 && format != FORMAT) {
                        throw new IOException(f + "：不认识的格式 " + format);
                    }
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) {
                        long key = in.readLong();
                        String world = in.readUTF();
                        int tx = in.readInt(), tz = in.readInt();
                        int workerId = -1;
                        String player = "";
                        if (format == 1) {
                            in.readUTF(); // 旧格式的 token：不要了
                        } else {
                            workerId = in.readInt();
                            player = in.readUTF();
                        }
                        entries.put(key, new Entry(world, tx, tz, workerId, player, in.readLong(), in.readBoolean()));
                    }
                }
            }
        }
        return entries.size();
    }
}
