package mcdonate.coordinator;

/**
 * 主世界里每个导入的 chunk 来自哪个 donor 世界（docs/step6-plan.md 0.2）。数据在导入账本（{@link ImportLedger}，7.7）里，
 * 按 region 存在世界目录，重启后还在。
 *
 * <p>服务器自己存这个 chunk 时（ChunkMapSaveMixin）标成"服务器加载过"：存盘说明服务器加载过、可能改过它，
 * 之后就当成服务器的。
 */
public final class Ownership {
    /** 主世界的账本。服务器启动时从世界目录读回来（Coordinator）。 */
    public static final ImportLedger LEDGER = new ImportLedger();

    private Ownership() {}

    static String owner(long chunkPos) {
        return LEDGER.owner(chunkPos);
    }

    /** 服务器存主世界的 chunk 时调。 */
    public static void forget(long chunkPos) {
        LEDGER.serverSaved(chunkPos);
    }
}
