package mcdonate.client;

import java.nio.file.Path;

/** 只隔离运行目录；不复制世界、session.lock 或 donor worldId。 */
public final class WorkerLayout {
    private WorkerLayout() {}
    public static Path directory(Path donateDir, int slot) {
        if (slot < 0 || slot >= 3) throw new IllegalArgumentException("worker slot 越界");
        return donateDir.resolve(slot == 0 ? "worker" : "worker-" + (slot + 1));
    }
}
