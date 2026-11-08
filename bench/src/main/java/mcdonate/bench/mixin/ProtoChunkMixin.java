package mcdonate.bench.mixin;

import mcdonate.bench.FromDisk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ProtoChunk.class)
abstract class ProtoChunkMixin implements FromDisk {
    @Unique
    private volatile boolean mcdonate$fromDisk;

    @Override
    public void mcdonate$markFromDisk() {
        mcdonate$fromDisk = true;
    }

    @Override
    public boolean mcdonate$isFromDisk() {
        return mcdonate$fromDisk;
    }
}
