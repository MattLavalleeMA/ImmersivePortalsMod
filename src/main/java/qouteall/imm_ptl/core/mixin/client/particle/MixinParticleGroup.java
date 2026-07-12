package qouteall.imm_ptl.core.mixin.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// MC 26.1: relocated from MixinParticleEngine -- ParticleEngine.tickParticle(Particle) was
// removed, with per-particle ticking moved into this new dedicated ParticleGroup class
// (ParticleEngine.tick() now just calls group.tickParticles() per render-type group, which
// internally calls this class's own private tickParticle(Particle) per particle; confirmed
// via decompiled MC 26.1.2 source).
@Mixin(ParticleGroup.class)
public class MixinParticleGroup {
    // a lava ember particle can generate a smoke particle during ticking
    // avoid generating the particle into the wrong dimension
    @Inject(method = "tickParticle", at = @At("HEAD"), cancellable = true)
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            ci.cancel();
        }
    }
}
