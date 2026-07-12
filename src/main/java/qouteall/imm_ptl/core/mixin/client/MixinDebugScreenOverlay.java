package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public class MixinDebugScreenOverlay {
    // MC 26.1: getSystemInformation() (which used to build the right-side debug text
    // list and return it) is gone. The right/left line lists are now built in
    // extractRenderState and each mutated in-place via extractLines(graphics, list,
    // isLeft) -- isLeft == false is the right-side column (confirmed via decompile:
    // extractLines(graphics, leftLines, true); extractLines(graphics, rightLines,
    // false);). Hook extractLines at RETURN and only act on the right-side call.
    @Inject(method = "extractLines", at = @At("RETURN"))
    private void onExtractLines(
        GuiGraphicsExtractor graphics, List<String> list, boolean isLeft, CallbackInfo ci
    ) {
        if (isLeft) {
            return;
        }

        List<String> debugText = RenderStates.collectDebugText();

        if (IPGlobal.moveDebugTextToTop) {
            list.addAll(0, debugText);
        }
        else {
            list.addAll(debugText);
        }
    }

}
