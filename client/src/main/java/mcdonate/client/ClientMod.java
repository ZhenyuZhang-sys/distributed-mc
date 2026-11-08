package mcdonate.client;

import mcdonate.common.ModIds;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端 mod：启动 worker、按帧率限速。 现在只打一行日志，证明 mod 被加载了。 */
public final class ClientMod implements ClientModInitializer {
    public static final String MOD_ID = ModIds.of("client");
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOG.info("{} loaded", MOD_ID);
    }
}
