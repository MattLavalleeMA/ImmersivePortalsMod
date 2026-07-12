package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@Mixin(MultiBufferSource.BufferSource.class)
public class MixinMultiBufferSourceBufferSource {
    // NOTE: the method descriptor string literal below must use the real current package
    // (renderer.rendertype.RenderType) -- bulk_rename.py's import/usage rewriting doesn't
    // touch string literals inside annotations, so this stale `Lnet/minecraft/client/
    // renderer/RenderType;` (pre-migration package) silently survived every prior rename
    // pass and only failed at weave time.
    @Inject(
        method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;)V",
        at = @At("HEAD")
    )
    private void onBeginDraw(RenderType layer, CallbackInfo ci) {
        if (PortalRendering.isRenderingOddNumberOfMirrors()) {
            RenderStates.shouldForceDisableCull = true;
            GlStateManager._disableCull();
        }
    }
    
    @Inject(
        method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;)V",
        at = @At("RETURN")
    )
    private void onEndDraw(RenderType layer, CallbackInfo ci) {
        if (RenderStates.shouldForceDisableCull) {
            RenderStates.shouldForceDisableCull = false;
            GlStateManager._enableCull();
        }
    }
}
