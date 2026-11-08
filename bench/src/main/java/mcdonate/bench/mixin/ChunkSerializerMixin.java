package mcdonate.bench.mixin;

import mcdonate.bench.FromDisk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 从存档读出的半成品打上标记（成品会包成 ImposterProtoChunk，不用标）。 */
@Mixin(ChunkSerializer.class)
abstract class ChunkSerializerMixin {
    @Inject(method = "read", at = @At("RETURN"))
    private static void mcdonate$markFromDisk(ServerLevel level, PoiManager poiManager, RegionStorageInfo info,
            ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        ProtoChunk chunk = cir.getReturnValue();
        if (!(chunk instanceof ImposterProtoChunk)) {
            ((FromDisk) chunk).mcdonate$markFromDisk();
        }
    }
}
