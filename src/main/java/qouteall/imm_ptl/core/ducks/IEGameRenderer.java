package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;

public interface IEGameRenderer {
    void ip_setLightmapTextureManager(Lightmap manager);
    
    Lightmap ip_getLightmap();
    
    boolean ip_getDoRenderHand();
    
    void ip_setDoRenderHand(boolean doRenderHand);
    
    FogRenderer ip_getFogRenderer();
    
    void ip_setCamera(Camera camera);
}
