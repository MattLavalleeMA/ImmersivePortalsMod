package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager;

import java.util.Set;

@Mixin(ServerPlayer.class)
public class MixinServerPlayerEntity_MA {
    // MC 26.1: ServerPlayer/Entity.changeDimension(TeleportTransition) was renamed to
    // teleport(TeleportTransition) -- confirmed via decompiled 26.1.2 source:
    // `Entity.teleport(TeleportTransition): @Nullable Entity`, overridden by
    // `ServerPlayer.teleport(TeleportTransition): @Nullable ServerPlayer` with a
    // covariant return type (hence CallbackInfoReturnable<ServerPlayer> here, not
    // <Entity>).
    @Inject(method = "teleport", at = @At("HEAD"))
    private void onChangeDimensionByVanilla(
        TeleportTransition dimensionTransition, CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;
        onBeforeDimensionTravel(this_);
    }
    
    // update chunk visibility data
    // MC 26.1: ServerPlayer.teleportTo(ServerLevel, double, double, double, float,
    // float) gained a `Set<Relative>` param and a trailing `resetCamera` boolean, and
    // now returns boolean instead of void -- confirmed via decompiled 26.1.2 source.
    @Inject(
        method = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
        at = @At("HEAD")
    )
    private void onTeleported(
        ServerLevel targetWorld,
        double x,
        double y,
        double z,
        Set<Relative> relatives,
        float yaw,
        float pitch,
        boolean resetCamera,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;
        
        if (this_.level() != targetWorld) {
            onBeforeDimensionTravel(this_);
        }
    }
    
    private static void onBeforeDimensionTravel(ServerPlayer player) {
        CustomPortalGenManager customPortalGenManager =
            IPPerServerInfo.of(player.level().getServer()).customPortalGenManager;
        
        if (customPortalGenManager != null) {
            customPortalGenManager.onBeforeConventionalDimensionChange(player);
            ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
            
            ServerTaskList.of(player.level().getServer()).addTask(() -> {
                customPortalGenManager.onAfterConventionalDimensionChange(player);
                return true;
            });
        }
    }
}
