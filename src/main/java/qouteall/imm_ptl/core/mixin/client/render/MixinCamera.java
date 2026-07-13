package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(Camera.class)
public abstract class MixinCamera implements IECamera {
//    private static float lastClipSpaceResult = 1;
    
    @Shadow
    private Vec3 position;
    @Shadow
    private Level level;
    @Shadow
    private Entity entity;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    @Shadow
    private float fovModifier;
    @Shadow
    private float oldFovModifier;
    
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);
    
    // MC 26.1: Camera.getEntity() renamed to entity() (confirmed via javap) -- this
    // @Shadow was dead code though (never called anywhere in this file), so just removed.
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At("RETURN")
    )
    private void onUpdateFinished(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(FogType.NONE);
            cir.cancel();
        }
    }
    
//    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
//    private void onGetMaxZoomHead(float f, CallbackInfoReturnable<Float> cir) {
//        if (PortalRendering.isRendering()) {
//            cir.setReturnValue(lastClipSpaceResult);
//            cir.cancel();
//        }
//    }
//
//    // TODO using global variable to pass may be problematic when multiple camera objects are used
//    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
//    private void onGetMaxZoomReturn(float f, CallbackInfoReturnable<Float> cir) {
//        lastClipSpaceResult = cir.getReturnValue();
//    }
    
    // to let the player be rendered when rendering portal
    @Inject(method = "Lnet/minecraft/client/Camera;isDetached()Z", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (CrossPortalEntityRenderer.shouldRenderPlayerDefault()) {
            cir.setReturnValue(true);
        }
    }
    
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    @Override
    public float ip_getCameraY() {
        return eyeHeight;
    }
    
    @Override
    public float ip_getLastCameraY() {
        return eyeHeightOld;
    }
    
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }
    
    @Override
    public void portal_setFocusedEntity(Entity arg) {
        entity = arg;
    }
    
    @Override
    public void ip_setFovModifier(float modifier) {
        fovModifier = modifier;
        oldFovModifier = modifier;
    }
    
    // MC 26.1: relocated from MixinGameRenderer's wrapCameraTransformation -- the
    // Matrix4f.rotation(Quaternionfc) call it used to wrap was inline inside
    // GameRenderer.renderLevel before, now it's inside this class's own
    // getViewRotationMatrix(Matrix4f) instead (confirmed via decompiled source). See the
    // caching caveat noted in MixinGameRenderer.java where this used to live.
    @WrapOperation(
        method = "getViewRotationMatrix",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix4f;rotation(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;",
            remap = false
        )
    )
    private Matrix4f wrapCameraTransformation(
        Matrix4f instance, Quaternionfc quat, Operation<Matrix4f> original
    ) {
        Matrix4f r = original.call(instance, quat);
        return TransformationManager.processTransformation((Camera) (Object) this, r);
    }
}
