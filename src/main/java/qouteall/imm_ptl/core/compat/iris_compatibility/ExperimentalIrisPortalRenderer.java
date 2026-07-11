package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

// Iris now use the vanilla framebuffer's depth texture and support stencil
// So a better portal rendering method for forward-shading shaders is possible
//
// TODO MC 26.1: this renderer's stencil-based portal masking (raw GL11 glStencilFunc/
// glStencilOp calls interleaved with RenderTarget.bindWrite) has no direct translation -
// RenderTarget.bindWrite no longer exists, and the new RenderPass/GpuBuffer pipeline has
// no dynamic stencil test state at all (DepthStencilState is baked into a RenderPipeline
// at build time). See ViewAreaRenderer/RendererUsingStencil's class-level TODOs for the
// broader context. Stubbed to a no-op renderer pending a full redesign + in-game testing.
public class ExperimentalIrisPortalRenderer extends PortalRenderer {
    public static final ExperimentalIrisPortalRenderer instance = new ExperimentalIrisPortalRenderer();
    
    public static void init() {
    
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onBeginIrisTranslucentRendering(Matrix4f modelView) {
        // Resume Iris world rendering
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipeline().get();
        ((IEIrisNewWorldRenderingPipeline) (Object) pipeline)
            .ip_setIsRenderingWorld(true);
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
    
    @Override
    public void prepareRendering() {
    
    }
    
    @Override
    public void finishRendering() {
    
    }
    
    @Override
    public void invokeWorldRendering(WorldRenderInfo worldRenderInfo) {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipeline().get();
        
        SystemTimeUniforms.COUNTER.beginFrame(); // is it necessary?
        super.invokeWorldRendering(worldRenderInfo);
        SystemTimeUniforms.COUNTER.beginFrame(); // make Iris to update the uniforms
        
        if (pipeline instanceof IrisRenderingPipeline newWorldRenderingPipeline) {
            // this is important to hand rendering
            newWorldRenderingPipeline.isBeforeTranslucent = true;
        }
        
        // Avoid Iris from force-disabling depth mask
        ((IEIrisNewWorldRenderingPipeline) (Object) pipeline)
            .ip_setIsRenderingWorld(false);
    }
    
    public void onAfterIrisDeferredCompositeRendering() {
        // no-op: see class-level TODO
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }
}
