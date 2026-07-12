package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;

// TODO MC 26.1: DebugRenderer.render(PoseStack, MultiBufferSource.BufferSource,
// double, double, double) is fully removed (not renamed/reshaped) — confirmed via
// inspect_class.py that DebugRenderer's only public methods now are
// refreshRendererList(), emitGizmos(Frustum, double, double, double, float), and
// the static getTargetedEntity(...). Vanilla's own debug overlays (chunk borders,
// pathfinding, hitboxes, etc.) were migrated wholesale to the new declarative
// net.minecraft.gizmos API (Gizmos.cuboid/circle/line/arrow/rect/point/...,
// collected via a thread-local GizmoCollector and drawn later by LevelRenderer's
// own gizmo-submission step — see LevelRenderer$FinalizedGizmos /
// client.renderer.gizmos.DrawableGizmoPrimitives). emitGizmos() runs during the
// CPU-only extractLevel() phase and only provides a Frustum + camera position +
// partial tick — no PoseStack/MultiBufferSource, so it can't be used as a drop-in
// replacement anchor for this mod's own immediate-mode PoseStack-based marker
// drawing (PortalWandItem.clientRender / ClientPortalWandPortalCreation.render
// etc.). The declarative Gizmos API itself isn't a fit either: it only offers
// axis-aligned primitives (no rotation support), while this mod's wand markers
// include an animated *rotating* highlight cube (WireRenderingHelper
// .renderSmallCubeFrame), which Gizmos.cuboid(AABB, GizmoStyle) can't express at
// all. The real remaining PoseStack+MultiBufferSource render call site now lives
// inside LevelRenderer.addMainPass's captured FramePass lambda (confirmed via
// decompiled source — same synthetic-lambda-method problem already tracked for
// MixinLevelRenderer.java's ~8 disabled hooks), which isn't a stable, directly
// injectable named method either. Disabled here (rather than left targeting a
// nonexistent method, which would hard-crash Mixin weaving at game launch since
// @Inject is `require`d by default) until a real game launch lets this be
// re-anchored against the actual lambda method alongside the other deferred
// MixinLevelRenderer hooks — see "2. Portal rendering algorithm redesign" in
// docs/migration-26.1-plan.md.
@Mixin(DebugRenderer.class)
public class MixinDebugRenderer {
}
