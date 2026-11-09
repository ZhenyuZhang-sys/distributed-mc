package mcdonate.worker;

/** 给 Mixin 调的公开入口（Mixin 类不能直接用包内可见的类）。 */
public final class WorkerSeed {
    private WorkerSeed() {}

    /** 配了 coordinator 就返回握手拿到的 seed，否则原样返回。 */
    public static String levelSeed(String original) {
        String address = SeedHandshake.address();
        return chooseSeed(original, address, mcdonate.common.Config.get("inviteSeed", null),
                () -> SeedHandshake.seed(() -> SeedHandshake.handshake(address)));
    }

    /**
     * 建世界用哪个 seed。
     * <ul>
     *   <li>没配 coordinator：原样。
     *   <li>第 9 步，客户端起的 worker：用邀请里的 seed（系统属性 mcdonate.inviteSeed，客户端 mod 传），不为了 seed 单独握手——
     *       token 是一次性的，只换正式的那次连接。正式连接时 Welcome 里的 seed 再核对一遍（{@link WorkerClient#seedOk}）。
     *   <li>Docker 里的本地 worker（没有邀请）：建世界前握手要 seed（第 5 步）。
     * </ul>
     */
    static String chooseSeed(String original, String address, String inviteSeed, java.util.function.LongSupplier handshake) {
        if (address == null) {
            return original;
        }
        if (inviteSeed != null && !inviteSeed.isBlank()) {
            try {
                return Long.toString(Long.parseLong(inviteSeed.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("mcdonate.inviteSeed 不是数字：" + inviteSeed, e);
            }
        }
        return Long.toString(handshake.getAsLong());
    }
}
