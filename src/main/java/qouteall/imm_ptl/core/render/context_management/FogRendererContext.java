package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.util.profiling.Profiler;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;

/**
 * {@link FogRenderer}
 * <p>
 * MC 26.1 rewrote {@code FogRenderer} from a bag of static per-dimension
 * color/biome-transition-tracking fields into a plain instance whose {@code
 * setupFog(Camera, int, DeltaTracker, float, ClientLevel)} is a pure function
 * of its arguments (confirmed via decompiled source and {@code javap
 * --private}: the only instance fields left are GPU buffers, no mutable
 * per-dimension color state left to track at all). This means the old
 * cross-dimension trick this class used to use -- a {@code MixinFogRenderer}
 * that shadowed {@code FogRenderer}'s old static {@code
 * fogRed}/{@code fogGreen}/{@code fogBlue}/{@code targetBiomeFog}/{@code
 * previousBiomeFog}/{@code biomeChangedTime} fields and swapped them in/out
 * per-dimension via a generic static-field-swapping helper -- is both
 * unnecessary and impossible now (none of those fields exist anymore, so that
 * mixin would have hard-crashed Mixin weaving at game launch; it has been
 * removed entirely, along with the now-fully-unused swapping helper). Fog
 * data for any (level, camera) pair can simply be recomputed directly via
 * {@code setupFog} instead, with no state to swap.
 */
public class FogRendererContext {
    // lazily-created and reused (not per-call) to avoid leaking the GPU buffers a
    // FogRenderer instance allocates in its constructor
    private static FogRenderer fogRendererForColorQuery;
    
    private static FogRenderer getFogRendererForColorQuery() {
        if (fogRendererForColorQuery == null) {
            fogRendererForColorQuery = new FogRenderer();
        }
        return fogRendererForColorQuery;
    }
    
    /**
     * Computes the fog color of {@code destWorld} at {@code pos}, without
     * actually switching the client's current world/camera. Used for
     * cross-dimension queries -- for the fog actually used while rendering a
     * dimension, see
     * {@link qouteall.imm_ptl.core.render.MyGameRenderer#switchAndRenderTheWorld}.
     */
    public static Vec3 getFogColorOf(
        ClientLevel destWorld, Vec3 pos
    ) {
        Minecraft client = Minecraft.getInstance();
        
        Profiler.get().push("get_fog_color");
        
        try {
            Camera camera = new Camera();
            ((IECamera) camera).portal_setPos(pos);
            ((IECamera) camera).portal_setFocusedEntity(client.getCameraEntity());
            
            FogData fogData = getFogRendererForColorQuery().setupFog(
                camera,
                client.options.getEffectiveRenderDistance(),
                RenderStates.fixedDeltaTracker(RenderStates.getPartialTick()),
                client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick()),
                destWorld
            );
            
            Vector4f color = fogData.color;
            
            return new Vec3(color.x(), color.y(), color.z());
        }
        finally {
            Profiler.get().pop();
        }
    }
    
    /**
     * The fog color of whatever dimension/camera is actually being rendered
     * right now (recomputed fresh via {@code setupFog}, using the shared
     * {@code GameRenderer}'s own {@code FogRenderer} instance so this matches
     * exactly what the real render pass will show). While rendering portal
     * content, {@code client.gameRenderer.getMainCamera()}/{@code
     * client.level} are the swapped-in portal-side camera/world (see {@link
     * qouteall.imm_ptl.core.render.MyGameRenderer#switchAndRenderTheWorld}),
     * so this naturally reflects the current render target rather than the
     * outer/main world.
     */
    public static Vec3 getCurrentFogColor() {
        Minecraft client = Minecraft.getInstance();
        
        Vector4f color = ((IEGameRenderer) client.gameRenderer)
            .ip_getFogRenderer()
            .setupFog(
                client.gameRenderer.getMainCamera(),
                client.options.getEffectiveRenderDistance(),
                client.getDeltaTracker(),
                client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick()),
                client.level
            )
            .color;
        
        return new Vec3(color.x(), color.y(), color.z());
    }
}

