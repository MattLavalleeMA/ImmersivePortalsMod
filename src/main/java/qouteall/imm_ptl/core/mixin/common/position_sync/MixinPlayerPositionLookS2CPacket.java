package qouteall.imm_ptl.core.mixin.common.position_sync;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;

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
    // declarative `public static final StreamCodec<FriendlyByteBuf,
    // ClientboundPlayerPositionPacket> STREAM_CODEC` field -- confirmed via decompiled
    // 26.1.2 source: there is no write(FriendlyByteBuf)/read-constructor left to inject
    // into at all. Since the codec field is shared by both the server (which only ever
    // calls its encode side, for this S2C packet) and the client (which only ever calls
    // its decode side), a single wrapped codec here replaces BOTH the old write-side
    // hook (this file) and the old read-side hook (previously a separate
    // MixinClientboundPlayerPositionPacket.java in the client package, now deleted as
    // redundant). Re-anchored onto `<clinit>` (after vanilla's own STREAM_CODEC
    // assignment) rather than a rename, since this is a genuine codec-composition
    // wrap, not a simple retarget -- same technique already used elsewhere in this
    // migration for overwriting a `static final` after its class's own <clinit> runs
    // (e.g. the SplashManager immutable-list fix used @Mutable on an instance field;
    // this is the same idea applied to a static one).
    @Shadow(remap = false)
    @Final
    @Mutable
    private static StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> STREAM_CODEC;
    
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void ip_wrapStreamCodec(CallbackInfo ci) {
        StreamCodec<FriendlyByteBuf, ClientboundPlayerPositionPacket> original = STREAM_CODEC;
        
        STREAM_CODEC = StreamCodec.of(
            (FriendlyByteBuf buf, ClientboundPlayerPositionPacket packet) -> {
                original.encode(buf, packet);
                // only the server ever encodes/sends this S2C packet; write
                // unconditionally, matching the pre-migration behavior exactly
                buf.writeResourceKey(((IEPlayerPositionLookS2CPacket) (Object) packet).ip_getPlayerDimension());
            },
            (FriendlyByteBuf buf) -> {
                ClientboundPlayerPositionPacket packet = original.decode(buf);
                // only the client ever decodes/receives this S2C packet; the
                // extra dimension data is only present if the server that sent
                // it is running ImmPtl (matching the pre-migration behavior)
                if (ImmPtlNetworkConfig.doesServerHaveImmPtl()) {
                    ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
                    ((IEPlayerPositionLookS2CPacket) (Object) packet).ip_setPlayerDimension(dimension);
                }
                return packet;
            }
        );
    }
}

