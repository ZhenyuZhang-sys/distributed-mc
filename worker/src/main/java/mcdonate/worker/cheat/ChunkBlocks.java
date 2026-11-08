package mcdonate.worker.cheat;

import java.util.Map;
import java.util.TreeMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 一个 chunk 的 NBT 里所有 section 的方块，按世界 y 读写；write() 把改过的 section 写回原来的 CompoundTag。 */
final class ChunkBlocks {
    final CompoundTag chunk;
    final int cx, cz;
    private final TreeMap<Integer, SectionBlocks> sections = new TreeMap<>();
    private final Map<Integer, CompoundTag> raw = new TreeMap<>();

    ChunkBlocks(CompoundTag chunk) {
        this.chunk = chunk;
        this.cx = chunk.getInt("xPos");
        this.cz = chunk.getInt("zPos");
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            if (!s.contains("block_states", Tag.TAG_COMPOUND)) continue; // 只有光照的 section
            int sy = s.getByte("Y");
            sections.put(sy, SectionBlocks.decode(s.getCompound("block_states")));
            raw.put(sy, s);
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("chunk 没有方块 section");
    }

    int minY() {
        return sections.firstKey() * 16;
    }

    /** 最高一格 + 1。 */
    int maxY() {
        return (sections.lastKey() + 1) * 16;
    }

    boolean has(int y) {
        return sections.containsKey(Math.floorDiv(y, 16));
    }

    String name(int x, int y, int z) {
        return section(y).name(x, Math.floorMod(y, 16), z);
    }

    CompoundTag state(int x, int y, int z) {
        return section(y).state(x, Math.floorMod(y, 16), z);
    }

    void set(int x, int y, int z, CompoundTag state) {
        section(y).set(x, Math.floorMod(y, 16), z, state);
    }

    void write() {
        sections.forEach((sy, s) -> raw.get(sy).put("block_states", s.encode()));
    }

    private SectionBlocks section(int y) {
        SectionBlocks s = sections.get(Math.floorDiv(y, 16));
        if (s == null) throw new IllegalArgumentException("y=" + y + " 不在 chunk 的 section 里");
        return s;
    }
}
