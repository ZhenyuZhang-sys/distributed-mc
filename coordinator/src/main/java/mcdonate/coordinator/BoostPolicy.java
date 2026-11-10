package mcdonate.coordinator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mcdonate.common.Config;
import mcdonate.common.TilePos;

/**
 * 11-09 加速模式（用户：Mac 的 CPU 很闲，希望 donor 空闲时也一直算）。纯逻辑。
 * <ul>
 *   <li>开了加速的 donor 在途上限按它报的生成线程数（threads ÷ boostInFlightDivisor，最多 boostMaxInFlight），不比原来少。
 *   <li>后台任务最多占它一半的名额，前方的任务来了马上有空位。
 *   <li>后台 tile 的候选顺序像 Chunky：以每个中心（在线玩家、预设中心）一圈一圈往外。
 * </ul>
 */
final class BoostPolicy {
    private BoostPolicy() {}

    static final int MAX_IN_FLIGHT = Config.getInt("boostMaxInFlight", 8);
    static final int DIVISOR = Math.max(1, Config.getInt("boostInFlightDivisor", 1));

    /** normal：不开加速时的在途上限（实测算的）；开了加速按线程数，不比 normal 少；不知道线程数（0）照旧。 */
    static int maxInFlight(int normal, boolean boost, int threads) {
        if (!boost || threads <= 0) {
            return normal;
        }
        return Math.max(normal, Math.min(MAX_IN_FLIGHT, Math.max(1, threads / DIVISOR)));
    }

    /**
     * 后台任务最多占几个名额：一半（向下取整）。只有 1 个名额时不做后台（codex-audit 第十七节 B2：做了就把唯一的名额占了，
     * 前方的急活进不来；一个一个做的 donor 开了加速也不多做后台，这是取舍）。
     */
    static int backgroundSlots(int max) {
        return Math.max(0, max / 2);
    }

    /**
     * 派活的在途上限（按 lanes 算出来的 cap 之后）：开了加速的至少 2 个（线程数够时），1 个后台、1 个留给前方。
     * 11-10 f2 端到端：worker 之前一直一个一个派、lanes 学成 1，开了加速在途上限 1、后台名额 0，后台排满 64 个一个都不派。
     * 代价：真一个一个做的 donor，前方的可能在 worker 上排在那 1 个后台后面（开加速是玩家自己选的）。
     */
    static int dispatchCap(int cap, boolean boost, int boostMax) {
        return boost ? Math.max(cap, Math.min(2, boostMax)) : cap;
    }

    /** 后台结果的导入：平均 tick 超过这么多毫秒就不导（codex-audit 第十七节 B1）。 */
    static final double BACKGROUND_IMPORT_MAX_MSPT = Config.getDouble("backgroundImportMaxMspt", 30);
    /** 后台结果最多每这么多 tick 导一个。 */
    static final int BACKGROUND_IMPORT_EVERY_TICKS = Math.max(1, Config.getInt("backgroundImportEveryTicks", 4));
    /** 等着导入的后台结果有这么多个时，不再派新的后台任务（背压）。 */
    static final int BACKGROUND_PENDING_CAP = Config.getInt("backgroundPendingCap", 4);

    /**
     * 这个 tick 能不能导一个后台结果：这个 tick 没导过前方的、前方没有排着的、平均 tick 不超过上限、离上一个后台够远。
     */
    static boolean backgroundImportAllowed(boolean importedForeground, boolean foregroundPending, double avgTickMs, int ticksSinceLast) {
        return !importedForeground && !foregroundPending && avgTickMs <= BACKGROUND_IMPORT_MAX_MSPT
                && ticksSinceLast >= BACKGROUND_IMPORT_EVERY_TICKS;
    }

    /**
     * 后台 tile 的候选顺序。centers：每行 {chunk x, chunk z, 起始圈}，离这个中心不超过起始圈的不要
     * （玩家的起始圈是找需求的 outer：再往里是找需求自己管的、服务器内存里的；预设中心是 -1，从中心那个 tile 开始）。
     * 离哪个中心都不超过 radius 圈的才要；在任何一个中心的起始圈以内的都不要。按"离最近的中心几圈"从小到大，同一圈按中心、坐标排。不重复。
     */
    static List<TilePos> backgroundOrder(int[][] centers, int radius) {
        Set<TilePos> seen = new HashSet<>();
        List<long[]> c = new ArrayList<>();   // {ring, center, tx, tz}
        for (int i = 0; i < centers.length; i++) {
            int cx = centers[i][0], cz = centers[i][1];
            TilePos a = TilePos.ofChunk(cx - radius, cz - radius), b = TilePos.ofChunk(cx + radius, cz + radius);
            for (int tx = a.x(); tx <= b.x(); tx++) {
                for (int tz = a.z(); tz <= b.z(); tz++) {
                    TilePos t = new TilePos(tx, tz);
                    if (!seen.add(t)) {
                        continue;
                    }
                    int ring = Integer.MAX_VALUE, owner = -1;
                    boolean inside = false;
                    for (int j = 0; j < centers.length; j++) {
                        int d = Demand.minChunkDistance(t, centers[j][0], centers[j][1]);
                        if (d <= centers[j][2]) {
                            inside = true;
                            break;
                        }
                        if (d < ring) {
                            ring = d;
                            owner = j;
                        }
                    }
                    if (!inside && ring <= radius) {
                        c.add(new long[] {ring, owner, tx, tz});
                    }
                }
            }
        }
        c.sort(Comparator.<long[]>comparingLong(x -> x[0]).thenComparingLong(x -> x[1])
                .thenComparingLong(x -> x[2]).thenComparingLong(x -> x[3]));
        List<TilePos> out = new ArrayList<>(c.size());
        for (long[] x : c) {
            out.add(new TilePos((int) x[2], (int) x[3]));
        }
        return out;
    }
}
