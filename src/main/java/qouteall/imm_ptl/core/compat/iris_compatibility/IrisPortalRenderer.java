package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.joml.Matrix4f;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

// TODO MC 26.1: this renderer's multi-framebuffer stencil compositing (raw GL
// glBindFramebuffer/glBlitFramebuffer on RenderTarget.frameBufferId, RenderTarget.
// bindWrite/unbindWrite/checkStatus, viewWidth/viewHeight) has no direct translation -
// none of those APIs exist anymore (RenderTarget now only exposes GpuTexture/
// GpuTextureView objects, width/height fields, and no dynamic stencil test state at all
// in the new RenderPass/GpuBuffer pipeline). See ViewAreaRenderer/RendererUsingStencil's
// class-level TODOs for the broader context. Stubbed to a no-op renderer pending a full
// redesign + in-game testing.
public class IrisPortalRenderer extends PortalRenderer {
    public static final IrisPortalRenderer instance = new IrisPortalRenderer();
    
    IrisPortalRenderer() {
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }
    
    @Override
    public void prepareRendering() {
    
    }
    
    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void finishRendering() {
    
    }
    
    @Override
    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
    
    }
}
