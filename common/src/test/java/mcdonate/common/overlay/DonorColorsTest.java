package mcdonate.common.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import mcdonate.common.net.ProvenanceUpdate.Source;
import org.junit.jupiter.api.Test;

/** 颜色（docs/overlay-plan.md 第 3 节）：四类底色沿用实时地图；每个 donor 的颜色只由稳定名字决定，每次都一样，和四类底色分得开。 */
class DonorColorsTest {
    @Test
    void baseColorsMatchTheLiveMap() {
        assertEquals(0x5b8c5a, DonorColors.of(Source.SERVER));
        assertEquals(0x7d6b9e, DonorColors.of(Source.RESUMED));
        assertEquals(0xd62728, DonorColors.of(Source.REJECTED));
        assertEquals(-1, DonorColors.of(Source.UNKNOWN));   // 来源不明：不画
    }

    @Test
    void sameStableNameSameColorEveryTime() {
        String alice = "9b5c2f1e-0000-4000-8000-000000000001";
        int c = DonorColors.donor(alice);
        assertEquals(c, DonorColors.donor(alice));
        assertEquals(c, DonorColors.donor(new String(alice.toCharArray())));
        // 固定几个名字的颜色（回归：算法改了这里要跟着改，并想清楚老玩家的颜色会变）
        assertEquals(DonorColors.donor("world-abc"), DonorColors.donor("world-abc"));
        assertNotEquals(DonorColors.donor("world-abc"), DonorColors.donor("world-abd"));
    }

    @Test
    void donorColorsStayAwayFromBaseColorsAndMostlyDiffer() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            int c = DonorColors.donor("donor-" + i);
            float h = DonorColors.hue(c);
            for (Source s : new Source[] {Source.SERVER, Source.RESUMED, Source.REJECTED}) {
                float d = Math.abs(h - DonorColors.hue(DonorColors.of(s)));
                assertTrue(Math.min(d, 360 - d) >= DonorColors.MIN_HUE_GAP - 0.5f, "donor-" + i + " 和 " + s + " 太近：" + d);
            }
            seen.add(c);
        }
        assertTrue(seen.size() >= 150, "200 个名字只有 " + seen.size() + " 种颜色");
    }
}
