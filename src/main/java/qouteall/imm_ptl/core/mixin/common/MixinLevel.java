package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEWorld;

@Mixin(Level.class)
public abstract class MixinLevel implements IEWorld {
    
    @Shadow
    @Final
    protected WritableLevelData levelData;
    
    @Shadow
    public abstract ResourceKey<Level> dimension();
    
    @Shadow
    protected float rainLevel;
    
    @Shadow
    protected float thunderLevel;
    
    @Shadow
    protected float oRainLevel;
    
    @Shadow
    protected float oThunderLevel;
    
    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();
    
    @Shadow
    @Final
    private Thread thread;
    
    // MC 26.1: Level.prepareWeather() is fully gone -- confirmed via decompiled 26.1.2
    // source: weather-gradient initialization moved to a new private
    // ServerLevel.prepareWeather(WeatherData) (server-only, applied from a persistent
    // WeatherData saved-data object at level load, not a no-arg Level method anymore).
    // Re-anchored onto ServerLevel instead -- see MixinServerLevel.java.
    
    @Override
    public WritableLevelData ip_getLevelData() {
        return levelData;
    }
    
    @Override
    public void portal_setWeather(float rainGradPrev, float rainGrad, float thunderGradPrev, float thunderGrad) {
        oRainLevel = rainGradPrev;
        rainLevel = rainGrad;
        oThunderLevel = thunderGradPrev;
        thunderLevel = thunderGrad;
    }
    
    @Override
    public LevelEntityGetter<Entity> portal_getEntityLookup() {
        return getEntities();
    }
    
    @Override
    public Thread portal_getThread() {
        return thread;
    }
}
