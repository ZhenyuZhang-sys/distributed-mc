package mcdonate.bench.mixin;

import mcdonate.bench.SyncLoads;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 标出玩家实体 tick 的范围，让守护计数分清"玩家引起的同步加载"（见 SyncLoads）。只在主线程上被调用。 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void mcdonate$tickStart(CallbackInfo ci) {
        SyncLoads.enterPlayerTick();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void mcdonate$tickEnd(CallbackInfo ci) {
        SyncLoads.exitPlayerTick();
    }

    @Inject(method = "doTick", at = @At("HEAD"))
    private void mcdonate$doTickStart(CallbackInfo ci) {
        SyncLoads.enterPlayerTick();
    }

    @Inject(method = "doTick", at = @At("RETURN"))
    private void mcdonate$doTickEnd(CallbackInfo ci) {
        SyncLoads.exitPlayerTick();
    }
}
