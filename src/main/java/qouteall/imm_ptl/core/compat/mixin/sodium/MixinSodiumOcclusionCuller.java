package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

// MC 26.1 / Sodium 0.9.1: Sodium's occlusion culling API was fully redesigned.
// OcclusionCuller.findVisible used to take a single `OcclusionCuller.Visitor` + a
// `useOcclusionCulling` boolean + a `frame` int; it now takes THREE separate visitor
// types (GraphOcclusionVisitor, GraphOcclusionVisitor, VisibilityTestingVisitor) plus a
// CancellationToken, with no single "useOcclusionCulling" flag to override anymore.
// `isWithinFrustum(Viewport, RenderSection)` was also renamed to
// `isWithinNearbySectionFrustum(Viewport, RenderSection)`.
//
// The portal cave-culling override (redirecting the occlusion-culling iteration start
// point to the portal's visible-section origin) turns out to still have a clean hook,
// despite `findVisible` now running via async `CullTask`s (confirmed via decompiling the
// real `sodium-mc26.1.2-0.9.1-fabric.jar` with Vineflower): `findVisible` still computes
// a single `this.origin`/`this.inBoundsOrigin` (SectionPos, derived from
// `viewport.getChunkCoord()`) near the top of its own method body, which is then fed into
// `initWithinWorld`/`processQueue`/`visitNeighbors` as the BFS seed - structurally the
// exact same "single seed point" shape the old pre-redesign override targeted, just
// renamed/reshaped from a `frame`+`Visitor` pair into these two fields. Since Mixin
// transforms `OcclusionCuller`'s own bytecode (not the call site), this works identically
// whether `findVisible` is invoked from Sodium's background `CullTask` thread or
// synchronously - "no single synchronous call site" (the original concern) doesn't matter
// here, because the injection point lives inside the method itself, not at a caller.
@Mixin(OcclusionCuller.class)
public abstract class MixinSodiumOcclusionCuller {
    // MC 26.1 / Sodium 0.9.1: OcclusionCuller.getRenderSection(int,int,int) was
    // removed entirely with no replacement on this class (confirmed via javap --
    // section lookups now go through the private `sections: SectionStorage` field
    // instead). This @Shadow was dead code in this mixin (never actually called
    // anywhere in this file) -- removed rather than re-anchored.
    @Shadow(remap = false)
    private SectionPos origin;
    
    @Shadow(remap = false)
    private SectionPos inBoundsOrigin;
    
    @Unique
    private @Nullable SectionPos ip_modifiedStartPoint;
    
    // Runs right after `this.inBoundsOrigin = this.origin;` inside `findVisible` (the last
    // of the two seed-point field writes), so both fields are overridden together before
    // `init`/`initWithinWorld` ever reads them.
    @Inject(
        method = "findVisible",
        at = @At(
            value = "FIELD",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;inBoundsOrigin:Lnet/minecraft/core/SectionPos;",
            opcode = Opcodes.PUTFIELD,
            ordinal = 0,
            shift = At.Shift.AFTER
        ),
        remap = false
    )
    private void ip_redirectVisibleSectionIterationOrigin(
        OcclusionCuller.GraphOcclusionVisitor visitorWide,
        OcclusionCuller.GraphOcclusionVisitor visitorRegular,
        OcclusionCuller.VisibilityTestingVisitor visitorLocal,
        Viewport viewport,
        float searchDistanceRegular,
        float searchDistanceLocal,
        boolean useOcclusionCulling,
        CancellationToken cancellationToken,
        CallbackInfo ci
    ) {
        ip_modifiedStartPoint = null;
        
        if (!PortalRendering.shouldEnableSodiumCaveCulling()) {
            return;
        }
        
        Portal renderingPortal = PortalRendering.getRenderingPortal();
        
        Vec3 cameraPos = new Vec3(
            viewport.getTransform().x,
            viewport.getTransform().y,
            viewport.getTransform().z
        );
        
        SectionPos modifiedOrigin = renderingPortal.getPortalShape()
            .getModifiedVisibleSectionIterationOrigin(renderingPortal, cameraPos);
        
        if (modifiedOrigin != null) {
            ip_modifiedStartPoint = modifiedOrigin;
            this.origin = modifiedOrigin;
            this.inBoundsOrigin = modifiedOrigin;
        }
    }
}

