package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// TODO MC 26.1 / Sodium 0.9.1: Sodium's occlusion culling API was fully redesigned.
// OcclusionCuller.findVisible used to take a single `OcclusionCuller.Visitor` + a
// `useOcclusionCulling` boolean + a `frame` int; it now takes THREE separate visitor
// types (GraphOcclusionVisitor, GraphOcclusionVisitor, VisibilityTestingVisitor) plus a
// CancellationToken, with no single "useOcclusionCulling" flag to override anymore.
// `isWithinFrustum(Viewport, RenderSection)` was also renamed to
// `isWithinNearbySectionFrustum(Viewport, RenderSection)`.
// The portal cave-culling override (redirecting the occlusion-culling iteration start
// point to the portal's visible-section origin, and tolerating an initial out-of-frustum
// start point) needs a genuine redesign against this new 3-visitor shape - deferred
// pending real in-game testing, same as the other stubbed rendering-pipeline items.
// Stubbed to a no-op for now: cave culling through portals will behave like vanilla
// Sodium's own culling (a performance-only regression, not a correctness one).
@Mixin(OcclusionCuller.class)
public abstract class MixinSodiumOcclusionCuller {
    @Shadow(remap = false)
    protected abstract RenderSection getRenderSection(int x, int y, int z);
    
    @Unique
    private @Nullable SectionPos ip_modifiedStartPoint;
}

