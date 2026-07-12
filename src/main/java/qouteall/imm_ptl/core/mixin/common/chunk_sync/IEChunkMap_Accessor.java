package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface IEChunkMap_Accessor {
    // MC 26.1: ChunkMap.getChunks(): Iterable<ChunkHolder> was removed with no
    // replacement method -- confirmed via javap, the backing data is now just the
    // private visibleChunkMap field (Long2ObjectLinkedOpenHashMap<ChunkHolder>).
    // Access the field directly via an @Accessor and call .values() at call sites.
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ip_getVisibleChunkMap();
}
