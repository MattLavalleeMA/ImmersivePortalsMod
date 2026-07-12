package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_ForceMainThreadRebuild {
    // MC 26.1: Options.prioritizeChunkUpdates() changed from a plain boolean toggle to a
    // 3-way `PrioritizeChunkUpdates` enum (NONE/NEARBY/PLAYER_AFFECTED), and compileSections
    // now computes the "rebuild synchronously" flag across an `isNearby`/`rebuildSync`
    // boolean-local pair instead of a single boolean read directly from options (confirmed
    // via decompiled MC 26.1.2 source). The old implicit (type-only) @ModifyVariable match
    // at the `prioritizeChunkUpdates()` invoke became ambiguous once there were 2 in-scope
    // booleans instead of 1 -- also, that invoke happens BEFORE `rebuildSync` gets its final
    // value assigned in either branch, so overriding it there would just get immediately
    // overwritten. Re-anchored to right after `section.setWasPreviouslyEmpty(...)` (the last
    // statement before `rebuildSync` is actually read at `if (rebuildSync)`), with an
    // explicit `ordinal = 1` to disambiguate from `isNearby` (ordinal 0, declared first).
    @ModifyVariable(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setWasPreviouslyEmpty(Z)V",
            shift = At.Shift.AFTER
        ),
        ordinal = 1
    )
    private boolean modifyShouldImmediatelyRebuild(boolean originalValue) {
        if (ForceMainThreadRebuild.isCurrentFrameForceMainThreadRebuild()) {
            return true;
        }
        else {
            return originalValue;
        }
    }
}
