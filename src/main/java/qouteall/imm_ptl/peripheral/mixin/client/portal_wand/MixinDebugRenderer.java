package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

// MC 26.1: this mixin used to target the now-fully-removed
// DebugRenderer.render(PoseStack, MultiBufferSource.BufferSource, double, double,
// double) to hook the portal wand's marker rendering in (DebugRenderer's debug
// drawing moved wholesale to the declarative net.minecraft.gizmos API, which can't
// express this mod's rotating wireframe cube marker -- see
// WireRenderingHelper.renderSmallCubeFrame / docs/migration-26.1-plan.md's
// MixinDebugRenderer.java entry for the full investigation).
//
// Re-anchored here to LevelRenderer's synthetic lambda$addMainPass$0 method
// instead, replicating the exact pattern Vivecraft/VivecraftMod uses on its own
// Multiloader-26.1 branch (MC 26.1.2, matching this mod's target version exactly)
// to inject custom PoseStack/MultiBufferSource-based drawing into the same render
// pass -- see LevelRendererVRMixin.java on that branch, which independently landed
// on the same lambda$addMainPass$0* anchor after confirming its own
// submitFeatures-based hook (used on Vivecraft's MC 26.2 branch) doesn't exist as
// a real method on 26.1.x. levelRenderState is a genuine parameter of the
// synthetic lambda method (captured effectively-final locals become real
// parameters of the generated method at the bytecode level), hence
// @Local(argsOnly = true); poseStack/bufferSource are declared inside the lambda
// body itself, hence plain @Local (bufferSource needs ordinal = 0 to disambiguate
// from the sibling crumblingBufferSource local of the same type).
@Mixin(LevelRenderer.class)
public class MixinDebugRenderer {
    @Inject(
        method = "lambda$addMainPass$0*",
        at = @At(value = "CONSTANT", args = "stringValue=renderSolidFeatures")
    )
    private void imm_ptl$renderPortalWandMarkers(
        CallbackInfo ci,
        @Local(argsOnly = true) LevelRenderState levelRenderState,
        @Local PoseStack poseStack,
        @Local(ordinal = 0) MultiBufferSource.BufferSource bufferSource
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        
        ItemStack itemStack = player.getMainHandItem();
        if (itemStack.getItem() != PortalWandItem.instance) {
            return;
        }
        
        Vec3 cameraPos = levelRenderState.cameraRenderState.pos;
        
        PortalWandItem.clientRender(
            player, itemStack, poseStack, bufferSource,
            cameraPos.x, cameraPos.y, cameraPos.z
        );
    }
}
