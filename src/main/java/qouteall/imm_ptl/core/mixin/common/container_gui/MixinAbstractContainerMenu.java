package qouteall.imm_ptl.core.mixin.common.container_gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

@Mixin(AbstractContainerMenu.class)
public class MixinAbstractContainerMenu {
    // MC 26.1: the old intermediary/Yarn-mapped lambda name `method_17696` is
    // meaningless now that MC ships unobfuscated -- the real (deterministic,
    // compiler-generated) name of this lambda, confirmed via `javap -p` on the real
    // class, is `lambda$stillValid$0(Block, Player, Level, BlockPos): Boolean` (the
    // capture-then-declared-params order of the lambda
    // `(level, pos) -> ... player.isWithinBlockInteractionRange(pos, 4.0)` inside
    // `AbstractContainerMenu.stillValid(ContainerLevelAccess, Player, Block)`, confirmed
    // via decompiled source). Also renamed the wrapped call itself:
    // Player.canInteractWithBlock(BlockPos, double) -> isWithinBlockInteractionRange
    // (same signature), same rename as MixinContainer.java's sibling fix.
    @WrapOperation(
        method = "lambda$stillValid$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z"
        )
    )
    private static boolean wrapDistanceToSqr(
        Player player, BlockPos blockPos, double distance,
        Operation<Boolean> operation,
        @Local(argsOnly = true) Level world
    ) {
        boolean canInteract = operation.call(player, blockPos, distance);
        if (canInteract) {
            return true;
        }
        
        return BlockManipulationServer.validateReach(player, world, blockPos);
    }
}
