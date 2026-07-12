package qouteall.imm_ptl.core.ducks;

import net.minecraft.world.entity.Entity;

// Added for MC 26.1: EntityRenderState no longer carries a back-reference to the source
// Entity it was extracted from (vanilla itself has no need for one), but
// CrossPortalEntityRenderer.beforeRenderingEntity/afterRenderingEntity need the real
// Entity (they key a WeakHashMap<Entity, ...> and read IEEntity duck state off it).
// Stashed onto the state itself by MixinEntityRenderState, set right after
// LevelRenderer.extractEntity(...) produces it (see MixinLevelRenderer.java) -- the same
// technique MeteorDevelopment/meteor-client uses for the identical problem (its own
// IEntityRenderState.meteor$getEntity() duck).
public interface IEEntityRenderState {
    Entity ip_getEntity();
    
    void ip_setEntity(Entity entity);
}
