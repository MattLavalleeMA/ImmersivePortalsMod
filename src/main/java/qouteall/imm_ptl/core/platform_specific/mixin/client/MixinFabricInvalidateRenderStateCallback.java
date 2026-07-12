package qouteall.imm_ptl.core.platform_specific.mixin.client;

import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

@Pseudo
@Mixin(value = InvalidateRenderStateCallback.class, remap = false)
public interface MixinFabricInvalidateRenderStateCallback {
    // MC 26.1 / Fabric API: the EVENT field's array-backed factory now has two
    // synthetic lambdas (lambda$static$0 returns the merged
    // InvalidateRenderStateCallback instance itself; lambda$static$1 is the actual
    // void invoker that loops over the listener array and calls onInvalidate() on
    // each) -- confirmed via javap. Previously there was only one lambda at index 0.
    // Retargeted to lambda$static$1, the real void invoker.
    @Inject(
        method = "lambda$static$1",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onInvokeEvent(InvalidateRenderStateCallback[] event, CallbackInfo ci) {
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            ci.cancel();
        }
    }
}
