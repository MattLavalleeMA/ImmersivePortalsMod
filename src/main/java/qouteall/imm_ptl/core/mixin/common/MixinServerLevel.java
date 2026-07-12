package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEServerWorld;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel implements IEServerWorld {
    
    @Shadow
    public abstract SavedDataStorage getDataStorage();
    
    @Shadow
    public abstract ServerChunkCache getChunkSource();
    
    @Shadow
    @Final
    private ServerLevelData serverLevelData;
    
    @Shadow
    @Final
    private PersistentEntitySectionManager<Entity> entityManager;
    
    // MC 26.1: Level.prepareWeather() (no-arg) is fully gone -- confirmed via decompiled
    // 26.1.2 source: weather-gradient initialization moved to this new private
    // ServerLevel.prepareWeather(WeatherData) (applied from a persistent WeatherData
    // saved-data object at level load, called from ServerLevel's own constructor-time
    // setup). Re-anchored here from MixinLevel.java (which could no longer target this
    // method at all, since it moved off the shared Level base class onto ServerLevel
    // specifically, and gained a parameter).
    // Fix overworld rain cause nether fog change
    @Inject(method = "prepareWeather", at = @At("TAIL"))
    private void ip_onPrepareWeather(WeatherData weatherData, CallbackInfo ci) {
        ServerLevel this_ = (ServerLevel) (Object) this;
        if (this_.dimension() == Level.NETHER) {
            this_.setRainLevel(0);
            this_.setThunderLevel(0);
        }
    }
    
    //in vanilla if a dimension has no player and no forced chunks then it will not tick
    // MC 26.1: ServerLevel.tick(BooleanSupplier)'s old `if (!players.isEmpty() ||
    // !forcedChunks.isEmpty()) { ... }`-style gating (a plain List.isEmpty() call) is
    // fully gone -- confirmed via decompiled 26.1.2 source: activity is now gated by
    // `this.chunkSource.hasActiveTickets()` (a single boolean, encompassing both the
    // player and forced-chunk cases uniformly) plus a separate `emptyTime < 300`
    // grace-period counter. Re-anchored to redirect hasActiveTickets() instead of the
    // removed List.isEmpty() call -- same intent (force this dimension to look
    // "active" when ImmPtl wants it loaded), just inverted (true forces active,
    // whereas the old redirect returned false to mean "list is not empty").
    @Redirect(
        method = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;hasActiveTickets()Z"
        )
    )
    private boolean redirectIsEmpty(ServerChunkCache chunkSource) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        if (ImmPtlChunkTracking.shouldLoadDimension(this_.dimension())) {
            return true;
        }
        return chunkSource.hasActiveTickets();
    }
    
    // for debug
    @Inject(method = "Lnet/minecraft/server/level/ServerLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        cir.setReturnValue("ServerWorld " + this_.dimension().identifier() +
            " " + serverLevelData.getLevelName());
    }
    
    @Inject(
        method = "tickNonPassenger",
        at = @At("HEAD")
    )
    private void onTickNonPassenger(Entity entity, CallbackInfo ci) {
        // this should be done right before setting last tick pos to this tick pos
        ((IEEntity) entity).ip_tickCollidingPortal();
    }
    
    @Override
    public PersistentEntitySectionManager<Entity> ip_getEntityManager() {
        return entityManager;
    }
}
