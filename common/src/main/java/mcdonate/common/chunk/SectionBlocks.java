package mcdonate.common.chunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一个 section 的 block_states（1.21.1 存档格式）：palette + 打包的 long 数组，下标 = y*256 + z*16 + x。
 * 每个 long 放 64/bits 个值，不跨 long；bits = max(4, ⌈log2(palette 大小)⌉)；palette 只有一项时没有 data。
 */
public final class SectionBlocks {
    private final List<CompoundTag> palette = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> keys = new ArrayList<>();
    private final int[] cells = new int[4096];

    public static SectionBlocks decode(CompoundTag blockStates) {
        SectionBlocks s = new SectionBlocks();
        ListTag list = blockStates.getList("palette", Tag.TAG_COMPOUND);
        if (list.isEmpty()) throw new IllegalArgumentException("block_states 没有 palette");
        for (int i = 0; i < list.size(); i++) s.intern(list.getCompound(i).copy());
        if (list.size() == 1) return s;
        long[] data = blockStates.getLongArray("data");
        int bits = bits(list.size()), perLong = 64 / bits, mask = (1 << bits) - 1;
        if (data.length != (4096 + perLong - 1) / perLong) throw new IllegalArgumentException("block_states data 长度不符");
        for (int i = 0; i < 4096; i++) {
            int v = (int) (data[i / perLong] >>> ((i % perLong) * bits)) & mask;
            if (v >= list.size()) throw new IllegalArgumentException("palette 下标越界");
            s.cells[i] = v;
        }
        return s;
    }

    /** 写回 block_states：去掉没用到的 palette 项，按 palette 大小重新打包。 */
    public CompoundTag encode() {
        int[] remap = new int[palette.size()];
        java.util.Arrays.fill(remap, -1);
        ListTag out = new ListTag();
        for (int i = 0; i < 4096; i++) {
            int v = cells[i];
            if (remap[v] < 0) {
                remap[v] = out.size();
                out.add(palette.get(v).copy());
            }
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", out);
        if (out.size() > 1) {
            int bits = bits(out.size()), perLong = 64 / bits;
            long[] data = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) data[i / perLong] |= (long) remap[cells[i]] << ((i % perLong) * bits);
            tag.putLongArray("data", data);
        }
        return tag;
    }

    public String name(int x, int y, int z) {
        return palette.get(cells[cell(x, y, z)]).getString("Name");
    }

    /** 这个位置的方块状态键（名字 + 排好序的属性），不复制 NBT。 */
    public String keyAt(int x, int y, int z) {
        return keys.get(cells[cell(x, y, z)]);
    }

    public CompoundTag state(int x, int y, int z) {
        return palette.get(cells[cell(x, y, z)]).copy();
    }

    public void set(int x, int y, int z, CompoundTag state) {
        cells[cell(x, y, z)] = intern(state);
    }

    private int intern(CompoundTag state) {
        return index.computeIfAbsent(key(state), k -> {
            palette.add(state.copy());
            keys.add(k);
            return palette.size() - 1;
        });
    }

    /** 方块名加排好序的属性，用来判断两个 palette 项是不是同一个方块状态。 */
    public static String key(CompoundTag state) {
        StringBuilder b = new StringBuilder(state.getString("Name"));
        if (state.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = state.getCompound("Properties");
            TreeMap<String, String> sorted = new TreeMap<>();
            for (String k : props.getAllKeys()) sorted.put(k, props.getString(k));
            b.append(sorted);
        }
        return b.toString();
    }

    public static CompoundTag block(String name) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", name);
        return t;
    }

    private static int cell(int x, int y, int z) {
        if ((x | y | z) >>> 4 != 0) throw new IndexOutOfBoundsException("section 内坐标越界");
        return y * 256 + z * 16 + x;
    }

    private static int bits(int size) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(size - 1));
    }
}
