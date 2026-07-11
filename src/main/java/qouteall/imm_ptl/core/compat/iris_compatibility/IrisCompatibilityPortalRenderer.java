package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.joml.Matrix4f;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

// TODO MC 26.1: this renderer's deferred-buffer stencil compositing (RenderTarget.
// bindWrite/setClearColor/clear, raw GL stencil ops) has no direct translation - see
// ViewAreaRenderer/RendererUsingStencil/IrisPortalRenderer's class-level TODOs for the
// broader context. Stubbed to a no-op renderer pending a full redesign + in-game testing.
public class IrisCompatibilityPortalRenderer extends PortalRenderer {
    
    public static final IrisCompatibilityPortalRenderer instance = new IrisCompatibilityPortalRenderer(false);
    public static final IrisCompatibilityPortalRenderer debugModeInstance =
        new IrisCompatibilityPortalRenderer(true);
    
    public boolean isDebugMode;
    
    public IrisCompatibilityPortalRenderer(boolean isDebugMode) {
        this.isDebugMode = isDebugMode;
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
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
    public void prepareRendering() {
    
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
    
    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
}
