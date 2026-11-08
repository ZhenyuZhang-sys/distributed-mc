package mcdonate.common;

/**
 * tile 坐标：一个 tile 是 4×4 个 chunk。
 * tile (tx, tz) 包含 chunk x ∈ [4tx, 4tx+3]、z ∈ [4tz, 4tz+3]；负数向下取整。
 */
public record TilePos(int x, int z) {
    public static final int SIZE = 4;

    public static TilePos ofChunk(int chunkX, int chunkZ) {
        return new TilePos(Math.floorDiv(chunkX, SIZE), Math.floorDiv(chunkZ, SIZE));
    }

    public int minChunkX() {
        return x * SIZE;
    }

    public int maxChunkX() {
        return x * SIZE + SIZE - 1;
    }

    public int minChunkZ() {
        return z * SIZE;
    }

    public int maxChunkZ() {
        return z * SIZE + SIZE - 1;
    }

    /** chunk 在第几圈：tile 内是 0，紧挨着的一圈是 1，依此类推（按切比雪夫距离）。 */
    public int ring(int chunkX, int chunkZ) {
        int dx = Math.max(0, Math.max(minChunkX() - chunkX, chunkX - maxChunkX()));
        int dz = Math.max(0, Math.max(minChunkZ() - chunkZ, chunkZ - maxChunkZ()));
        return Math.max(dx, dz);
    }
}
