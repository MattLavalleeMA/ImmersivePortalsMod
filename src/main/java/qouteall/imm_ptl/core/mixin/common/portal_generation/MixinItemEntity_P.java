package qouteall.imm_ptl.core.mixin.common.portal_generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager;

@Mixin(ItemEntity.class)
public abstract class MixinItemEntity_P {
    @Shadow
    public abstract ItemStack getItem();
    
    // MC 26.1: ItemEntity.thrower's type changed from a plain @Nullable UUID to
    // @Nullable EntityReference<Entity> (confirmed via decompiled 26.1.2 source, part
    // of the same EntityReference-based save-data-reference pattern used elsewhere in
    // this migration) -- still named `thrower`, just retyped. Only used here for a
    // null-check, so no further logic changes needed.
    @Shadow
    private @Nullable EntityReference<Entity> thrower;
    
    @Inject(
        method = "Lnet/minecraft/world/entity/item/ItemEntity;tick()V",
        at = @At("TAIL")
    )
    private void onItemTickEnded(CallbackInfo ci) {
        ItemEntity this_ = (ItemEntity) (Object) this;
        if (this_.isRemoved()) {
            return;
        }
        
        if (this_.level().isClientSide()) {
            return;
        }
        
        if (thrower == null) {
            return;
        }
        
        Profiler.get().push("imm_ptl_item_tick");
        
        CustomPortalGenManager customPortalGenManager =
            IPPerServerInfo.of(((ServerLevel) this_.level()).getServer()).customPortalGenManager;
        if (customPortalGenManager != null) {
            customPortalGenManager.onItemTick(this_);
        }
        
        Profiler.get().pop();
    }
}
