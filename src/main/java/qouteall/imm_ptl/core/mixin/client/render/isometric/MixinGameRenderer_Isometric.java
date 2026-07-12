package qouteall.imm_ptl.core.mixin.client.render.isometric;

import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.TransformationManager;

// MC 26.1: GameRenderer.getProjectionMatrix(double fov) was removed entirely (confirmed
// via javap) -- the projection matrix is no longer computed by a single overridable
// method call at all. It's now built inline inside renderLevel() as a local
// `Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix)` (base value
// from the extracted CameraRenderState), then mutated in-place for view-bobbing/portal-
// spin effects before being uploaded (confirmed via decompiled source). Re-anchored from
// overriding the old method's return value to overriding that first local Matrix4f
// assignment instead, via @ModifyVariable/@At("STORE") (unambiguous: it's the only
// Matrix4f-typed local in this method, `modelViewMatrix` right above it is the Matrix4fc
// interface type instead).
@Mixin(GameRenderer.class)
public class MixinGameRenderer_Isometric {
    @ModifyVariable(
        method = "renderLevel",
        at = @At("STORE"),
        ordinal = 0
    )
    private Matrix4f onGetBasicProjectionMatrix(Matrix4f matrix) {
        if (TransformationManager.isIsometricView) {
            return TransformationManager.getIsometricProjection();
        }
        return matrix;
    }
}
