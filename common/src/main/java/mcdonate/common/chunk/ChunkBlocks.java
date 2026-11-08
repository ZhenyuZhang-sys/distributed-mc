package mcdonate.common.chunk;

import java.util.Map;
import java.util.TreeMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 一个 chunk 的 NBT 里所有 section 的方块，按世界 y 读写；write() 把改过的 section 写回原来的 CompoundTag。 */
public final class ChunkBlocks {
    public final CompoundTag chunk;
    public final int cx, cz;
    private final TreeMap<Integer, SectionBlocks> sections = new TreeMap<>();
    private final Map<Integer, CompoundTag> raw = new TreeMap<>();

    public ChunkBlocks(CompoundTag chunk) {
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

    public int minY() {
        return sections.firstKey() * 16;
    }

    /** 最高一格 + 1。 */
    public int maxY() {
        return (sections.lastKey() + 1) * 16;
    }

    public boolean has(int y) {
        return sections.containsKey(Math.floorDiv(y, 16));
    }

    public String name(int x, int y, int z) {
        return section(y).name(x, Math.floorMod(y, 16), z);
    }

    public String key(int x, int y, int z) {
        return section(y).keyAt(x, Math.floorMod(y, 16), z);
    }

    public CompoundTag state(int x, int y, int z) {
        return section(y).state(x, Math.floorMod(y, 16), z);
    }

    public void set(int x, int y, int z, CompoundTag state) {
        section(y).set(x, Math.floorMod(y, 16), z, state);
    }

    /** 一个 section 的 64 个生物群系（4×4×4，下标 y*16 + z*4 + x），没有这个 section 返回 null。 */
    public String[] biomes(int sectionY) {
        CompoundTag s = raw.get(sectionY);
        if (s == null || !s.contains("biomes", Tag.TAG_COMPOUND)) return null;
        CompoundTag b = s.getCompound("biomes");
        ListTag palette = b.getList("palette", Tag.TAG_STRING);
        if (palette.isEmpty()) throw new IllegalArgumentException("biomes 没有 palette");
        String[] out = new String[64];
        if (palette.size() == 1) {
            java.util.Arrays.fill(out, palette.getString(0));
            return out;
        }
        int bits = 32 - Integer.numberOfLeadingZeros(palette.size() - 1), perLong = 64 / bits, mask = (1 << bits) - 1;
        long[] data = b.getLongArray("data");
        if (data.length != (64 + perLong - 1) / perLong) throw new IllegalArgumentException("biomes data 长度不符");
        for (int i = 0; i < 64; i++) {
            int v = (int) (data[i / perLong] >>> ((i % perLong) * bits)) & mask;
            if (v >= palette.size()) throw new IllegalArgumentException("biome 下标越界");
            out[i] = palette.getString(v);
        }
        return out;
    }

    /** 有方块数据的 section 的 Y（从小到大）。 */
    public java.util.Set<Integer> sectionYs() {
        return java.util.Collections.unmodifiableSet(sections.keySet());
    }

    public void write() {
        sections.forEach((sy, s) -> raw.get(sy).put("block_states", s.encode()));
    }

    private SectionBlocks section(int y) {
        SectionBlocks s = sections.get(Math.floorDiv(y, 16));
        if (s == null) throw new IllegalArgumentException("y=" + y + " 不在 chunk 的 section 里");
        return s;
    }
}
