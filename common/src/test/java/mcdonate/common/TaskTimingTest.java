package mcdonate.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** worker 在结果里带的时间点（收到任务、开始生成、生成完、打包完），coordinator 拆耗时用。 */
class TaskTimingTest {
    @Test
    void roundTrip() {
        CompoundTag tag = new CompoundTag();
        TaskTiming.stamp(tag, 100, 150, 1350, 1400);
        assertArrayEquals(new long[] {100, 150, 1350, 1400}, TaskTiming.read(tag));
    }

    @Test
    void missingIsNull() {
        assertNull(TaskTiming.read(new CompoundTag()), "旧 worker 的结果没有时间点");
    }
}
