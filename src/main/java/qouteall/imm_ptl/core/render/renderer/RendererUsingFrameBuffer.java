package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.List;

public class RendererUsingFrameBuffer extends PortalRenderer {
    SecondaryFrameBuffer secondaryFrameBuffer = new SecondaryFrameBuffer();
    
    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        renderPortals(modelView);
    }
    
    @Override
    public void onAfterTranslucentRendering(Matrix4f modelView) {
    
    }
    
    @Override
    public void onHandRenderingEnded() {
    
    }
    
    @Override
    public void finishRendering() {
    
    }
    
    @Override
    public void prepareRendering() {
        secondaryFrameBuffer.prepare();
        
        GlStateManager._enableDepthTest();
        
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    
        IPPortingLibCompat.setIsStencilEnabled(client.getMainRenderTarget(), false);
//        ((IEFrameBuffer) client.getMainRenderTarget()).setIsStencilBufferEnabledAndReload(false);
    }
    
    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        if (PortalRendering.isRendering()) {
            //only support one-layer portal
            return;
        }
        
        // 2026-07-12 (section 2.13, round 3): the GPU occlusion-query gate
        // (testShouldRenderPortal, removed below) was found to return false on ~99%
        // of frames regardless of renderer (section 2.12) -- it relied on hardware
        // occlusion queries/depth test, which proved unreliable for this draw.
        // Replaced with an always-render-then-manually-decide-occlusion-at-
        // composite-time approach (see MyRenderHelper.drawPortalAreaWithFramebuffer/
        // PositionTexturedGlProgram for the current, round-3 version of that compare
        // -- two earlier variants were tried and disproven, see session notes/doc
        // section 2.16+). Only the cheap CPU-side shouldSkipRenderingPortal checks
        // (already applied by getPortalsToRender before doRenderPortal is even
        // called) remain as the gate.
        FrontClipping.updateInnerClipping(modelView);
        
        // Raw GL id of the framebuffer actually bound for the main render target
        // right now (captured BEFORE swapping to the secondary buffer below) --
        // needed to restore the correct binding after compositing, since
        // ip_setFrameBuffer only swaps a Java-side reference and never itself
        // rebinds anything at the raw GL level.
        int fboBeforePortal = GlStateManager.getFrameBuffer(GL30.GL_FRAMEBUFFER);
        
        PortalRendering.pushPortalLayer(portal);
        
        RenderTarget oldFrameBuffer = client.getMainRenderTarget();
        
        ((IEMinecraftClient) client).ip_setFrameBuffer(secondaryFrameBuffer.fb);
        
        // Clear the secondary buffer's color to fully transparent and depth to far
        // -- a completely normal clear, matching a fresh vanilla frame (no depth
        // priming -- see the round-3 comment below for why that was abandoned).
        // (RenderTarget.bindWrite/GlStateManager._clearColor/_clearDepth no longer
        // exist -- clearing now goes through the GpuDevice command-encoder API
        // directly on the GpuTexture objects.)
        {
            com.mojang.blaze3d.textures.GpuTexture colorTex = secondaryFrameBuffer.fb.getColorTexture();
            com.mojang.blaze3d.textures.GpuTexture depthTex = secondaryFrameBuffer.fb.getDepthTexture();
            if (colorTex != null && depthTex != null) {
                RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(colorTex, 0, depthTex, 1.0);
            }
        }
        
        // 2026-07-12 (round 3): a previous attempt primed this buffer's depth
        // attachment with a copy of the real scene's depth (`copyDepthFrom`) so
        // nested content would be naturally occluded by real nearby geometry via
        // ordinary hardware depth test. PROVEN BROKEN via pixel-readback
        // diagnostics: vanilla's own sky-rendering pass (drawn first in ANY normal
        // world render, including this nested one) clobbers the primed depth with
        // its own far-plane value regardless of what was primed there (real
        // captured numbers: primed depth 0.98 became ~1.0 immediately after
        // rendering -- only possible if sky ignores/overwrites depth without a
        // passing depth test, safe in a NORMAL frame since sky is drawn first into
        // a freshly-cleared buffer there, but fatal to a pre-primed one). Distant
        // Horizons was re-checked in depth and does NOT have a real technique for
        // this either -- DH's own LOD render pass also just clears to far every
        // time; DH never needs real occlusion against nearby geometry because its
        // LOD content is constructed to only exist beyond vanilla's render distance
        // (structurally non-overlapping), unlike a portal which CAN be directly
        // behind/occluded by arbitrarily-close real geometry. Reverted -- occlusion
        // against real nearby geometry is instead decided manually at compositing
        // time (see MyRenderHelper.drawPortalAreaWithFramebuffer/
        // PositionTexturedGlProgram), which doesn't depend on this buffer's own
        // depth at all and is therefore immune to the sky-clobbering issue.
        
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        
        renderPortalContent(portal);
        
        // TEMP DIAGNOSTIC (2026-07-12, round 3): read the secondary buffer's depth
        // AND color/alpha at the screen-center texel right after nested content
        // rendered, still while the secondary buffer is the active target -- kept
        // from round 2 since it's still useful (confirms nested content actually
        // drew something plausible there). Remove once root-caused/fixed.
        int diagX = client.getWindow().getWidth() / 2;
        int diagY = client.getWindow().getHeight() / 2;
        {
            GpuTexture secDepthTexDiag = secondaryFrameBuffer.fb.getDepthTexture();
            GpuTexture secColorTexDiag = secondaryFrameBuffer.fb.getColorTexture();
            float depthAfterNestedRender = secDepthTexDiag != null
                ? MyRenderHelper.debugReadDepthPixel(((GlTexture) secDepthTexDiag).glId(), diagX, diagY)
                : -1;
            float[] colorAfterNestedRender = secColorTexDiag != null
                ? MyRenderHelper.debugReadColorPixel(((GlTexture) secColorTexDiag).glId(), diagX, diagY)
                : new float[] {-1, -1, -1, -1};
            qouteall.q_misc_util.Helper.log("[PORTAL-SKIP-DIAG] " + portal.getDiscriminator()
                + " afterNestedRender(center) depth=" + depthAfterNestedRender
                + " color=(" + colorAfterNestedRender[0] + "," + colorAfterNestedRender[1]
                + "," + colorAfterNestedRender[2] + "," + colorAfterNestedRender[3] + ")");
        }
        
        ((IEMinecraftClient) client).ip_setFrameBuffer(oldFrameBuffer);
        
        PortalRendering.popPortalLayer();
        
        renderSecondBufferIntoMainBuffer(portal, modelView, fboBeforePortal);
        
        // TEMP DIAGNOSTIC (2026-07-12): confirm the compositing draw actually ran
        // and issued no GL errors. Remove once root-caused/fixed.
        int glErr = GL11.glGetError();
        qouteall.q_misc_util.Helper.log("[PORTAL-SKIP-DIAG] RendererUsingFrameBuffer.doRenderPortal "
            + portal.getDiscriminator() + " composited into main buffer, glError=" + glErr);
        
        // TEMP DIAGNOSTIC (2026-07-12, round 2): read the MAIN target's final color
        // at the same screen-center texel after compositing, to see the actual
        // end result of this pipeline at the exact pixel the other two readings
        // were taken at.
        {
            GpuTexture mainColorTexDiag = client.getMainRenderTarget().getColorTexture();
            float[] finalColor = mainColorTexDiag != null
                ? MyRenderHelper.debugReadColorPixel(((GlTexture) mainColorTexDiag).glId(), diagX, diagY)
                : new float[] {-1, -1, -1, -1};
            qouteall.q_misc_util.Helper.log("[PORTAL-SKIP-DIAG] " + portal.getDiscriminator()
                + " finalMainBufferColor(center)=(" + finalColor[0] + "," + finalColor[1]
                + "," + finalColor[2] + "," + finalColor[3] + ")");
        }
        
        MyRenderHelper.debugFramebufferDepth();
    }
    
    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }
    
    @Override
    public boolean replaceFrameBufferClearing() {
        return false;
    }
    
    private void renderSecondBufferIntoMainBuffer(Portal portal, Matrix4f modelView, int restoreToFbo) {
        MyRenderHelper.drawPortalAreaWithFramebuffer(
            portal,
            secondaryFrameBuffer.fb,
            modelView,
            RenderStates.basicProjectionMatrix,
            restoreToFbo
        );
    }
    
    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);
    
        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }
}
