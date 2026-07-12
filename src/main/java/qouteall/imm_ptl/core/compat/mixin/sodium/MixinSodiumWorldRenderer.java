package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.q_misc_util.Helper;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    // TEMP DIAGNOSTIC (2026-07-12): tracing the Sodium "Global terrain uniforms have
    // not been updated" crash. Remove once root-caused/fixed.
    @Shadow
    private ClientLevel level;
    
    // Sodium 0.9.1+mc26.1.2: setupTerrain gained a new `FogParameters` 3rd param and a
    // trailing `Matrix4f` param (confirmed via javap on the exact local jar --
    // `setupTerrain(Camera, Viewport, FogParameters, boolean, boolean, Matrix4f)`). Only
    // `camera`/`viewport` are actually used here, but unlike vanilla mixins, Mixin requires
    // the FULL real descriptor for @Inject on a `remap = false` third-party-mod target
    // (trailing-param-dropping isn't accepted here the way it is for vanilla/remapped
    // targets) -- so the extra params are kept, just unused.
    @Inject(
        method = "setupTerrain",
        at = @At("HEAD")
    )
    private void onUpdateChunks(
        Camera camera, Viewport viewport, FogParameters fogParameters,
        boolean spectator, boolean updateChunksImmediately, Matrix4f matrix4f, CallbackInfo ci
    ) {
        SodiumInterface.frustumCuller = new FrustumCuller();
        Vec3 cameraPos = camera.position();
        SodiumInterface.frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);
        
        Helper.log("[SODIUM-DIAG] setupTerrain dim=" + this.level.dimension().identifier() +
            " depth=" + MyGameRenderer.portalRenderDepth);
    }
    
    @Inject(
        method = "renderLayer",
        at = @At("HEAD")
    )
    private void onRenderLayerHead(
        CommandList commandList, ChunkRenderMatrices matrices, TerrainRenderPass pass,
        double x, double y, double z, FogParameters fogParameters, GpuSampler terrainSampler,
        CallbackInfo ci
    ) {
        Helper.log("[SODIUM-DIAG] renderLayer dim=" + this.level.dimension().identifier() +
            " pass=" + pass + " depth=" + MyGameRenderer.portalRenderDepth);
    }
    
    @Inject(
        method = "endFrame",
        at = @At("HEAD")
    )
    private void onEndFrameHead(CallbackInfo ci) {
        Helper.log("[SODIUM-DIAG] endFrame dim=" +
            (this.level != null ? this.level.dimension().identifier() : "null") +
            " depth=" + MyGameRenderer.portalRenderDepth);
    }
}
