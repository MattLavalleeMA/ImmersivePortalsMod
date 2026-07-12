package qouteall.imm_ptl.core.mixin.common.entity_sync;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.network.PacketRedirection;

// MC 26.1: ServerCommonPacketListenerImpl.send(Packet, PacketSendListener) is gone --
// confirmed via decompiled 26.1.2 source: `PacketSendListener` was replaced throughout
// by Netty's own `ChannelFutureListener`, and the single-arg `send(Packet)` overload is
// just a thin wrapper delegating to `send(Packet, null)` -- so the 2-arg overload
// remains the one real implementation point to hook (same role the old 2-arg method
// played), just retyped.
@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl_Redirect {
    @Shadow @Final protected MinecraftServer server;
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet modifyPacket(Packet originalPacket) {
        if (PacketRedirection.getForceRedirectDimension() == null) {
            return originalPacket;
        }
        
        return PacketRedirection.createRedirectedMessage(
            server,
            PacketRedirection.getForceRedirectDimension(),
            originalPacket
        );
    }
    
    @SuppressWarnings("unchecked")
    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V"
        ),
        cancellable = true
    )
    private void onSend(
        Packet<?> packet, @Nullable ChannelFutureListener packetSendListener, CallbackInfo ci
    ) {
        PacketRedirection.ForceBundleCallback forceBundleCallback = PacketRedirection.getForceBundleCallback();
        if (forceBundleCallback != null) {
            forceBundleCallback.accept(
                (ServerCommonPacketListenerImpl) (Object) this,
                (Packet<ClientGamePacketListener>) packet
            );
            ci.cancel();
        }
    }
}
