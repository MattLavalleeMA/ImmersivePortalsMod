package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.my_util.RateStat;

import java.util.WeakHashMap;

/**
 * Each {@link ImmPtlChunkTickets} manages ImmPtl chunk tickets for one dimension.
 * <p>
 * Chunk tickets are added and removed via vanilla's own high-level
 * {@code ServerChunkCache#addTicketWithRadius}/{@code removeTicketWithRadius} API
 * (backed by {@link net.minecraft.world.level.TicketStorage}, and throttled
 * internally by vanilla's own chunk task dispatcher for every ticket type, not just
 * player tickets). This mod used to re-implement its own throttling on top of
 * vanilla's older, lower-level ticket/mailbox internals ({@code ChunkTaskPriorityQueueSorter}
 * / {@code ProcessorMailbox}) to avoid overloading world generation - those internals
 * no longer exist and are no longer necessary, since vanilla's own throttling now
 * covers this case directly.
 */
public class ImmPtlChunkTickets {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final TicketType TICKET_TYPE = Registry.register(
        BuiltInRegistries.TICKET_TYPE,
        Identifier.of("imm_ptl", "imm_ptl"),
        new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING)
    );
    
    // for debugging
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean enableDebugRateStat = false;
    private static final RateStat debugRateStat = new RateStat("imm_ptl_chunk_ticket");
    
    // the fields of ImmPtlChunkTickets should avoid referencing ServerLevel
    public static final WeakHashMap<ServerLevel, ImmPtlChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    public static void init() {
        DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.register(
            ImmPtlChunkTickets::onDimensionRemove
        );
        
        IPGlobal.SERVER_CLEANUP_EVENT.register(ImmPtlChunkTickets::cleanup);
    }
    
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        // the loading radius that was in effect when the ticket was added, so removal
        // can use the exact same radius (ticket level is derived from the radius, so
        // add/remove must match even if the global loading radius setting changes later)
        public final int radius;
        public boolean ticketAdded = false;
        
        public ChunkTicketInfo(int lastUpdateGeneration, int distanceToSource, int radius) {
            this.lastUpdateGeneration = lastUpdateGeneration;
            this.distanceToSource = distanceToSource;
            this.radius = radius;
        }
    }
    
    private final Long2ObjectOpenHashMap<ChunkTicketInfo> chunkPosToTicketInfo = new Long2ObjectOpenHashMap<>();
    
    private boolean isValid = true;
    
    private ImmPtlChunkTickets() {
    
    }
    
    // it takes in world instead of dimension id, to ensure dimension really exists
    public static ImmPtlChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new ImmPtlChunkTickets());
    }
    
    /**
     * Marks a chunk as wanted for loading. The actual ticket is added the next time
     * {@link #tick(ServerLevel)} runs (at most one tick of delay) - vanilla's own
     * chunk task dispatcher throttles the resulting generation work, so there is no
     * need to throttle ticket *adding* here anymore.
     */
    public void markForLoading(long chunkPos, int distanceToSource, int generation) {
        Validate.isTrue(distanceToSource >= 0);
        
        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
        
        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource, getLoadingRadius());
            chunkPosToTicketInfo.put(chunkPos, info);
        }
        else {
            info.lastUpdateGeneration = generation;
            if (distanceToSource < info.distanceToSource) {
                info.distanceToSource = distanceToSource;
            }
        }
    }
    
    public void tick(ServerLevel world) {
        if (!isValid) {
            LOGGER.error("ticking when invalid {}", world);
            return;
        }
        
        if (!world.getServer().isRunning()) {
            // important: don't add chunk ticket when server is saving
            // https://github.com/iPortalTeam/ImmersivePortalsMod/issues/1455
            return;
        }
        
        if (!IPConfig.getConfig().enableImmPtlChunkLoading) {
            return;
        }
        
        for (Long2ObjectMap.Entry<ChunkTicketInfo> entry : chunkPosToTicketInfo.long2ObjectEntrySet()) {
            ChunkTicketInfo info = entry.getValue();
            if (!info.ticketAdded) {
                addTicket(world, entry.getLongKey(), info.radius);
                info.ticketAdded = true;
            }
        }
    }
    
    private static void addTicket(ServerLevel world, long chunkPos, int radius) {
        world.getChunkSource().addTicketWithRadius(TICKET_TYPE, ChunkPos.unpack(chunkPos), radius);
        
        if (enableDebugRateStat) {
            debugRateStat.hit();
        }
    }
    
    private static void removeTicket(ServerLevel world, long chunkPos, int radius) {
        world.getChunkSource().removeTicketWithRadius(TICKET_TYPE, ChunkPos.unpack(chunkPos), radius);
    }
    
    public void purge(
        ServerLevel world,
        LongPredicate shouldKeepLoadingFunc
    ) {
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            ChunkTicketInfo ticketInfo = e.getValue();
            
            boolean keepLoading = shouldKeepLoadingFunc.test(chunkPos);
            
            if (!keepLoading) {
                if (ticketInfo.ticketAdded) {
                    removeTicket(world, chunkPos, ticketInfo.radius);
                }
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public int getLoadedChunkNum() {
        return chunkPosToTicketInfo.size();
    }
    
    public static void onDimensionRemove(ServerLevel world) {
        ImmPtlChunkTickets dimTicketManager = BY_DIMENSION.remove(world);
        
        if (dimTicketManager == null) {
            return;
        }
        
        removeAllTicketsInWorld(world, dimTicketManager);
    }
    
    private static void removeAllTicketsInWorld(ServerLevel world, ImmPtlChunkTickets dimTicketManager) {
        for (Long2ObjectMap.Entry<ChunkTicketInfo> entry : dimTicketManager.chunkPosToTicketInfo.long2ObjectEntrySet()) {
            ChunkTicketInfo info = entry.getValue();
            if (info.ticketAdded) {
                removeTicket(world, entry.getLongKey(), info.radius);
            }
        }
        
        dimTicketManager.isValid = false;
    }
    
    public static int getLoadingRadius() {
        if (IPGlobal.activeLoading) {
            return 2;
        }
        else {
            return 1;
        }
    }
    
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return ((IEServerChunkCache) world.getChunkSource()).ip_getDistanceManager();
    }
    
    private static void cleanup(MinecraftServer server) {
        for (ImmPtlChunkTickets immPtlChunkTickets : BY_DIMENSION.values()) {
            immPtlChunkTickets.isValid = false;
        }
        BY_DIMENSION.clear();
    }
}

