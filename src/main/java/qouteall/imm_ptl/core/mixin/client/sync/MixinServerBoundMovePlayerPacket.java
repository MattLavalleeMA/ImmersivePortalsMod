package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerMoveC2SPacket;

@Mixin(ServerboundMovePlayerPacket.class)
public class MixinServerBoundMovePlayerPacket {
    // MC 26.1: ServerboundMovePlayerPacket's constructor gained a new
    // `horizontalCollision` boolean param between `onGround` and `hasPos`/`hasRot`
    // (confirmed via decompiled 26.1.2 source).
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onConstruct(
        double x, double y, double z, float yaw, float pitch, boolean onGround,
        boolean horizontalCollision, boolean changePosition, boolean changeLook, CallbackInfo ci
    ) {
        ResourceKey<Level> dimension = Minecraft.getInstance().player.level().dimension();
        ((IEPlayerMoveC2SPacket) this).ip_setPlayerDimension(dimension);
    }
}
