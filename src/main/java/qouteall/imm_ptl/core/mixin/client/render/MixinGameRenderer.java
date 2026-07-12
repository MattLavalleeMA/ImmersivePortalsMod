package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.util.profiling.Profiler;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.CrossPortalViewRendering;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.Helper;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer implements IEGameRenderer {
    @Shadow
    @Final
    @Mutable
    private Lightmap lightmap;
    
    @Shadow
    @Final
    private FogRenderer fogRenderer;
    
    // GameRenderer's own `renderHand` field was removed entirely (not just made
    // inaccessible -- confirmed via javap that no such field exists anymore), so
    // this can no longer be a @Shadow. Tracked independently here instead. Note
    // this flag isn't actually wired into anything that skips rendering the hand
    // yet -- that's separate portal-rendering-algorithm work, not related to the
    // onRenderHandBegins/onRenderHandEnds hooks below (those were fixed to match
    // GameRenderer.renderItemInHand's new CameraRenderState/Matrix4fc signature).
    @Unique
    private boolean renderHand = true;
    @Shadow
    @Final
    @Mutable
    private Camera mainCamera;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    // MC 26.1: GameRenderer.panoramicMode field removed entirely (confirmed via javap, no
    // replacement found under any name) -- this @Shadow's only consumer,
    // ip_setIsRenderingPanorama, was itself dead code (never called anywhere in this
    // codebase), so removed both rather than chase a replacement for an unused hook.
    
    // TODO MC 26.1: GameRenderer.resetProjectionMatrix(Matrix4f) was removed entirely
    // (confirmed via javap) -- callers now use RenderSystem.backupProjectionMatrix()/
    // .restoreProjectionMatrix() instead (see MyGameRenderer.java). Removed this dead
    // @Shadow since nothing in this file used it and the target no longer exists.
    
    // MC 26.1: bobView(PoseStack, float) changed to bobView(CameraRenderState, PoseStack)
    // (confirmed via javap) -- this @Shadow was never actually called from within this
    // file though (only referenced by name in the @ModifyArg targets below, which don't
    // need the descriptor to match), so just removed rather than updated, same as the
    // resetProjectionMatrix @Shadow above.
    
    @Shadow @Final private static Logger LOGGER;
    
    @Inject(method = "render", at = @At("HEAD"))
    private void onFarBeforeRendering(
        DeltaTracker deltaTracker, boolean renderWorldIn, CallbackInfo ci
    ) {
        Profiler.get().push("ip_pre_total_render");
        IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks();
        Profiler.get().pop();
        if (minecraft.level == null) {
            return;
        }
        if (!renderWorldIn) { // when respawning, it will runTick and execute rendering
            return;
        }
        Profiler.get().push("ip_pre_render");
        // Note do not use delta tick. use partial tick.
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        RenderStates.updatePreRenderInfo(partialTick);
        StableClientTimer.update(
            minecraft.level.getGameTime(), partialTick
        );
        ClientPortalAnimationManagement.update(); // must update before teleportation
        ClientTeleportationManager.manageTeleportation(false);
        IPGlobal.PRE_GAME_RENDER_EVENT.invoker().run();
        if (IPCGlobal.earlyRemoteUpload) {
            MyRenderHelper.earlyRemoteUpload();
        }
        Profiler.get().pop();
        
        RenderStates.frameIndex++;
    }
    
    //before rendering world (not triggered when rendering portal)
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void onBeforeRenderingCenter(
        DeltaTracker deltaTracker, boolean bl, CallbackInfo ci
    ) {
        PortalRenderer.switchToCorrectRenderer();
        
        IPCGlobal.renderer.prepareRendering();
    }
    
    //after rendering world (not triggered when rendering portal)
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterRenderingCenter(
        DeltaTracker deltaTracker, boolean bl, CallbackInfo ci
    ) {
        IPCGlobal.renderer.finishRendering();
        
        RenderStates.onTotalRenderEnd();
        
        GuiPortalRendering._onGameRenderEnd();
        
        if (IPCGlobal.lateClientLightUpdate) {
            Profiler.get().push("ip_late_update_light");
            MyRenderHelper.lateUpdateLight();
            Profiler.get().pop();
        }
    }
    
    //special rendering in third person view
    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"
        )
    )
    private void redirectRenderingWorld(
        GameRenderer gameRenderer, DeltaTracker deltaTracker
    ) {
        if (CrossPortalViewRendering.renderCrossPortalView()) {
            return;
        }
        
        gameRenderer.renderLevel(deltaTracker);
    }
    
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderCenterEnded(
        DeltaTracker deltaTracker, CallbackInfo ci
    ) {
        IPCGlobal.renderer.onHandRenderingEnded();
    }
    
    // TODO MC 26.1: the old WrapOperation targeted
    // LevelRenderer.renderLevel(DeltaTracker,boolean,Camera,GameRenderer,LightTexture,
    // Matrix4f,Matrix4f), which was completely restructured (now
    // LevelRenderer.renderLevel(GraphicsResourceAllocator,DeltaTracker,boolean,
    // CameraRenderState,Matrix4fc,GpuBufferSlice,Vector4f,boolean,ChunkSectionsToRender),
    // and most of the actual translucent/entity rendering now happens inside a
    // FrameGraphBuilder pass lambda built by LevelRenderer.addMainPass, not sequentially
    // in renderLevel's own body - the old "before hand rendering" anchor point doesn't
    // exist in the same form anymore. Using the renderItemInHand(...) call within
    // GameRenderer.renderLevel (which still runs right after LevelRenderer.renderLevel
    // returns) as the new anchor instead.
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"
        )
    )
    private void ip_onBeforeHandRendering(
        DeltaTracker deltaTracker, CallbackInfo ci, @Local(ordinal = 0) Matrix4fc modelViewMatrix
    ) {
        IPCGlobal.renderer.onBeforeHandRendering(new Matrix4f(modelViewMatrix));
    }
    
    //resize all world renderers when resizing window
    @Inject(method = "Lnet/minecraft/client/renderer/GameRenderer;resize(II)V", at = @At("RETURN"))
    private void onOnResized(int int_1, int int_2, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.WORLD_RENDERER_MAP.values().stream()
                .filter(
                    worldRenderer -> worldRenderer != minecraft.levelRenderer
                )
                .forEach(
                    worldRenderer -> worldRenderer.resize(int_1, int_2)
                );
        }
    }
    
    private static boolean portal_isRenderingHand = false;
    
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void onRenderHandBegins(
        net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState,
        float f, Matrix4fc matrix4fc, CallbackInfo ci
    ) {
        portal_isRenderingHand = true;
    }
    
    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void onRenderHandEnds(
        net.minecraft.client.renderer.state.level.CameraRenderState cameraRenderState,
        float f, Matrix4fc matrix4fc, CallbackInfo ci
    ) {
        portal_isRenderingHand = false;
    }
    
    // not using ModifyArgs because ModifyArgs seems broken on Forge
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 0
    )
    private float modifyBobViewTranslateX(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }
    
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 1
    )
    private float modifyBobViewTranslateY(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }
    
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 2
    )
    private float modifyBobViewTranslateZ(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }


