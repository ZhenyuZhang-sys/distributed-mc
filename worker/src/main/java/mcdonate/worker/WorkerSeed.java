package mcdonate.worker;

/** 给 Mixin 调的公开入口（Mixin 类不能直接用包内可见的类）。 */
public final class WorkerSeed {
    private WorkerSeed() {}

    /** 配了 coordinator 就返回握手拿到的 seed，否则原样返回。 */
    public static String levelSeed(String original) {
        String address = SeedHandshake.address();
        if (address == null) {
            return original;
        }
        return Long.toString(SeedHandshake.seed(() -> SeedHandshake.handshake(address)));
    }
}
