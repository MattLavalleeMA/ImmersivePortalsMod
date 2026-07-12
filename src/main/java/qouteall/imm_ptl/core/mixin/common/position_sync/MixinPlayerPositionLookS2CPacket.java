package qouteall.imm_ptl.core.mixin.common.position_sync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;

@Mixin(ClientboundPlayerPositionPacket.class)
public class MixinPlayerPositionLookS2CPacket implements IEPlayerPositionLookS2CPacket {
    private ResourceKey<Level> playerDimension;
    
    @Override
    public ResourceKey<Level> ip_getPlayerDimension() {
        return playerDimension;
    }
    
    @Override
    public void ip_setPlayerDimension(ResourceKey<Level> dimension) {
        playerDimension = dimension;
    }
    
    // MC 26.1: ClientboundPlayerPositionPacket was rewritten from an imperative
    // read()/write(FriendlyByteBuf) class into a plain record
    // (int id, PositionMoveRotation change, Set<Relative> relatives) serialized via a
    // declarative `StreamCodec.composite(...)` static field -- confirmed via decompiled
    // 26.1.2 source: there is no write(FriendlyByteBuf) method left to inject into at
    // all. Appending an extra "player dimension" value to this packet's wire format now
    // needs the STREAM_CODEC itself wrapped/replaced (a real codec-composition
    // redesign, not a rename) -- disabled (require = 0) rather than crash-on-launch;
    // the paired read-side hook (MixinClientboundPlayerPositionPacket.java, client
    // package) has the same issue. Cross-dimension position-sync tagging for this
    // specific packet won't work until that redesign lands.
    @Inject(method = "Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("RETURN"), require = 0)
    private void onWrite(FriendlyByteBuf buf, CallbackInfo ci) {
        buf.writeResourceKey(playerDimension);
    }
}
