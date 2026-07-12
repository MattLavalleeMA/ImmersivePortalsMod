package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderSectionManager;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumRenderingContext;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {
    @Shadow
    @Final
    @Mutable
    private int renderDistance;
    
    @Shadow
    private @NotNull SortedRenderLists renderLists;
    
    @Override
    public void ip_swapContext(SodiumRenderingContext context) {
        Validate.isTrue(context.renderDistance != 0, "Render distance cannot be 0");
        Validate.isTrue(context.renderLists != null);
        
        SortedRenderLists renderListsTmp = renderLists;
        renderLists = context.renderLists;
        context.renderLists = renderListsTmp;
        
        int renderDistanceTmp = renderDistance;
        renderDistance = context.renderDistance;
        context.renderDistance = renderDistanceTmp;
    }
    
    /**
     * The section visibility information will be wrong if rendered a portal.
     * Just cancel this optimization.
     * isSectionVisible() is currently only used for culling entities.
     */
    // MC 26.1 / Sodium 0.9.1: RenderSectionManager.isSectionVisible(int,int,int)
    // (chunk-section-coordinate based) was removed entirely -- confirmed via javap,
    // no method by that name remains. SodiumWorldRenderer.isEntityVisible(...) (the
    // only caller of the old method, per its own doc comment above) now calls
    // RenderSectionManager.isBoxVisible(double,double,double,double,double,double)
    // instead (an AABB-min/max-corners based check), confirmed via decompile of the
    // exact pinned sodium-mc26.1.2-0.9.1-fabric.jar. Same cancel-the-optimization
    // intent, just retargeted to the new signature/semantics.
    @Inject(method = "isBoxVisible", at = @At("HEAD"), cancellable = true)
    private void onIsSectionVisible(
        double x1, double y1, double z1, double x2, double y2, double z2,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (RenderStates.portalsRenderedThisFrame != 0) {
            cir.setReturnValue(true);
        }
    }
    
}
