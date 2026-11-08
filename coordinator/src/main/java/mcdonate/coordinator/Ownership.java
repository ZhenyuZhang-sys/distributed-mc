package mcdonate.coordinator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主世界里每个导入的 chunk 来自哪个 donor 世界（docs/step6-plan.md 0.2）。只在内存里。
 *
 * <p>服务器自己存这个 chunk 时清掉（ChunkMapSaveMixin）：存盘说明服务器加载过、可能改过它，
 * 之后就当成服务器的。服务器重启后全部丢失，那些 chunk 一律当服务器的。
 */
public final class Ownership {
    private static final Map<Long, String> OWNERS = new ConcurrentHashMap<>();

    private Ownership() {}

    static void record(long chunkPos, String world) {
        OWNERS.put(chunkPos, world);
    }

    static String owner(long chunkPos) {
        return OWNERS.get(chunkPos);
    }

    /** 服务器存主世界的 chunk 时调。 */
    public static void forget(long chunkPos) {
        OWNERS.remove(chunkPos);
    }
}
