package mcdonate.coordinator;

import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;

/**
 * 服务器的"前沿"：玩家前进方向上，服务器已经占住（有 holder）的 chunk 最远到第几圈。纯逻辑。
 *
 * <p>11-09 kexu 演示 B 轮（2 核、鞘翅 30 格/秒）：被接受的 tile 交回时区域离玩家都 ≥ 14 圈，被丢的 ≤ 13 圈，
 * 而找需求的 inner 是视距 + 11 + 1 = 22。服务器忙的时候 holder 跟不上视距（原版给玩家加 ticket 有节流），
 * 真正"来不及"的边界是前沿，不是 inner。前沿随负载变，所以每次扫描实测。
 */
final class Frontier {
    private Frontier() {}

    /**
     * 从玩家所在 chunk 沿速度方向一圈一圈往外看（每圈取方向上最近的那个 chunk），返回最远有 holder 的圈数，
     * 最多看到 max；不动返回 -1。只看一条线：前沿在正前方最要紧，侧面的 tile 本来就晚到。
     */
    static int ahead(int pcx, int pcz, double vx, double vz, int max, LongPredicate hasHolder) {
        double len = Math.max(Math.abs(vx), Math.abs(vz));
        if (len == 0) {
            return -1;
        }
        double ux = vx / len, uz = vz / len; // 切比雪夫单位：每走一圈，至少一个方向走满 1
        int best = 0;
        for (int d = 1; d <= max; d++) {
            int x = pcx + (int) Math.round(ux * d), z = pcz + (int) Math.round(uz * d);
            if (hasHolder.test(ChunkPos.asLong(x, z))) {
                best = d;
            }
        }
        return best;
    }
}
