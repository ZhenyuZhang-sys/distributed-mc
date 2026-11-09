package mcdonate.client;

/**
 * 测试钩子（docs/step9-plan.md 1.1）：Fabric 入口点 {@code mcdonate:consent_driver}。正式环境里没有任何 mod 提供它。
 * 测试另装一个只给测试的小 mod（mcdonate-client-testhooks），在同意界面打开时替测试勾选、按"同意"——走和玩家点击完全一样的代码路径。
 * 正式的客户端 jar 里没有自动同意的代码，也不认任何跳过同意的系统属性（9.0 检查）。
 */
public interface ConsentDriver {
    /** 进服后（测试钩子可以在这里打开设置界面、按开关，就像玩家那样）。 */
    default void onJoin(net.minecraft.client.Minecraft client) {}

    /** 同意界面打开了。 */
    void onConsentScreen(ConsentScreen screen);
}
