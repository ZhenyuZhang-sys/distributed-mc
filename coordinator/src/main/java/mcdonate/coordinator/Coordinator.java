package mcdonate.coordinator;

import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 服务器 mod：需求、调度、写入、验证。 现在只打一行日志，证明 mod 被加载了。 */
public final class Coordinator implements ModInitializer {
    public static final String MOD_ID = ModIds.of("coordinator");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("{} loaded", MOD_ID);
    }
}
