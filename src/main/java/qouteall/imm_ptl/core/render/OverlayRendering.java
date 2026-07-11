package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@Environment(EnvType.CLIENT)
public class OverlayRendering {
    
    
    public static boolean shouldRenderOverlay(Portal portal) {
        if (portal instanceof BreakablePortalEntity breakablePortalEntity) {
            if (breakablePortalEntity.getActualOverlay() != null) {
                return breakablePortalEntity.isInFrontOfPortal(CHelper.getCurrentCameraPos());
            }
        }
        return false;
    }
    
    private static boolean shaderOverlayWarned = false;
    
    public static void onRenderPortalEntity(
        Portal portal,
        PoseStack matrixStack
    ) {
        if (IrisInterface.invoker.isShaders()) {
            if (!shaderOverlayWarned) {
                shaderOverlayWarned = true;
                CHelper.printChat("[Immersive Portals] Portal overlay cannot be rendered with shaders");
            }
            
            return;
        }
        
        if (portal instanceof BreakablePortalEntity) {
            renderBreakablePortalOverlay(
                ((BreakablePortalEntity) portal),
                RenderStates.getPartialTick(),
                matrixStack
            );
        }
    }
    
    // TODO MC 26.1: BakedModel/BakedQuad/BlockRenderDispatcher were replaced by a new
    // model-part system (BlockStateModel/BlockStateModelPart/BlockStateModelDispatcher,
    // net.minecraft.client.renderer.block.dispatch package) with a completely restructured
    // BakedQuad (net.minecraft.client.resources.model.geometry.BakedQuad, now a record of
    // packed vertex/material data with no .getSprite(), and VertexConsumer.putBulkData(pose,
    // BakedQuad, ...) no longer matches this new shape). This needs a genuine redesign (not a
    // rename), similar to the stencil-masking/clip-plane rendering-pipeline items - deferred
    // pending real in-game testing. Stubbed to a no-op for now so the portal breakable-overlay
    // block render simply doesn't draw anything, instead of guessing at the new quad-consuming
    // API.
    
    /**
     * {@link net.minecraft.client.renderer.entity.FallingBlockRenderer}
     */
    private static void renderBreakablePortalOverlay(
        BreakablePortalEntity portal,
        float partialTick,
        PoseStack matrixStack
    ) {
        // TODO MC 26.1: stubbed out, see comment above. Was: build BakedQuads from the
        // overlay's BlockState via BlockRenderDispatcher.getBlockModel() and feed them into
        // a VertexConsumer via VertexConsumer.putBulkData(...); needs re-implementing against
        // the new BlockStateModel/BlockStateModelPart/BakedQuad API.
    }
}
