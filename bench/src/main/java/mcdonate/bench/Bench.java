package mcdonate.bench;

import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 测量 mod：生成计数、阶段计时。 现在只打一行日志，证明 mod 被加载了。 */
public final class Bench implements ModInitializer {
    public static final String MOD_ID = ModIds.of("bench");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("{} loaded", MOD_ID);
    }
}
