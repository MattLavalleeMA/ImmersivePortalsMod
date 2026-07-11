package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

// TODO MC 26.1: the old @Inject targeted a "translucent" CONSTANT string inside
// LevelRenderer.renderLevel's own body with the old (Camera,GameRenderer,LightTexture,
// Matrix4f,Matrix4f) parameter list. renderLevel was completely restructured around a
// FrameGraphBuilder (translucent-layer handling now lives inside the PostChain
// transparency mechanism / lambda$addMainPass$0, not a simple string constant in
// renderLevel's own body) - LightTexture itself no longer exists as a class either.
// Needs re-deriving against the new structure with an actual game launch to verify Iris
// interop; stubbed out for now (consistent with the rest of the Iris-compat renderer
// stack being disabled pending that follow-up).
@Mixin(value = LevelRenderer.class, priority = 900)
public class MixinLevelRenderer_BeforeIris {
}
