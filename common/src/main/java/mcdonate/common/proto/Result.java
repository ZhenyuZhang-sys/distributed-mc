package mcdonate.common.proto;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/**
 * worker → coordinator：生成结果。
 * ok 时 error 是空串、tile 是 TileFile.toTag()；失败时 tile 是空的 CompoundTag。
 */
public record Result(long taskId, boolean ok, String error, CompoundTag tile) implements Message {
    public Result {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(tile, "tile");
        if (ok && !error.isEmpty()) {
            throw new IllegalArgumentException("ok 的结果不能带 error");
        }
        if (!ok && !tile.isEmpty()) {
            throw new IllegalArgumentException("失败的结果不能带 tile");
        }
    }

    public static Result success(long taskId, CompoundTag tile) {
        return new Result(taskId, true, "", tile);
    }

    public static Result failure(long taskId, String error) {
        return new Result(taskId, false, error, new CompoundTag());
    }
}
