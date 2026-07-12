package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEAbstractClientPlayer;

// MC 26.1: AbstractClientPlayer no longer declares its own `clientLevel` field at all
// -- confirmed via decompiled 26.1.2 source: it just forwards its level straight to the
// shared Entity.level field via `super(level, gameProfile)` in its constructor, same as
// every other Entity subclass. Mixin's @Shadow resolution only matches fields declared
// directly in the exact target class's own bytecode (confirmed: targeting
// AbstractClientPlayer.class and shadowing `level` still failed, since `level` is
// declared on Entity, not AbstractClientPlayer) -- so this mixin now targets
// Entity.class directly instead, matching the same field MixinEntity.java (in the
// collision package) already shadows there successfully.
@Mixin(Entity.class)
public abstract class MixinAbstractClientPlayer implements IEAbstractClientPlayer {
    @Shadow
    private Level level;
    
    @Override
    public void ip_setClientLevel(ClientLevel clientWorld) {
        level = clientWorld;
    }
}
