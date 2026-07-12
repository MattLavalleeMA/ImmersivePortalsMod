package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
    // skip particle rendering for far portals
    @Inject(
        method = "extract",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBeginRenderParticles(
        ParticlesRenderState particlesRenderState, Frustum frustum, Camera camera, float f, CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            if (RenderStates.getRenderedPortalNum() > 4) {
                ci.cancel();
            }
        }
    }
    
    // TODO Particle.render(VertexConsumer, Camera, float) no longer exists - particle
    // rendering moved to a render-state-extraction pattern (see ParticleEngine.extract
    // above) with the actual per-particle geometry building happening elsewhere. This
    // needs research into the new pipeline before it can be ported (tracked as part of
    // the broader rendering-pipeline migration, not the lightmap redesign).
    // Previously used to skip building geometry for culled/hidden particles via
    // RenderStates.shouldRenderParticle(instance).
    // maybe incompatible with sodium and iris
    // @WrapWithCondition(
    //     method = "render",
    //     at = @At(
    //         value = "INVOKE",
    //         target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
    //     )
    // )
    // private boolean redirectBuildGeometry(
    //     Particle instance, VertexConsumer vertexConsumer, Camera camera, float v
    // ) {
    //     return RenderStates.shouldRenderParticle(instance);
    // }
    
    // a lava ember particle can generate a smoke particle during ticking
    // avoid generating the particle into the wrong dimension
    // MC 26.1: `ParticleEngine.tickParticle(Particle)` was moved to a new dedicated
    // `ParticleGroup` class (confirmed via decompiled source: ParticleEngine.tick() now
    // just calls `group.tickParticles()` per render-type group, which internally calls a
    // private `ParticleGroup.tickParticle(Particle)` per particle) -- re-anchored onto
    // that new class instead, see MixinParticleGroup.java.
    
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
}
