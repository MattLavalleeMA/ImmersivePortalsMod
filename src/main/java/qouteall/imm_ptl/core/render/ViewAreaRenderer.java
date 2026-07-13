package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.my_util.TriangleConsumer;

// MC 26.1: renderPortalArea/buildPortalViewAreaTrianglesBuffer draw the portal's
// view-area geometry (as colored triangles) into the stencil buffer to mask which
// screen pixels show the portal's other side. The old ShaderInstance/Tesselator/
// BufferBuilder/BufferUploader classes this used to be built on no longer exist, and
// the new RenderPipeline abstraction has no dynamic stencil-test state at all
// (DepthStencilState is baked into a RenderPipeline at build time) - so this now draws
// via PositionColorGlProgram, a small hand-rolled raw-GL (LWJGL) program that bypasses
// Blaze3D's RenderPipeline/RenderPass/GpuBuffer entirely for this one feature, matching
// the plan in docs/migration-26.1-plan.md.
public class ViewAreaRenderer {
    
    public static void renderPortalArea(
        Portal portal, Vec3 fogColor,
        Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
        boolean doFaceCulling, boolean doModifyColor,
        boolean doModifyDepth, boolean doClip
    ) {
        if (doFaceCulling) {
            GlStateManager._enableCull();
        }
        else {
            GlStateManager._disableCull();
        }
        
        if (portal.isFuseView() && IPGlobal.maxPortalLayer != 0) {
            GL11.glColorMask(false, false, false, false);
        }
        else {
            if (!doModifyColor) {
                GL11.glColorMask(false, false, false, false);
            }
            else {
                GL11.glColorMask(true, true, true, true);
            }
        }
        
        if (doModifyDepth) {
            if (portal.isFuseView()) {
                GlStateManager._depthMask(false);
            }
            else {
                GlStateManager._depthMask(true);
            }
        }
        else {
            GlStateManager._depthMask(false);
        }
        
        boolean shouldReverseCull = PortalRendering.isRenderingOddNumberOfMirrors();
        if (shouldReverseCull) {
            MyRenderHelper.applyMirrorFaceCulling();
        }
        
        if (doClip) {
            if (PortalRendering.isRendering()) {
                FrontClipping.setupInnerClipping(
                    PortalRendering.getActiveClippingPlane(),
                    modelViewMatrix, 0  // don't do adjustment
                );
            }
        }
        else {
            FrontClipping.disableClipping();
        }
        
        GlStateManager._enableDepthTest();
        
        CHelper.enableDepthClamp();
        
        PositionColorGlProgram.begin(modelViewMatrix, projectionMatrix, fogColor, 1.0f);
        
        buildPortalViewAreaTrianglesBuffer(
            fogColor,
            portal,
            CHelper.getCurrentCameraPos(),
            RenderStates.getPartialTick()
        );
        
        PositionColorGlProgram.end();
        
        GlStateManager._enableCull();
        CHelper.disableDepthClamp();
        
        GL11.glColorMask(true, true, true, true);
        GlStateManager._depthMask(true);
        
        if (shouldReverseCull) {
            MyRenderHelper.recoverFaceCulling();
        }
        
        if (PortalRendering.isRendering()) {
            FrontClipping.disableClipping();
        }
        
        CHelper.checkGlError();
    }
    
    /**
     * Accumulates the portal's view-area mesh triangles into the currently-active
     * {@link PositionColorGlProgram} batch (started by the caller via {@code begin()}).
     * The {@code fogColor} param is unused now that color is set once as a uniform in
     * {@code begin()} - kept for API-compatibility with existing call sites.
     */
    public static void buildPortalViewAreaTrianglesBuffer(
        Vec3 fogColor, Portal portal,
        Vec3 cameraPos, float partialTick
    ) {
        Vec3 originRelativeToCamera = portal.getOriginPos().subtract(cameraPos);
        
        portal.renderViewAreaMesh(originRelativeToCamera, PositionColorGlProgram.VERTEX_OUTPUT);
    }
    
    public static void outputTriangle(
        TriangleConsumer vertexOutput, Vec3 center,
        Vec3 localXAxis, Vec3 localYAxis,
        double p0x, double p0y, double p1x, double p1y, double p2x, double p2y
    ) {
        vertexOutput.accept(
            center.x + p0x * localXAxis.x() + p0y * localYAxis.x(),
            center.y + p0x * localXAxis.y() + p0y * localYAxis.y(),
            center.z + p0x * localXAxis.z() + p0y * localYAxis.z(),
            center.x + p1x * localXAxis.x() + p1y * localYAxis.x(),
            center.y + p1x * localXAxis.y() + p1y * localYAxis.y(),
            center.z + p1x * localXAxis.z() + p1y * localYAxis.z(),
            center.x + p2x * localXAxis.x() + p2y * localYAxis.x(),
            center.y + p2x * localXAxis.y() + p2y * localYAxis.y(),
            center.z + p2x * localXAxis.z() + p2y * localYAxis.z()
        );
    }
    
    @Deprecated
    private static void generateTriangleForNormalShape(
        TriangleConsumer vertexOutput,
        Portal portal,
        Vec3 posInPlayerCoordinate
    ) {
        //avoid floating point error for converted global portal
        final double w = Math.min(portal.getWidth(), 23333);
        final double h = Math.min(portal.getHeight(), 23333);
        
        Vec3 localXAxis = portal.getAxisW().scale(w / 2);
        Vec3 localYAxis = portal.getAxisH().scale(h / 2);
        
        outputFullQuad(vertexOutput, posInPlayerCoordinate, localXAxis, localYAxis);
        
    }
    
    @Deprecated
    private static void generateTriangleForGlobalPortal(
        TriangleConsumer vertexOutput,
        Portal portal,
        Vec3 portalOriginLocal
    ) {
        Vec3 cameraPosFromPortalOrigin = portalOriginLocal.scale(-1);
        
        Vec3 cameraPosFromPortalOriginProjected =
            portal.getLocalVecProjectedToPlane(cameraPosFromPortalOrigin);
        
        Vec3 localCenter = portalOriginLocal.add(cameraPosFromPortalOriginProjected);
        
        double r = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 16;
        if (TransformationManager.isIsometricView) {
            r *= 2;
        }
        
        double distance = Math.abs(cameraPosFromPortalOrigin.dot(portal.getNormal()));
        if (distance > 200) {
            r = r * 200 / distance;
        }
        
        Vec3 localXAxis = portal.getAxisW().scale(r);
        Vec3 localYAxis = portal.getAxisH().scale(r);
        
        outputFullQuad(vertexOutput, localCenter, localXAxis, localYAxis);
    }
    
    public static void outputFullQuad(
        TriangleConsumer vertexOutput, Vec3 posInPlayerCoordinate,
        Vec3 localXAxis, Vec3 localYAxis
    ) {
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, 1, 1, -1, 1, 1, -1
        );
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, -1, 1, -1, -1, 1, -1
        );
    }
}
