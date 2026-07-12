package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.MyRenderHelper;

// MC 26.1: RenderSystem.setShaderFogStart(float)/setShaderFogEnd(float) are both fully
// gone -- confirmed via decompiled 26.1.2 source: RenderSystem has no fog-related
// methods left at all. Fog distance now flows through a dedicated
// net.minecraft.client.renderer.fog.FogRenderer.setupFog(...) (a different class from
// the old top-level FogRenderer already re-anchored for the cross-dimension fog color
// swap earlier this migration -- see docs/migration-26.1-plan.md), which builds and
// returns a plain-field FogData object (renderDistanceStart/renderDistanceEnd set
// directly in setupFog; environmentalStart/environmentalEnd set slightly earlier in the
// same method, via one of several FogEnvironment subclasses depending on fog type).
// Re-anchored to transform all four fields at the RETURN of setupFog instead of the
// old two-callsite setShaderFogStart/End hooks -- functionally equivalent, since the
// old hooks fired once per fog "layer" (environmental vs render-distance) and this
// transform (see MyRenderHelper.transformFogDistance) is a uniform "push fog distance
// out of range" toggle, not a per-layer-specific formula.
@Mixin(FogRenderer.class)
public class MixinRenderSystem_Fog {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void onSetupFog(
        Camera camera,
        int renderDistanceInChunks,
        DeltaTracker deltaTracker,
        float darkenWorldAmount,
        ClientLevel level,
        CallbackInfoReturnable<FogData> cir
    ) {
        FogData fog = cir.getReturnValue();
        fog.environmentalStart = MyRenderHelper.transformFogDistance(fog.environmentalStart);
        fog.environmentalEnd = MyRenderHelper.transformFogDistance(fog.environmentalEnd);
        fog.renderDistanceStart = MyRenderHelper.transformFogDistance(fog.renderDistanceStart);
        fog.renderDistanceEnd = MyRenderHelper.transformFogDistance(fog.renderDistanceEnd);
    }
}
