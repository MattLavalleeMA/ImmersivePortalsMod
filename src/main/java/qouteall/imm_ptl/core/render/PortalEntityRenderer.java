package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.mc_utils.WireRenderingHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Environment(EnvType.CLIENT)
public class PortalEntityRenderer extends EntityRenderer<Portal, PortalEntityRenderer.PortalRenderState> {
    
    public PortalEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }
    
    @Override
    public PortalRenderState createRenderState() {
        return new PortalRenderState();
    }
    
    @Override
    public void extractRenderState(Portal portal, PortalRenderState state, float partialTicks) {
        super.extractRenderState(portal, state, partialTicks);
        state.portal = portal;
    }
    
    @Override
    public void submit(
        PortalRenderState state,
        PoseStack matrixStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera
    ) {
        Portal portal = state.portal;
        
        IPCGlobal.renderer.renderPortalInEntityRenderer(portal);
        
        if (OverlayRendering.shouldRenderOverlay(portal)) {
            OverlayRendering.onRenderPortalEntity(portal, matrixStack);
        }
        
        if (IPGlobal.debugRenderPortalShapeMesh && !PortalRendering.isRendering()) {
            submitNodeCollector.submitCustomGeometry(
                matrixStack, RenderTypes.lines(),
                (pose, vertexConsumer) -> WireRenderingHelper.renderPortalShapeMeshDebug(
                    matrixStack, vertexConsumer, portal
                )
            );
        }
        
        super.submit(state, matrixStack, submitNodeCollector, camera);
    }
    
    public static class PortalRenderState extends EntityRenderState {
        public Portal portal;
    }
}

