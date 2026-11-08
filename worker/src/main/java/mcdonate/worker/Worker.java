package mcdonate.worker;

import mcdonate.common.ModIds;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** donor mod：领任务、生成、导出。 现在只打一行日志，证明 mod 被加载了。 */
public final class Worker implements ModInitializer {
    public static final String MOD_ID = ModIds.of("worker");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("{} loaded", MOD_ID);
    }
}
