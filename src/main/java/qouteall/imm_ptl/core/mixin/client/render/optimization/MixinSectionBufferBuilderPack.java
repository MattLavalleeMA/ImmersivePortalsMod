package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.IPGlobal;

@Mixin(SectionBufferBuilderPack.class)
public class MixinSectionBufferBuilderPack {
    // The buffer can grow size on demand.
    // There is no need to allocate a large buffer at the beginning.
    // That's not an issue of vanilla,
    // but with ImmPtl, each loaded dimension will have some buffer packs,
    // so memory could be exhausted.
    // This mixin will reduce memory usage.
    // The initial size cannot be 0, because it resizes in endVertex(), not before putting data.
    // MC 26.1: the old intermediary-mapped `method_60896` target no longer exists at all
    // now that MC ships unobfuscated -- the buffer-size lookup this mixin overrides moved
    // from `RenderType.bufferSize()` to `ChunkSectionLayer.bufferSize()` too (confirmed via
    // decompiled source: `buffers = Util.makeEnumMap(ChunkSectionLayer.class, layer -> new
    // ByteBufferBuilder(layer.bufferSize()))`, RenderType isn't referenced here anymore at
    // all). Re-anchored to the real compiled lambda name (`lambda$new$0`, confirmed via
    // javap) and the new ChunkSectionLayer-based call.
    @Redirect(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;bufferSize()I"
        )
    )
    private static int redirectBufferSize(ChunkSectionLayer instance) {
        if (!IPGlobal.saveMemoryInBufferPack) {
            return instance.bufferSize();
        }
        
        return Math.min(128, instance.bufferSize());
    }
}
