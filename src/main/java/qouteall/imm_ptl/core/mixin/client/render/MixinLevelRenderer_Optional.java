package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

// avoid crashing with sodium
// the overwrite has priority of 1000
@Mixin(value = LevelRenderer.class, priority = 1100)
public class MixinLevelRenderer_Optional {
    @Shadow
    private ViewArea viewArea;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    //avoid translucent sort while rendering portal
    // TODO MC 26.1: LevelRenderer.renderSectionLayer(...) no longer exists in this form
    // (part of the FrameGraphBuilder rewrite already documented for MixinLevelRenderer.java)
    // and RenderType.translucent()/RenderTypes.translucent() doesn't exist either (RenderTypes
    // only has more specific translucent-ish factories like glintTranslucent()/
    // linesTranslucent() now) -- removed rather than guessed at, same precedent as the
    // other already-removed renderSectionLayer-targeting hooks.
    
    //the camera position is used for translucent sort
    //avoid messing it
    @Redirect(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;setCameraPosition(Lnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0
    )
    private void onSetChunkBuilderCameraPosition(
        SectionRenderDispatcher chunkBuilder, Vec3 cameraPosition
    ) {
        if (PortalRendering.isRendering()) {
            if (minecraft.level.dimension() == RenderStates.originalPlayerDimension) {
                return;
            }
        }
        chunkBuilder.setCameraPosition(cameraPosition);
    }
    
    // TODO MC 26.1: old anchor ShaderInstance.apply() no longer exists (ShaderInstance
    // itself was removed) - the clip-plane-uniform mechanism this drove is stubbed
    // (see FrontClipping's class-level TODO), so this hook is no longer needed.
    
    // correct the position of updating ViewArea
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"),
        require = 0
    )
    private double redirectGetXInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().x;
        }
        return player.getX();
    }
    
    // biolerplate
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"),
        require = 0
    )
    private double redirectGetYInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().y;
        }
        return player.getY();
    }
    
    // biolerplate
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"),
        require = 0
    )
    private double redirectGetZInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().z;
        }
        return player.getZ();
    }
}
