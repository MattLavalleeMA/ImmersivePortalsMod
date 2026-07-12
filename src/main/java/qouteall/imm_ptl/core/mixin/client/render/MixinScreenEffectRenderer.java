package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {
    //avoid rendering suffocating when colliding with portal
    // MC 26.1: renderTex(TextureAtlasSprite, PoseStack) gained a trailing MultiBufferSource
    // param (confirmed via javap). Trailing-param-dropping is NOT supported for @Inject
    // handlers (contrary to what was assumed) -- the full real parameter list must be
    // declared, so the added MultiBufferSource param is included here (unused in body).
    @Inject(
        method = "renderTex",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void onRenderInWallOverlay(
        TextureAtlasSprite sprite,
        PoseStack matrices,
        MultiBufferSource bufferSource,
        CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            if (((IEEntity) player).ip_getCollidingPortal() != null) {
                ci.cancel();
            }
        }
        if (ClientTeleportationManager.isTeleportingFrequently()) {
            ci.cancel();
        }
    }
}
