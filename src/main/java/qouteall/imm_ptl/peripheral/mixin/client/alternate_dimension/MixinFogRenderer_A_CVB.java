package qouteall.imm_ptl.peripheral.mixin.client.alternate_dimension;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;

@Mixin(FogRenderer.class)
public class MixinFogRenderer_A_CVB {
    //avoid alternate dimension dark when seeing from overworld
    // TODO MC 26.1: FogRenderer.setupColor(Camera,float,ClientLevel,int,float) (static,
    // void) was replaced by the instance method setupFog(Camera,int,DeltaTracker,float,
    // ClientLevel), which delegates its actual color/darkness computation to a new
    // private computeFogColor(Camera,float,ClientLevel,int,float,Vector4f) helper
    // (confirmed via decompiled source) -- that's the method that now directly reads
    // camera.position().y for the void-darkness falloff calc this redirect exists to
    // override, so the injection target moved there. Camera.getPosition() was also
    // renamed to Camera.position() (confirmed via javap) -- this file's own handler
    // body already called the new name, only the two Mixin string targets were stale.
    @Redirect(
        method = "Lnet/minecraft/client/renderer/fog/FogRenderer;computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IFLorg/joml/Vector4f;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private static Vec3 redirectCameraGetPos(Camera camera) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null && AlternateDimensions.isAlternateDimension(world)) {
            return new Vec3(
                camera.position().x,
                Math.max(32.0, camera.position().y),
                camera.position().z
            );
        }
        else {
            return camera.position();
        }
    }
}
