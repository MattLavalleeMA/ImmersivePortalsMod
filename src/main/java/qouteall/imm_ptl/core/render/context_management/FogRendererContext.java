package qouteall.imm_ptl.core.render.context_management;

import net.minecraft.util.profiling.Profiler;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IECamera;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link FogRenderer}
 * {@link qouteall.imm_ptl.core.mixin.client.multiworld_awareness.MixinFogRenderer}
 */
@SuppressWarnings("SpellCheckingInspection")
public class FogRendererContext {
    public float red;
    public float green;
    public float blue;
    public int targetBiomeFog = -1;
    public int previousBiomeFog = -1;
    public long biomeChangedTime = -1L;
    
    public static Consumer<FogRendererContext> copyContextFromObject;
    public static Consumer<FogRendererContext> copyContextToObject;
    public static Supplier<Vec3> getCurrentFogColor;
    
    public static StaticFieldsSwappingManager<FogRendererContext> swappingManager;
    
    public static void init() {
        //load the class and apply mixin
        FogRenderer.class.hashCode();
        
        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject, false,
            FogRendererContext::new
        );
        
        
    }
    
    public static void update() {
        swappingManager.setOuterDimension(RenderStates.originalPlayerDimension);
        swappingManager.resetChecks();
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.getClientWorlds().forEach(world -> {
                ResourceKey<Level> dimension = world.dimension();
                swappingManager.contextMap.computeIfAbsent(
                    dimension,
                    k -> new StaticFieldsSwappingManager.ContextRecord<>(
                        dimension,
                        new FogRendererContext(),
                        dimension != RenderStates.originalPlayerDimension
                    )
                );
            });
        }
    }
    
    public static Vec3 getFogColorOf(
        ClientLevel destWorld, Vec3 pos
    ) {
        Minecraft client = Minecraft.getInstance();
        
        Profiler.get().push("get_fog_color");
        
        ClientLevel oldWorld = client.level;
        
        ResourceKey<Level> newWorldKey = destWorld.dimension();
        
        swappingManager.contextMap.computeIfAbsent(
            newWorldKey,
            k -> new StaticFieldsSwappingManager.ContextRecord<>(
                k, new FogRendererContext(), true
            )
        );
        
        swappingManager.pushSwapping(newWorldKey);
        client.level = destWorld;
        
        Camera newCamera = new Camera();
        ((IECamera) newCamera).portal_setPos(pos);
        ((IECamera) newCamera).portal_setFocusedEntity(client.getCameraEntity());
        
        try {
            // TODO MC 26.1: FogRenderer.setupColor(Camera,float,ClientLevel,int,float) was
            // replaced by an instance method FogRenderer#setupFog(Camera,int,DeltaTracker,
            // float,ClientLevel) that returns the computed FogData directly (color is now
            // GPU-buffer-driven, no more static red/green/blue fields to read back via
            // getCurrentFogColor) -- confirmed via GameRenderer.extractCamera's real call
            // site, which also confirmed getDarkenWorldAmount(float) was renamed to
            // getBossOverlayWorldDarkening(float). Uses its own cached FogRenderer instance
            // (rather than GameRenderer's private one, which isn't exposed) so this doesn't
            // depend on MixinFogRenderer's now-broken static-field shadowing at all.
            FogData fogData = getFogRendererForColorQuery().setupFog(
                newCamera,
                client.options.getEffectiveRenderDistance(),
                RenderStates.fixedDeltaTracker(RenderStates.getPartialTick()),
                client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick()),
                destWorld
            );
            
            Vector4f color = fogData.color;
            
            return new Vec3(color.x(), color.y(), color.z());
        }
        finally {
            swappingManager.popSwapping();
            client.level = oldWorld;
            
            Profiler.get().pop();
        }
    }
    
    // lazily-created and reused (not per-call) to avoid leaking the GPU buffers a
    // FogRenderer instance allocates in its constructor
    private static FogRenderer fogRendererForColorQuery;
    
    private static FogRenderer getFogRendererForColorQuery() {
        if (fogRendererForColorQuery == null) {
            fogRendererForColorQuery = new FogRenderer();
        }
        return fogRendererForColorQuery;
    }
    
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        swappingManager.updateOuterDimensionAndChangeContext(to);
    }
    
}
