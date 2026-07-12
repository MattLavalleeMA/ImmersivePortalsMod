package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;

@Mixin(value = Viewport.class, remap = false)
public class MixinSodiumViewport {
    // MC 26.1 / Sodium 0.9.1: Viewport's old 6-float-corners `isBoxVisible(float,
    // float, float, float, float, float)` was replaced by `isBoxVisible(int, int,
    // int)` (a totally different, testSection-based single-point check) plus a new
    // `isBoxVisibleDirect(float x, float y, float z, float radius)` (center+radius
    // shape) -- confirmed via javap/bytecode that `isBoxVisibleDirect` is the one
    // that still internally expands to 6 corners and calls
    // `Frustum.testAab(FFFFFF)Z` (same descriptor as before), so it's the correct
    // re-anchor target. The `@Redirect` itself doesn't need to change at all --
    // it redirects the inner `testAab` call, whose own 6-float-corners signature is
    // unchanged; only the outer `method` selector needed updating to the new name.
    @Redirect(
        method = "isBoxVisibleDirect",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testAab(FFFFFF)Z"
        )
    )
    private boolean redirectTestAab(
        Frustum instance,
        float minX, float minY, float minZ, float maxX, float maxY, float maxZ
    ) {
        boolean inFrustum = instance.testAab(
            minX, minY, minZ, maxX, maxY, maxZ
        );
        
        if (inFrustum) {
            if (SodiumInterface.frustumCuller != null) {
                boolean canDetermineInvisible =
                    SodiumInterface.frustumCuller.canDetermineInvisibleWithCameraCoord(
                        minX, minY, minZ, maxX, maxY, maxZ
                    );
                return !canDetermineInvisible;
            }
        }
        
        return inFrustum;
    }
}
