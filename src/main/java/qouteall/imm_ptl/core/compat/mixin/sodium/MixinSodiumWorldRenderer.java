package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
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
    }
}
