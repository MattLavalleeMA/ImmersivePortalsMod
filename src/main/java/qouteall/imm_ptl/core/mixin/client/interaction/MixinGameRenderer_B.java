package qouteall.imm_ptl.core.mixin.client.interaction;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

// MC 26.1: GameRenderer.pick(float) (the crosshair hit-testing method) was moved to
// Minecraft.pick(float) directly (confirmed via javap: `private void pick(float)` on
// Minecraft, no equivalent left on GameRenderer at all) -- retargeted this whole mixin
// onto Minecraft.class instead.
@Mixin(Minecraft.class)
public class MixinGameRenderer_B {
    
    //do not update target when rendering portal
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void onUpdateTargetedEntity(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            if (WorldRenderInfo.isRendering()) {
                ci.cancel();
            }
        }
    }
    
    @Inject(method = "pick", at = @At("RETURN"))
    private void onUpdateTargetedEntityFinish(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level != null) {
            BlockManipulationClient.updatePointedBlock(partialTick);
        }
    }
}
