package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.ducks.IEEntityRenderState;

// See IEEntityRenderState for why this exists.
@Mixin(EntityRenderState.class)
public class MixinEntityRenderState implements IEEntityRenderState {
    @Unique
    private Entity ip_entity;
    
    @Override
    public Entity ip_getEntity() {
        return ip_entity;
    }
    
    @Override
    public void ip_setEntity(Entity entity) {
        this.ip_entity = entity;
    }
}
