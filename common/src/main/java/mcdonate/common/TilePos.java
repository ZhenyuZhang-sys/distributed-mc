package mcdonate.common;

/**
 * tile 坐标：一个 tile 是 SIZE×SIZE 个 chunk（默认 4，配置项 tileSize；donor 和服务器必须一样）。
 * tile (tx, tz) 包含 chunk x ∈ [4tx, 4tx+3]、z ∈ [4tz, 4tz+3]；负数向下取整。
 */
public record TilePos(int x, int z) {
    public static final int SIZE = Config.getInt("tileSize", 4);

    public static TilePos ofChunk(int chunkX, int chunkZ) {
        return ofChunk(chunkX, chunkZ, SIZE);
    }

    /** 7.5：tile 大小作参数的版本（单元测试按 1、2、4、8 测；运行时用配置的 SIZE）。 */
    public static TilePos ofChunk(int chunkX, int chunkZ, int size) {
        return new TilePos(Math.floorDiv(chunkX, size), Math.floorDiv(chunkZ, size));
    }

    public static int minChunk(int t, int size) {
        return t * size;
    }

    public static int maxChunk(int t, int size) {
        return t * size + size - 1;
    }

    /** tile 中心（chunk 坐标，可以是半格）。 */
    public static double center(int t, int size) {
        return t * size + (size - 1) / 2.0;
    }

    public int minChunkX() {
        return minChunk(x, SIZE);
    }

    public int maxChunkX() {
        return maxChunk(x, SIZE);
    }

    public int minChunkZ() {
        return minChunk(z, SIZE);
    }

    public int maxChunkZ() {
        return maxChunk(z, SIZE);
    }

    /** chunk 在第几圈：tile 内是 0，紧挨着的一圈是 1，依此类推（按切比雪夫距离）。 */
    public int ring(int chunkX, int chunkZ) {
        int dx = Math.max(0, Math.max(minChunkX() - chunkX, chunkX - maxChunkX()));
        int dz = Math.max(0, Math.max(minChunkZ() - chunkZ, chunkZ - maxChunkZ()));
        return Math.max(dx, dz);
    }
}
