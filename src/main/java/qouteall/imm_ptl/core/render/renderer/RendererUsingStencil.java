package qouteall.imm_ptl.core.render.renderer;

import net.minecraft.util.profiling.Profiler;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_REPLACE;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;

public class RendererUsingStencil extends PortalRenderer {
    
    
    @Override
    public boolean replaceFrameBufferClearing() {
        boolean skipClearing = WorldRenderInfo.isRendering();
        if (skipClearing) {
            if (WorldRenderInfo.getTopRenderInfo().doRenderSky) {
                GlStateManager._depthMask(false);
                MyRenderHelper.renderScreenTriangle(FogRendererContext.getCurrentFogColor());
                GlStateManager._depthMask(true);
            }
        }
        return skipClearing;
    }
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        doPortalRendering(modelView);
    }
    
    protected void doPortalRendering(Matrix4f modelView) {
        // NOTE do not use glDisable(GL_DEPTH_TEST),
        // use GlStateManager.disableDepthTest() instead
        // because GlStateManager will cache its state.
        // Do not make its cache not synchronized
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        
        Profiler.get().popPush("render_portal_total");
        renderPortals(modelView);
        if (PortalRendering.isRendering()) {
            setStencilStateForWorldRendering();
        }
        else {
            // don't do it in finishRendering()
            // as it will render outer world's transparent things later
            myFinishRendering();
        }
    }
    
    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);
        
        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
        //nothing
    }
    
    // TEMP DIAGNOSTIC (2026-07-12): logs the exact call stack the FIRST time this
    // method runs, to definitively confirm (not guess) what's calling into
    // RendererUsingStencil while compatibilityRenderMode/RendererUsingFrameBuffer is
    // supposed to be the active renderer. Remove once root-caused/fixed.
    private static boolean loggedFirstCallStack = false;
    
    @Override
    public void prepareRendering() {
        if (!loggedFirstCallStack) {
            loggedFirstCallStack = true;
            StringBuilder sb = new StringBuilder("[PORTAL-SKIP-DIAG] RendererUsingStencil.prepareRendering() called! IPCGlobal.renderer=")
                .append(IPCGlobal.renderer == null ? "null" : IPCGlobal.renderer.getClass().getName())
                .append(" IPGlobal.renderMode=").append(IPGlobal.renderMode)
                .append(" stack:\n");
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                sb.append("    at ").append(e).append("\n");
            }
            qouteall.q_misc_util.Helper.log(sb.toString());
        }
        
        if (!IPPortingLibCompat.getIsStencilEnabled(client.getMainRenderTarget())) {
            IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), true);
            
            if (Minecraft.useShaderTransparency()) {
//                client.worldRenderer.reload();
            }
        }
        
        client.getMainRenderTarget();
        // TODO MC 26.1: RenderTarget.bindWrite no longer exists - see class-level TODO in
        // ViewAreaRenderer.java. The raw stencil-buffer GL calls below need to move into
        // a real RenderPass targeting the main render target's views once the stencil
        // masking algorithm is redesigned.
        
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        
        GlStateManager._enableDepthTest();
        GL11.glEnable(GL_STENCIL_TEST);
        
        // TEMP DIAGNOSTIC (2026-07-12): confirm which FBO is actually bound when this
        // clear runs, and whether the stencil buffer really reads back as 0
        // immediately afterward -- suspecting the clear above operates on a
        // different/no-op-for-stencil framebuffer than the one actually drawn to
        // later in the frame (see the TODO above). Remove once root-caused/fixed.
        {
            int fboAtClear = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            int w = client.getWindow().getWidth();
            int h = client.getWindow().getHeight();
            java.nio.ByteBuffer stencilBuf = java.nio.ByteBuffer.allocateDirect(1);
            GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, stencilBuf);
            // TEMP DIAGNOSTIC (2026-07-12): also read GL_STENCIL_BITS of the currently
            // bound FBO -- MixinRenderTarget's stencil-injection mixins are both
            // `require = 0` (silently disabled, per their own comments: "createBuffers
            // no longer calls GlStateManager._texImage2D/_glFramebufferTexture2D at
            // all" on MC 26.1). If this reads 0 here too, the whole
            // add-a-stencil-attachment mechanism is fully dead post-migration, and
            // FBO 0 never actually had real stencil bits either -- meaning the
            // occlusion-query "anySamplePassed" checks were never validating a real
            // mask at all (a framebuffer with no stencil buffer always passes the
            // stencil test trivially, everywhere). Remove once root-caused/fixed.
            int stencilBitsHere = GL11.glGetInteger(GL11.GL_STENCIL_BITS);
            qouteall.q_misc_util.Helper.log(
                "[PORTAL-SKIP-DIAG] prepareRendering stencilBitsAtClear=" + stencilBitsHere
            );
            qouteall.q_misc_util.Helper.log(
                "[PORTAL-SKIP-DIAG] prepareRendering fboAtClear=" + fboAtClear
                    + " stencilAtCenterAfterClear=" + (stencilBuf.get(0) & 0xFF)
            );
        }
    }
    
    @Override
    public void finishRendering() {
        //nothing
    }
    
    private void myFinishRendering() {
        GL11.glStencilFunc(GL_ALWAYS, 2333, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        
        GL11.glDisable(GL_STENCIL_TEST);
        GlStateManager._enableDepthTest();
    }
    
    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        if (shouldSkipRenderingInsideFuseViewPortal(portal)) {
            return;
        }
        
        int outerPortalStencilValue = PortalRendering.getPortalLayer();
        
        Profiler.get().push("render_view_area");
        
        boolean anySamplePassed = PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            renderPortalViewAreaToStencil(portal, modelView);
        });
        
        Profiler.get().pop();
        
        Helper.log("[PORTAL-SKIP-DIAG] " + portal.getDiscriminator() + " anySamplePassed=" + anySamplePassed
            + " vertexCount=" + qouteall.imm_ptl.core.render.PositionColorGlProgram.lastVertexCount
            + " " + qouteall.imm_ptl.core.render.PositionColorGlProgram.lastDrawStateDiag);
        
        if (!anySamplePassed) {
            setStencilStateForWorldRendering();
            return;
        }
        
        PortalRendering.pushPortalLayer(portal);
        
        int thisPortalStencilValue = outerPortalStencilValue + 1;
        
        if (!portal.isFuseView()) {
            Profiler.get().push("clear_depth_of_view_area");
            clearDepthOfThePortalViewArea(portal);
            Profiler.get().pop();
        }
        
        setStencilStateForWorldRendering();
        
        renderPortalContent(portal);
        
        PortalRendering.popPortalLayer();
        // pop portal layer before restoring depth, for clipping, see ViewAreaRenderer
        
        if (!portal.isFuseView()) {
            restoreDepthOfPortalViewArea(portal, modelView, thisPortalStencilValue);
        }
        
        clampStencilValue(outerPortalStencilValue);
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }
    
    private void renderPortalViewAreaToStencil(
        Portal portal, Matrix4f modelView
    ) {
        int outerPortalStencilValue = PortalRendering.getPortalLayer();
        
        //is the mask here different from the mask of glStencilMask?
        GL11.glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF);
        
        //if stencil and depth test pass, the data in stencil buffer will increase by 1
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        //NOTE about GL_INCR:
        //if multiple triangles occupy the same pixel and passed stencil and depth tests,
        //its stencil value will still increase by one
        
        GL11.glStencilMask(0xFF);
        
        // update it before pushing
        FrontClipping.updateInnerClipping(modelView);
        
        // TEMP DIAGNOSTIC (2026-07-12): compare against prepareRendering()'s own
        // fboAtClear/stencilAtCenterAfterClear log -- if fboAtDraw differs from
        // fboAtClear, or stencilAtCenterBeforeDraw is nonzero here despite the clear
        // reporting 0, that proves the once-per-frame stencil clear is landing on a
        // different framebuffer than this draw actually targets. Remove once
        // root-caused/fixed.
        {
            int fboAtDraw = GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            int w = client.getWindow().getWidth();
            int h = client.getWindow().getHeight();
            java.nio.ByteBuffer stencilBuf = java.nio.ByteBuffer.allocateDirect(1);
            GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, stencilBuf);
            qouteall.q_misc_util.Helper.log(
                "[PORTAL-SKIP-DIAG] " + portal.getDiscriminator()
                    + " renderPortalViewAreaToStencil fboAtDraw=" + fboAtDraw
                    + " stencilAtCenterBeforeDraw=" + (stencilBuf.get(0) & 0xFF)
                    + " outerPortalStencilValue=" + outerPortalStencilValue
            );
        }
        
        // MC 26.1: RenderSystem.getProjectionMatrix() was removed - RenderStates
        // .basicProjectionMatrix (captured every LevelRenderer.renderLevel call via
        // MixinGameRenderer's ip_captureBasicProjectionMatrix) is the real replacement.
        // This used to be an identity-matrix placeholder because renderPortalArea was a
        // no-op stub at the time - now that it actually draws geometry, an identity
        // projection would badly mis-transform the mask triangles.
        ViewAreaRenderer.renderPortalArea(
            portal, Vec3.ZERO,
            modelView,
            RenderStates.basicProjectionMatrix,
            true, true,
            true, true
        );
    }
    
    private void clearDepthOfThePortalViewArea(
        Portal portal
    ) {
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        
        setStencilStateForWorldRendering();
        
        //do not manipulate color buffer
        GL11.glColorMask(false, false, false, false);
        
        //save the state
        int originalDepthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);
        
        //always passes depth test
        GL11.glDepthFunc(GL_ALWAYS);
        
        //the pixel's depth will be 1, which is the furthest
        GL11.glDepthRange(1, 1);
        
        MyRenderHelper.renderScreenTriangle();
        
        //retrieve the state
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthFunc(originalDepthFunc);
        GL11.glDepthRange(0, 1);
    }
    
    protected void restoreDepthOfPortalViewArea(
        Portal portal, Matrix4f modelView,
        int portalStencilValue
    ) {
        setStencilLimitation(portalStencilValue);
        
        int originalDepthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);
        
        GL11.glDepthFunc(GL_ALWAYS);
        
        ViewAreaRenderer.renderPortalArea(
            portal, Vec3.ZERO,
            modelView,
            RenderStates.basicProjectionMatrix, // MC 26.1: see renderPortalViewAreaToStencil's own comment above
            false, false,
            true,
            true // important: should clip, otherwise depth will be abnormal when viewing scale box from inside in portal
        );
        
        GL11.glDepthFunc(originalDepthFunc);
    }
    
    public static void clampStencilValue(
        int maximumValue
    ) {
        GlStateManager._depthMask(true);
        
        //NOTE GL_GREATER means ref > stencil
        //GL_LESS means ref < stencil
        
        //pass if the stencil value is greater than the maximum value
        GL11.glStencilFunc(GL_LESS, maximumValue, 0xFF);
        
        //if stencil test passed, encode the stencil value
        GL11.glStencilOp(GL_KEEP, GL_REPLACE, GL_REPLACE);
        
        //do not manipulate the depth buffer
        GL11.glDepthMask(false);
        
        //do not manipulate the color buffer
        GL11.glColorMask(false, false, false, false);
        
        GlStateManager._disableDepthTest();
        
        MyRenderHelper.renderScreenTriangle();
        
        GL11.glDepthMask(true);
        
        GL11.glColorMask(true, true, true, true);
        
        GlStateManager._enableDepthTest();
    }
    
    private void setStencilStateForWorldRendering() {
        int thisPortalStencilValue = PortalRendering.getPortalLayer();
        
        setStencilLimitation(thisPortalStencilValue);
    }
    
    public static void setStencilLimitation(int stencilValue) {
        //draw content in the mask
        GL11.glStencilFunc(GL_EQUAL, stencilValue, 0xFF);
        
        //do not manipulate stencil buffer now
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    }
    
    public static boolean shouldSkipRenderingInsideFuseViewPortal(Portal portal) {
        if (!PortalRendering.isRendering()) {
            return false;
        }
        
        Portal renderingPortal = PortalRendering.getRenderingPortal();
        
        if (!renderingPortal.isFuseView()) {
            return false;
        }
        
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        
        Vec3 transformedCameraPos = portal
            .transformPoint(renderingPortal.transformPoint(cameraPos));
        
        // roughly test whether they are reverse portals
        return cameraPos.distanceToSqr(transformedCameraPos) < 0.1;
    }
}
