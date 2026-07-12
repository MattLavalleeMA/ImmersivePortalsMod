package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.UUID;

@Mixin(Projectile.class)
public abstract class MixinProjectile extends MixinEntity {
    
    // make it recognize the owner in another dimension
    // MC 26.1: Projectile.getOwner() no longer calls ServerLevel.getEntity(UUID)
    // directly -- confirmed via decompiled 26.1.2 source: it now delegates to
    // `EntityReference.getEntity(this.owner, this.level())` (same EntityReference
    // pattern used elsewhere this migration, e.g. ItemEntity.thrower). Re-anchored to
    // redirect that static call instead: try the normal single-level lookup first,
    // then fall back to searching every loaded ServerLevel by UUID, same as before.
    @Redirect(
        method = "getOwner",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityReference;getEntity(Lnet/minecraft/world/entity/EntityReference;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private Entity redirectGetEntityFromUuid(
        EntityReference<Entity> reference, Level level
    ) {
        Entity entity = EntityReference.getEntity(reference, level);
        if (entity != null) {
            return entity;
        }
        
        if (reference != null && level instanceof ServerLevel serverLevel) {
            UUID uuid = reference.getUUID();
            if (uuid != null) {
                MinecraftServer server = serverLevel.getServer();
                for (ServerLevel world : server.getAllLevels()) {
                    Entity found = world.getEntity(uuid);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        
        return null;
    }
    
//    @Shadow
//    public abstract void onHit(HitResult hitResult);
//
//    @Inject(method = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "HEAD"), cancellable = true)
//    protected void onHit(HitResult hitResult, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (hitResult instanceof BlockHitResult) {
//            Block hittingBlock = this_.level().getBlockState(((BlockHitResult) hitResult).getBlockPos()).getBlock();
//            if (hitResult.getType() == HitResult.Type.BLOCK &&
//                hittingBlock == PortalPlaceholderBlock.instance
//            ) {
//                ci.cancel();
//            }
//        }
//    }
//
    
}