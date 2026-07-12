package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

// MC 26.1: the old `onSetupTerrainBegin`/`onSetupTerrainEnd` terrain-visibility override
// (VisibleSectionDiscovery.discoverVisibleSections, targeting the now-removed
// setupRender) is dead code today -- MyGameRenderer.switchAndRenderTheWorld hands an
// EMPTY list to the portal-rendered dimension's own LevelRenderer via
// portal_setChunkInfoList(...) and just lets that LevelRenderer's own real
// cullTerrain(...) -> SectionOcclusionGraph populate it, relying entirely on vanilla's
// own (now async/cached, not one-shot) occlusion algorithm.
//
// Confirmed via the real decompiled 26.1.2 source (sibling -sources.jar) that
// SectionOcclusionGraph.initializeQueueForFullUpdate(Camera, Queue<Node>) still seeds
// its BFS from a single `BlockPos cameraPosition = camera.blockPosition();` local at the
// top of the method -- structurally the same "single seed point" shape
// VisibleSectionDiscovery/MixinSodiumOcclusionCuller's own redirect already targets,
// just derived from `Camera` instead of Sodium's `Viewport`. Unlike Sodium's per-frame
// synchronous findVisible, this full update is scheduled onto a background executor and
// cached (SectionOcclusionGraph.currentGraph) across frames, only recomputed on
// invalidate() -- redirecting only this one seed-point local (not the camera position
// used elsewhere for view-distance/frustum, which should stay real) keeps the fix
// narrowly scoped the same way the Sodium fix was, without touching the graph's
// invalidation timing.
@Mixin(SectionOcclusionGraph.class)
public abstract class MixinSectionOcclusionGraph {
    @ModifyVariable(
        method = "initializeQueueForFullUpdate",
        at = @At("STORE"),
        ordinal = 0
    )
    private BlockPos ip_redirectFullUpdateSeed(BlockPos cameraPosition) {
        if (!PortalRendering.isRendering()) {
            return cameraPosition;
        }
        
        Portal renderingPortal = PortalRendering.getRenderingPortal();
        
        Vec3 cameraPos = new Vec3(
            cameraPosition.getX() + 0.5,
            cameraPosition.getY() + 0.5,
            cameraPosition.getZ() + 0.5
        );
        
        SectionPos modifiedOrigin = renderingPortal.getPortalShape()
            .getModifiedVisibleSectionIterationOrigin(renderingPortal, cameraPos);
        
        if (modifiedOrigin != null) {
            return modifiedOrigin.center();
        }
        
        return cameraPosition;
    }
}
