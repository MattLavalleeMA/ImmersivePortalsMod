package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;

// MC 26.1: same lerpTo(x,y,z,yaw,pitch,steps) removal as MixinLivingEntity_C.java --
// replaced by Entity.moveOrInterpolateTo(Vec3, float, float), declared on Entity
// itself (confirmed AbstractMinecart does not override it, via javap) -- so this
// mixin now targets Entity.class instead of AbstractMinecart.class. The minecart-only
// cast this previously did wasn't actually needed (setPos/allowClientEntityPosInterpolation
// are both generic Entity concerns), so no behavior change from widening the target.
@Mixin(Entity.class)
public abstract class MixinAbstractMinecartEntity {
    // for debugging
    @Inject(
        method = "moveOrInterpolateTo",
        at = @At("RETURN")
    )
    private void onUpdateTracketPositionAndAngles(
        Vec3 position, float yRot, float xRot, CallbackInfo ci
    ) {
        Entity this_ = (Entity) ((Object) this);
        if (!(this_ instanceof AbstractMinecart)) {
            return;
        }
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            this_.setPos(position);
        }
    }
}
