package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinClientboundPlayerPositionPacket {
    // MC 26.1: ClientboundPlayerPositionPacket's FriendlyByteBuf-reading constructor is
    // fully gone -- it's now a plain record deserialized via a declarative
    // `StreamCodec.composite(...)`, not a constructor overload (confirmed via
    // decompiled 26.1.2 source). Disabled (require = 0) for the same reason as the
    // paired write-side hook (MixinPlayerPositionLookS2CPacket.java, common package) --
    // see that file's comment for the full explanation.
    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"), require = 0)
    private void onRead(FriendlyByteBuf buf, CallbackInfo ci) {
        if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
            ResourceKey<Level> playerDimension = buf.readResourceKey(Registries.DIMENSION);
            ((IEPlayerPositionLookS2CPacket) this).ip_setPlayerDimension(playerDimension);
        }
    }
    
}
