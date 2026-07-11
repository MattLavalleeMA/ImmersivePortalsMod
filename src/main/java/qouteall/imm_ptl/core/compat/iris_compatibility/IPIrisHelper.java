package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;

// TODO MC 26.1: this class copied depth/stencil/color buffers directly between
// framebuffers via raw GL texture ids (RenderTarget.frameBufferId/getColorTextureId()/
// getDepthTextureId()/unbindWrite()), which no longer exist - RenderTarget now only
// exposes GpuTexture/GpuTextureView objects (see com.mojang.blaze3d.textures.GpuTexture,
// RenderTarget.getColorTexture()/getDepthTexture()). The equivalent copy operation is
// CommandEncoder.copyTextureToTexture(GpuTexture,GpuTexture,...), but the callers of
// this class (the Iris-compatibility portal renderer stack) are themselves stubbed
// pending a full redesign - see RendererUsingStencil/IrisCompatibilityPortalRenderer's
// class-level TODOs. Stubbed as no-ops for now.
public class IPIrisHelper {
    
    public static void copyDepthStencil(
        RenderTarget from, RenderTarget to,
        boolean copyDepth, boolean copyStencil
    ) {
        // no-op: see class-level TODO
    }
    
    public static void newCopyDepthStencil(
        RenderTarget from, RenderTarget to
    ) {
        // no-op: see class-level TODO
    }
    
    public static void copyColor(
        RenderTarget from, RenderTarget to
    ) {
        // no-op: see class-level TODO
    }
    

}
