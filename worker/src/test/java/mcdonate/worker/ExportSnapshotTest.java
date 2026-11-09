package mcdonate.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import org.junit.jupiter.api.Test;

/**
 * 11-09（codex-audit 第九节 P1）：ChunkSerializer.write 出来的 NBT 和活的 chunk 共用数组（高度图 getRawData 直接给 LongArrayTag，
 * 光照 DataLayer.getData 直接 putByteArray），be41a83 把编码挪到发送线程以后，相邻 tile 接着生成时这些数组会被改，
 * 发出去的是悄悄撕裂的数据。导出时要在主线程上就深拷贝。
 */
class ExportSnapshotTest {
    @Test
    void snapshotDoesNotShareArraysWithTheLiveChunk() {
        long[] live = {1, 2, 3};
        CompoundTag fromSerializer = new CompoundTag();
        fromSerializer.put("Heightmaps", new LongArrayTag(live));   // 和 ChunkSerializer 一样，不复制
        CompoundTag snap = TileExporter.snapshot(fromSerializer);
        live[0] = 99;                                               // 生成线程接着改了 chunk
        assertArrayEquals(new long[] {1, 2, 3}, ((LongArrayTag) snap.get("Heightmaps")).getAsLongArray());
    }
}
