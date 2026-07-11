package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.q_misc_util.Helper;

public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();
    public final Level world;
    
    public final Lightmap lightmap;
    
    // only used when this helper is for a dimension other than the current one -
    // vanilla's GameRenderer already extracts/updates its own lightmap every frame
    // for the current dimension, so these are left null in that case
    private final LightmapRenderState renderState;
    private final LightmapRenderStateExtractor extractor;
    
    private final boolean isCurrentDimension;
    
    public DimensionRenderHelper(Level world) {
        this.world = world;
        
        if (client.level == world) {
            IEGameRenderer gameRenderer = (IEGameRenderer) client.gameRenderer;
            
            lightmap = gameRenderer.ip_getLightmap();
            renderState = null;
            extractor = null;
            isCurrentDimension = true;
        }
        else {
            lightmap = new Lightmap();
            renderState = new LightmapRenderState();
            extractor = new LightmapRenderStateExtractor(client.gameRenderer, client);
            isCurrentDimension = false;
            Helper.log("Created lightmap texture for " + world.dimension().identifier());
        }
    }
    
    public void tick() {
        if (!isCurrentDimension) {
            extractor.tick();
        }
    }
    
    /**
     * Force-updates this dimension's lightmap texture using the current (possibly
     * temporarily-swapped) client level/camera context. Equivalent to the old
     * {@code LightTexture#updateLightTexture(float)}. No-op for the current
     * dimension, since vanilla already updates its own lightmap every frame.
     */
    public void forceUpdate() {
        if (!isCurrentDimension) {
            extractor.extract(renderState, 0);
            lightmap.render(renderState);
        }
    }
    
    public void cleanUp() {
        if (!isCurrentDimension) {
            lightmap.close();
        }
    }
    
}

