package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.util.profiling.Profiler;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.Map;
import java.util.Set;

public class WorldInfoSender {
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register((server) -> {
            Profiler.get().push("portal_send_world_info");
            if (McHelper.getServerGameTime() % 100 == 42) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    Set<ResourceKey<Level>> visibleDimensions = ImmPtlChunkTracking.getVisibleDimensions(player);
                    
                    // sync overworld status when the player is not in overworld
                    if (player.level().dimension() != Level.OVERWORLD) {
                        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
                        Validate.notNull(overworld, "missing overworld");
                        sendWorldInfo(player, overworld);
                    }
                    
                    server.getAllLevels().forEach(thisWorld -> {
                        if (isNonOverworldSurfaceDimension(thisWorld)) {
                            if (visibleDimensions.contains(thisWorld.dimension())) {
                                sendWorldInfo(player, thisWorld);
                            }
                        }
                    });
                    
                }
            }
            Profiler.get().pop();
        });
    }
    
    //send the daytime and weather info to player when player is in nether
    public static void sendWorldInfo(ServerPlayer player, ServerLevel world) {
        ResourceKey<Level> remoteDimension = world.dimension();
        
        // TODO MC 26.1: the day/night clock system was redesigned into a global (not
        // per-dimension) net.minecraft.world.clock.WorldClock/ServerClockManager registry
        // -- ClientboundSetTimePacket now carries a Map<Holder<WorldClock>,
        // ClockNetworkState> snapshot instead of a single dayTime/daylightCycle pair, and
        // vanilla itself already broadcasts clock updates to every player regardless of
        // dimension (confirmed via decompiled ServerClockManager.modifyClock), which may
        // make part of this method's original purpose redundant now -- needs real-game
        // verification. Best-effort translation below: build a single-entry map for this
        // dimension's own default clock (if it has one), matching the old dayTime/
        // daylightCycle semantics (rate 1.0/0.0) as closely as possible.
        Map<Holder<WorldClock>, ClockNetworkState> clockUpdates = world.dimensionType().defaultClock()
            .map(clock -> Map.of(
                clock,
                new ClockNetworkState(
                    world.getOverworldClockTime(),
                    0.0F,
                    world.getGameRules().get(GameRules.ADVANCE_TIME) ? 1.0F : 0.0F
                )
            ))
            .orElse(Map.of());
        
        PacketRedirection.sendRedirectedMessage(
            player,
            remoteDimension,
            new ClientboundSetTimePacket(
                world.getGameTime(),
                clockUpdates
            )
        );
        
        /**{@link net.minecraft.client.network.ClientPlayNetworkHandler#onGameStateChange(GameStateChangeS2CPacket)}*/
        
        if (world.isRaining()) {
            PacketRedirection.sendRedirectedMessage(
                player,
                world.dimension(),
                new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.START_RAINING,
                    0.0F
                )
            );
        }
        else {
            //if the weather is already not raining when the player logs in then no need to sync
            //if the weather turned to not raining then elsewhere syncs it
        }
        
        PacketRedirection.sendRedirectedMessage(
            player,
            world.dimension(),
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
                world.getRainLevel(1.0F)
            )
        );
        PacketRedirection.sendRedirectedMessage(
            player,
            world.dimension(),
            new ClientboundGameEventPacket(
                ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
                world.getThunderLevel(1.0F)
            )
        );
    }
    
    public static boolean isNonOverworldSurfaceDimension(Level world) {
        return world.dimensionType().hasSkyLight() && world.dimension() != Level.OVERWORLD;
    }
}
