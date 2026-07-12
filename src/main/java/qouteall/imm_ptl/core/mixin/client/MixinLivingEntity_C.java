package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

// MC 26.1: LivingEntity's old raw lerpX/lerpY/lerpZ/lerpSteps fields are gone --
// confirmed via decompiled 26.1.2 source: entity position interpolation was redesigned
// around a dedicated net.minecraft.world.entity.InterpolationHandler object
// (Entity.getInterpolation(), .position()/.yRot()/.xRot()/.hasActiveInterpolation()),
// and the old 6-arg LivingEntity.lerpTo(x,y,z,yaw,pitch,steps) entry point is gone too
// (no matching method anywhere in the class, confirmed via javap) -- replaced by
// Entity.moveOrInterpolateTo(Vec3 position, float yRot, float xRot), defined on Entity
// itself (not overridden by LivingEntity), hence this mixin now targets Entity.class
// instead of LivingEntity.class.
@Mixin(Entity.class)
public class MixinLivingEntity_C {
    // avoid entity position interpolate when crossing portal to the same dimension
    @Inject(
        method = "moveOrInterpolateTo",
        at = @At("RETURN")
    )
    private void onUpdateTrackedPositionAndAngles(
        Vec3 position,
        float yRot,
        float xRot,
        CallbackInfo ci
    ) {
        Entity this_ = ((Entity) (Object) this);
        
        if (!(this_ instanceof LivingEntity)) {
            return;
        }
        
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            this_.setPos(position);
            return;
        }
        
        Portal collidingPortal = ((IEEntity) this).ip_getCollidingPortal();
        if (collidingPortal != null) {
            InterpolationHandler interpolation = this_.getInterpolation();
            
            if (interpolation != null) {
                Vec3 lerpPos = interpolation.position();
                
                double dx = this_.getX() - lerpPos.x;
                double dy = this_.getY() - lerpPos.y;
                double dz = this_.getZ() - lerpPos.z;
                if (dx * dx + dy * dy + dz * dz > 4) {
                    McHelper.setPosAndLastTickPos(
                        this_,
                        lerpPos,
                        lerpPos.subtract(McHelper.getWorldVelocity(this_))
                    );
                    McHelper.updateBoundingBox(this_);
                }
            }
        }
    }
}

