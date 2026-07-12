package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code ChunkMap.isExistingChunkFull(ChunkPos)}, which checks (via a cheap,
 * cached on-disk read) whether a specific chunk was actually previously generated to
 * "full" status, without forcing that chunk (or any of its neighbors) to be generated.
 *
 * This is more precise than checking whether the region file (a 32x32 chunk area) merely
 * exists on disk, which can give false positives when only a small, unrelated part of the
 * region (e.g. dim_stack's spawn-area setup) has been touched.
 */
@Mixin(ChunkMap.class)
public interface IEChunkMap_ExistingChunk {
    @Invoker("isExistingChunkFull")
    boolean ip_isExistingChunkFull(ChunkPos pos);
}
