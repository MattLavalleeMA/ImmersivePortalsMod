package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
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
    // MC 26.1: LevelRenderer.setupRender(Camera,Frustum,boolean,boolean) is fully removed;
    // its chunk-builder-camera-position call now lives in the private
    // cullTerrain(Camera,Frustum,boolean) method instead (confirmed via decompiled source:
    // `this.sectionRenderDispatcher.setCameraPosition(cameraPos);`). Re-anchored there --
    // same target call site (SectionRenderDispatcher.setCameraPosition(Vec3)), just a
    // different (real) enclosing method name.
    @Redirect(
        method = "cullTerrain",
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
    
    // MC 26.1: LocalPlayer.getX()/getY()/getZ() are no longer called from setupRender (fully
    // removed) or from its replacement cullTerrain/scheduleTranslucentSectionResort --
    // confirmed via decompiled source, the camera position now flows uniformly as
    // `camera.position()` (a single Vec3, not separate getX/getY/getZ reads) into
    // cullTerrain -> scheduleTranslucentSectionResort(camera.position()). That position is
    // already corrected for portal rendering earlier, directly on the Camera itself: see
    // MixinCamera.onUpdateFinished's `WorldRenderInfo.adjustCameraPos(this_)`, which mutates
    // Camera's own `position` field at the end of every Camera.update(...) -- before
    // cullTerrain ever reads it. These three redirects are therefore redundant with an
    // already-working fix elsewhere, not broken/needing a replacement; removed rather than
    // kept as dead weight targeting a fully nonexistent call site.
}