//    @Redirect(
//        method = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
//        at = @At(
//            value = "INVOKE",
//            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"
//        )
//    )
//    private void redirectBobViewTranslate(PoseStack matrixStack, float x, float y, float z) {
//        if (portal_isRenderingHand) {
//            matrixStack.translate(x, y, z);
//        }
//        else {
//            double multiplier = RenderStates.getViewBobbingOffsetMultiplier();
//            matrixStack.translate(
//                x * multiplier, y * multiplier, z * multiplier
//            );
//        }
//    }
    
    // make sure that the portal rendering basic projection matrix is right
    // the basic projection matrix does not contain view bobbing
    // TODO MC 26.1: the old redirect targeted GameRenderer.getProjectionMatrix(double),
    // which no longer exists (projection matrix is now computed via
    // Camera.extractRenderState into CameraRenderState.projectionMatrix during the new
    // separate "extract" phase, before this render() call happens at all). Capturing the
    // pre-view-bobbing matrix here (right when the local var is first assigned, before
    // .mul(bobStack...)/.rotate(...) mutate it in place) is a reasonable equivalent, but
    // the old ability to OVERRIDE it with a previously-captured value while
    // PortalRendering.isRendering() (to keep nested portal-content renders consistent
    // with the outer render's bobbing) is not reproduced here and may need revisiting
    // with in-game testing.
    @ModifyVariable(method = "renderLevel", at = @At("STORE"), ordinal = 0)
    private Matrix4f ip_captureBasicProjectionMatrix(Matrix4f projectionMatrix) {
        RenderStates.basicProjectionMatrix = new Matrix4f(projectionMatrix);
        return projectionMatrix;
    }
    
    // MC 26.1: the `Matrix4f.rotation(Quaternionfc)` call this used to wrap no longer
    // happens inside GameRenderer.renderLevel at all -- the view-rotation matrix is now
    // computed (and cached) inside Camera.getViewRotationMatrix(Matrix4f) itself, called
    // from Camera.setup()/extractRenderState() during the earlier "extract" phase, not
    // from renderLevel (confirmed via decompiled source). Moved to MixinCamera.java,
    // wrapping that method's own internal rotation(...) call instead -- see
    // wrapCameraTransformation there. NOTE: Camera.getViewRotationMatrix() caches its
    // result behind a dirty-flag check now (only recomputes when the camera's rotation
    // actually changed), unlike the old renderLevel-inline call which ran fresh every
    // single render. This could mean nested-portal-render transformations don't get
    // reapplied as often as before if the outer camera's own rotation didn't change
    // between renders -- flagged here for real in-game testing to confirm portal-view
    // transformation still looks right frame-to-frame (see docs/migration-26.1-plan.md).
    
    @Override
    public void ip_setLightmapTextureManager(Lightmap manager) {
        lightmap = manager;
    }
    
    @Override
    public Lightmap ip_getLightmap() {
        return lightmap;
    }
    
    @Override
    public boolean ip_getDoRenderHand() {
        return renderHand;
    }
    
    @Override
    public void ip_setDoRenderHand(boolean doRenderHand) {
        renderHand = doRenderHand;
    }
    
    @Override
    public FogRenderer ip_getFogRenderer() {
        return fogRenderer;
    }
    
    @Override
    public void ip_setCamera(Camera camera_) {
        mainCamera = camera_;
    }
    
}
