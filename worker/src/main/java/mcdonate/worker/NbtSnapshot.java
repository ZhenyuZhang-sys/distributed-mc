package mcdonate.worker;

import net.minecraft.nbt.CompoundTag;

/**
 * 导出的 chunk NBT 的深拷贝（11-09，codex-audit 第九节 P1）：ChunkSerializer.write 的高度图、光照数组、实体标签和活 chunk 共用，
 * 结果在发送线程上编码时相邻 tile 可能已经接着生成、改了它们。在主线程上、导出的安全窗口里拷。
 */
final class NbtSnapshot {
    private NbtSnapshot() {}

    static CompoundTag of(CompoundTag serialized) {
        return serialized.copy();
    }
}
