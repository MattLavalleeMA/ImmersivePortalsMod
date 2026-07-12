package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IEEntityRenderState;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

// MC 26.1: LevelRenderer.renderLevel was completely restructured around a
// FrameGraphBuilder (declarative named FramePasses executed later via lambdas, e.g. the
// bulk of solid/translucent/entity rendering happens inside a synthetic
// lambda$addMainPass$0 method, weather inside lambda$addWeatherPass$0, not sequentially
// in renderLevel's own body like before). Re-anchored below against those real synthetic
// methods -- cross-confirmed real and weave-stable on our exact MC version (26.1.2) via
// MeteorDevelopment/meteor-client's own LevelRendererMixin.java and
// Vivecraft/VivecraftMod's Multiloader-26.1 branch, both of which independently target
// the same lambda$addMainPass$0(*) method for the same kind of "inject custom drawing
// into the main render pass" problem (see docs/migration-26.1-plan.md's
// MixinDebugRenderer.java entry for the full research). One piece is NOT re-anchored
// here: the old onSetupTerrainBegin/onSetupTerrainEnd terrain-visibility override (via
// VisibleSectionDiscovery.discoverVisibleSections) targeted the now-fully-removed
// setupRender method; its replacement, cullTerrain(Camera,Frustum,boolean), is built
// around a fundamentally different SectionOcclusionGraph-based algorithm (persistent
// per-frame traversal state, not a one-shot linear setup) that this mod's own override
// can't be safely ported onto without real design work and launch testing -- left
// unimplemented rather than guessed at; see "Remaining items" in docs/migration-26.1-plan.md.
@SuppressWarnings("JavadocReference")
@Mixin(value = LevelRenderer.class)
public abstract class MixinLevelRenderer implements IEWorldRenderer {
    
    @Shadow
    private ClientLevel level;
    
    @Shadow
    @Final
    private EntityRenderDispatcher entityRenderDispatcher;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    @Shadow
    private ViewArea viewArea;
    
    // NOTE: LevelRenderer.renderEntity(Entity,double,double,double,float,PoseStack,
    // MultiBufferSource) no longer exists (confirmed absent from decompiled MC 26.2
    // source, and no equivalent in MeteorDevelopment/meteor-client's, CaffeineMC/sodium's,
    // or Vivecraft/VivecraftMod's own LevelRenderer mixins -- all of them wrap
    // submitEntities/EntityRenderDispatcher.submit(...) instead now). The old @Shadow for
    // it and the @Redirect targeting it (both real weave-time-crash risks, since @Shadow/
    // @Redirect targets aren't checked by compileJava) were removed; see
    // redirectSubmitEntity below for the replacement.
    
    @Shadow
    private PostChain transparencyChain;
    
    @Mutable
    @Shadow
    @Final
    private RenderBuffers renderBuffers;
    
    @Shadow
    private int lastViewDistance;
    
    @Shadow
    private Frustum cullingFrustum;
    
    @Shadow
    protected abstract void deinitTransparency();
    
    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
    
    @Shadow
    @Final
    @Mutable
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    
    // sometimes we change renderDistance but we don't want to reload it
    @Inject(method = "allChanged", at = @At("HEAD"), cancellable = true)
    private void onReloadStarted(CallbackInfo ci) {
        if (WorldRenderInfo.isRendering()) {
            Helper.log("world renderer reloading cancelled during portal rendering");
            ci.cancel();
        }
    }
    
    @Redirect(
        method = "allChanged",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/world/level/Level;ILnet/minecraft/client/renderer/LevelRenderer;)Lnet/minecraft/client/renderer/ViewArea;"
        )
    )
    private ViewArea redirectConstructingBuildChunkStorage(
        SectionRenderDispatcher chunkBuilder_1,
        Level world_1,
        int int_1,
        LevelRenderer worldRenderer_1
    ) {
        if (IPCGlobal.useHackedChunkRenderDispatcher) {
            return new ImmPtlViewArea(
                chunkBuilder_1, world_1, int_1, worldRenderer_1
            );
        }
        else {
            return new ViewArea(
                chunkBuilder_1, world_1, int_1, worldRenderer_1
            );
        }
    }
    
    // Stashes the source Entity onto the EntityRenderState LevelRenderer.extractEntity(...)
    // produces, via the IEEntityRenderState duck (see MixinEntityRenderState.java) --
    // vanilla's EntityRenderState has no back-reference to the Entity it came from, but
    // redirectSubmitEntity below needs the real Entity. Same technique
    // MeteorDevelopment/meteor-client uses for the identical problem (its own
    // IEntityRenderState.meteor$getEntity() duck).
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void onExtractEntity(
        Entity entity, float partialTickTime, CallbackInfoReturnable<EntityRenderState> cir
    ) {
        EntityRenderState state = cir.getReturnValue();
        if (state != null) {
            ((IEEntityRenderState) state).ip_setEntity(entity);
        }
    }
    
    // Replaces the old renderEntity-targeting redirect (removed, see the NOTE above) --
    // submitEntities is a real, stably-named, non-synthetic private method (confirmed via
    // decompiled source), so this @Redirect doesn't need the lambda$addMainPass$0-style
    // re-anchoring the other disabled hooks in this file do.
    @Redirect(
        method = "submitEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
        )
    )
    private void redirectSubmitEntity(
        EntityRenderDispatcher dispatcher,
        EntityRenderState state,
        CameraRenderState cameraRenderState,
        double x,
        double y,
        double z,
        PoseStack matrixStack,
        SubmitNodeCollector output
    ) {
        Entity entity = ((IEEntityRenderState) state).ip_getEntity();
        
        if (entity != null) {
            CrossPortalEntityRenderer.beforeRenderingEntity(entity, matrixStack);
        }
        
        dispatcher.submit(state, cameraRenderState, x, y, z, matrixStack, output);
        
        if (entity != null) {
            CrossPortalEntityRenderer.afterRenderingEntity(entity);
        }
    }
    
    // Re-anchored from the old onAfterCutoutRendering (targeted the now-fully-removed
    // DimensionSpecialEffects.constantAmbientLight() call, confirmed absent anywhere in
    // decompiled MC 26.2 source). submitEntities is the real, stable, non-synthetic method
    // where the bulk entity-rendering pass begins (called once per frame, right before
    // entities/block entities are submitted) -- a cleaner, more stable anchor than trying
    // to find a new call-site landmark inside the synthetic lambda. RenderSystem
    // .getModelViewMatrix() -- confirmed still real and unrenamed on our exact MC version
    // (26.1.x) via a decompiled 26.1.1 source cross-check; a 26.2-only
    // getModelViewMatrixCopy() rename exists in *later* versions but doesn't apply here.
    @Inject(method = "submitEntities", at = @At("HEAD"))
    private void onBeginRenderingEntitiesAndBlockEntities(CallbackInfo ci) {
        CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(RenderSystem.getModelViewMatrix());
    }
    
    // Re-anchored from the old onEndRenderingEntities (targeted an
    // endLastBatch()-ordinal-1 landmark inside the old lambda). Entities/block entities
    // are only *submitted* (queued) by submitEntities/submitBlockEntities now -- the
    // actual GPU draw happens later in the same lambda$addMainPass$0 frame pass, via
    // featureRenderDispatcher.renderSolidFeatures() (confirmed via decompiled source).
    // Anchored right after that call finishes instead, using the lambda's own captured
    // PoseStack local (confirmed to exist there: `PoseStack poseStack = new PoseStack();`).
    @Inject(
        method = "lambda$addMainPass$0*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderSolidFeatures()V",
            shift = At.Shift.AFTER
        )
    )
    private void onEndRenderingEntities(CallbackInfo ci, @Local PoseStack poseStack) {
        CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities(poseStack);
    }
    
    // Re-anchored from the old onMyBeforeTranslucentRendering (targeted
    // Sheets.translucentItemSheet(), no longer called from anywhere near this code path).
    // ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler) is called
    // exactly twice per frame in lambda$addMainPass$0 -- once for OPAQUE, once for
    // TRANSLUCENT (confirmed via decompiled source) -- ordinal 1 is the translucent one.
    @Inject(
        method = "lambda$addMainPass$0*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            ordinal = 1
        )
    )
    private void onMyBeforeTranslucentRendering(CallbackInfo ci) {
        IPCGlobal.renderer.onBeforeTranslucentRendering(RenderSystem.getModelViewMatrix());
        
        MyGameRenderer.updateFogColor();
        MyGameRenderer.resetFogState();
        
        MyGameRenderer.resetDiffuseLighting();
        
        FrontClipping.disableClipping();
    }
    
    // Re-anchored from the old onBeforeRenderingLayer/onAfterRenderingLayer (targeted the
    // now-fully-removed per-layer LevelRenderer.renderSectionLayer(RenderType,...) call,
    // which used to fire once per render layer in a loop). The new
    // ChunkSectionsToRender.renderGroup(...) call (see above) is the direct replacement,
    // now called exactly twice (opaque, translucent) rather than once per fine-grained
    // layer -- omitting `ordinal` here (unlike the translucent-specific hook above)
    // deliberately matches both occurrences, preserving the original "before/after any
    // render layer" semantics.
    @Inject(
        method = "lambda$addMainPass$0*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"
        )
    )
    private void onBeforeRenderingLayer(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                RenderSystem.getModelViewMatrix(),
                -FrontClipping.ADJUSTMENT
                // move the clipping plane a little back, to make world wrapping portal not z-fight
            );
            
            if (PortalRendering.isRenderingOddNumberOfMirrors()) {
                MyRenderHelper.applyMirrorFaceCulling();
            }
            
            if (IPGlobal.enableDepthClampForPortalRendering) {
                CHelper.enableDepthClamp();
            }
        }
    }
    
    @Inject(
        method = "lambda$addMainPass$0*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterRenderingLayer(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            FrontClipping.disableClipping();
            MyRenderHelper.recoverFaceCulling();
            
            if (IPGlobal.enableDepthClampForPortalRendering) {
                CHelper.disableDepthClamp();
            }
        }
    }
    
    // Re-anchored from the old redirectClearing (targeted RenderSystem.clear(int), which
    // this specific "clear" FramePass no longer calls at all -- confirmed via decompiled
    // source, it now clears via CommandEncoder.clearColorAndDepthTextures(...) directly).
    // Lower confidence than the hooks above: this "clear" pass's executes(...) lambda is
    // written directly inline in renderLevel's own body (not inside a separate named
    // helper method like addMainPass/addWeatherPass are), and appears to be the only
    // lambda literal there -- lambda$renderLevel$0 is a reasoned guess (first and only
    // lambda in that method's own source), not cross-confirmed via another mod's mixin
    // the way the others above are. If this fails to weave, Mixin's own error output will
    // list the real available lambda methods on LevelRenderer, which should make
    // correcting the index/name fast.
    @Redirect(
        method = "lambda$renderLevel$0*",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V"
        )
    )
    private void redirectClearing(
        CommandEncoder commandEncoder, GpuTexture colorTexture, int color, GpuTexture depthTexture, double depth
    ) {
        if (!IPCGlobal.renderer.replaceFrameBufferClearing()) {
            commandEncoder.clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
        }
    }
    
    // Re-anchored from the old beforeRenderingWeather/afterRenderingWeather (targeted the
    // lambda in addWeatherPass, same idea, just a fresh synthetic name in this MC version).
    // addWeatherPass is its own dedicated private method (confirmed via decompiled source),
    // so its lambda is unambiguously lambda$addWeatherPass$0 -- same confidence level as the
    // lambda$addMainPass$0 hooks above.
    @Inject(method = "lambda$addWeatherPass$0*", at = @At("HEAD"))
    private void beforeRenderingWeather(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            FrontClipping.setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                RenderSystem.getModelViewMatrix(), 0
            );
            RenderStates.isRenderingPortalWeather = true;
        }
    }
    
    @Inject(method = "lambda$addWeatherPass$0*", at = @At("RETURN"))
    private void afterRenderingWeather(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            FrontClipping.disableClipping();
            RenderStates.isRenderingPortalWeather = false;
        }
    }
    
    // Re-anchored from the old onFinishRenderLevel -- renderLevel is still a real, stable
    // method (just a different parameter list now), and this hook never read any of its
    // args, so no re-mapping was needed beyond the target method name itself.
    // Lighting.setupLevel() (the old static call) no longer exists -- Lighting became an
    // instance (via GameRenderer.getLighting()) with a single
    // updateLevel(CardinalLighting.Type) method instead of separate static
    // setupLevel()/setupNetherLevel() methods (already established and used elsewhere in
    // this mod, see MyGameRenderer.resetDiffuseLighting()). The old unconditional
    // Lighting.setupLevel() call corresponds to CardinalLighting.Type.DEFAULT specifically
    // (not whatever the current dimension happens to be), matching its "make hand
    // rendering normal again" intent after finishing a (possibly nested-portal) render.
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onFinishRenderLevel(CallbackInfo ci) {
        // make hand rendering normal
        minecraft.gameRenderer.getLighting().updateLevel(CardinalLighting.Type.DEFAULT);
    }
    @Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;shouldEntityAppearGlowing(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    private boolean redirectGlowing(Minecraft client, Entity entity) {
        if (WorldRenderInfo.isRendering()) {
            return false;
        }
        return client.shouldEntityAppearGlowing(entity);
    }
    
    //reload other world renderers when the main world renderer is reloaded
    @Inject(method = "allChanged", at = @At("TAIL"))
    private void onReloadFinished(CallbackInfo ci) {
        LevelRenderer this_ = (LevelRenderer) (Object) this;
        
        if (ClientWorldLoader.getIsCreatingClientWorld()) {
            return;
        }
        
        Validate.isTrue(Minecraft.getInstance().levelRenderer == this_);
        
        ClientWorldLoader._onWorldRendererReloaded();
    }
    
    @Inject(
        method = "renderSky", at = @At("HEAD"), cancellable = true
    )
    private void onRenderSkyBegin(
        Matrix4f modelView, Matrix4f matrix4f, float partialTick, Camera camera,
        boolean isFoggy, Runnable runnable, CallbackInfo ci
    ) {
        if (WorldRenderInfo.isRendering()) {
            if (!WorldRenderInfo.getTopRenderInfo().doRenderSky) {
                if (!IrisInterface.invoker.isShaders()) {
                    ci.cancel();
                }
            }
        }
        
        if (PortalRendering.isRenderingOddNumberOfMirrors()) {
            MyRenderHelper.applyMirrorFaceCulling();
        }
    }
    
    @Inject(
        method = "renderSky",
        at = @At("RETURN")
    )
    private void onRenderSkyEnd(
        Matrix4f modelView, Matrix4f matrix4f, float f, Camera camera,
        boolean bl, Runnable runnable, CallbackInfo ci
    ) {
        MyRenderHelper.recoverFaceCulling();
    }
    
    // correct the eye position for sky rendering
    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectGetEyePositionInSkyRendering(LocalPlayer player, float partialTicks) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos();
        }
        return player.getEyePosition(partialTicks);
    }
    
    // vanilla clears translucentFramebuffer even when transparencyShader is null
    // it makes the framebuffer to be wrongly bound in fabulous mode
    // MC 26.1: the translucentTarget field is fully removed -- translucent rendering now
    // goes through a FrameGraphBuilder-managed LevelTargetBundle (this.targets.translucent),
    // with a real public getTranslucentTarget() accessor (confirmed via decompiled source:
    // `public RenderTarget getTranslucentTarget() { return this.targets.translucent != null
    // ? this.targets.translucent.get() : null; }`, also used by vanilla's own
    // ChunkSectionLayerGroup). Re-anchored from a @Redirect on the removed field to an
    // @Inject at the head of that getter instead -- more precise than the old field redirect
    // since it intercepts every caller, not just one read site inside renderLevel's own body.
    @Inject(method = "getTranslucentTarget", at = @At("HEAD"), cancellable = true)
    private void onGetTranslucentTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(null);
        }
    }
    
    // if not in spectator mode, when the camera is in block chunk culling will cull chunks wrongly
    // MC 26.1: LevelRenderer.setupRender(Camera,Frustum,boolean,boolean) is fully removed
    // (confirmed absent anywhere in decompiled MC 26.2 source). Its spectator-based
    // smart-cull-disabling logic now lives in the private
    // cullTerrain(Camera,Frustum,boolean spectator) method instead (confirmed via decompiled
    // source: `if (spectator && ...isSolidRender()) smartCull = false`), called from
    // update(Camera) as `cullTerrain(camera, camera.getCullFrustum(),
    // minecraft.player.isSpectator())`. Re-anchored to cullTerrain's own (only) boolean
    // parameter.
    @ModifyVariable(
        method = "cullTerrain",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private boolean modifyIsSpectator(boolean value) {
        if (WorldRenderInfo.isRendering()) {
            return true;
        }
        return value;
    }
    
    // MC 26.1: ClientLevel.pollLightUpdates() is no longer called from
    // LevelRenderer.renderLevel(...) at all -- confirmed via decompiled source, it moved
    // into ClientLevel's own per-tick ClientLevel.update() (`populateLightUpdates` profiler
    // section, alongside `runLightUpdates`), decoupled entirely from this mod's
    // per-dimension renderLevel(...) calls. The world-switching wrapper this hook existed for
    // ("the captured lambda uses the net handler's world field") was specifically about
    // renderLevel being called multiple times per frame for different dimensions -- since
    // pollLightUpdates() isn't reached from that path anymore, the original problem this
    // redirect solved likely no longer exists. Removed rather than guessed at; flagged for
    // real-launch verification in case light updates in non-primary rendered dimensions
    // still need special handling some other way.
    
    /**
     * when rendering portal, it won't call {@link ViewArea#repositionCamera(double, double)}
     * So {@link ViewArea#getRenderSectionAt} will return incorrect result
     */
    // MC 26.1: LevelRenderer.isSectionCompiled(BlockPos) was renamed to
    // isSectionCompiledAndVisible(BlockPos) (confirmed via decompiled source and its call
    // site in extractVisibleEntities) -- same method body/semantics, just a rename.
    @Inject(
        method = "isSectionCompiledAndVisible",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsChunkCompiled(BlockPos blockPos, CallbackInfoReturnable<Boolean> cir) {
        if (PortalRendering.isRendering()) {
            if (!SodiumInterface.invoker.isSodiumPresent()) {
                if (viewArea instanceof ImmPtlViewArea immPtlViewArea) {
                    cir.setReturnValue(ip_isChunkCompiled(immPtlViewArea, blockPos));
                }
            }
        }
    }
    
    private boolean ip_isChunkCompiled(ImmPtlViewArea immPtlViewArea, BlockPos blockPos) {
        SectionPos sectionPos = SectionPos.of(blockPos);
        var renderChunk = immPtlViewArea.rawGet(
            sectionPos.x(), sectionPos.y(), sectionPos.z()
        );
        
        return renderChunk != null
            && renderChunk.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED;
    }
    
    @Override
    public EntityRenderDispatcher ip_getEntityRenderDispatcher() {
        return entityRenderDispatcher;
    }
    
    @Override
    public ViewArea ip_getBuiltChunkStorage() {
        return viewArea;
    }
    
    // TODO MC 26.1: LevelRenderer.renderEntity(...) (the old synchronous single-entity
    // immediate-draw method this called) no longer exists -- entity rendering is now
    // always extract(EntityRenderDispatcher.extractEntity)-then-submit
    // (EntityRenderDispatcher.submit(...), which only queues into a SubmitNodeCollector;
    // actually executing a submission requires the shared FeatureRenderDispatcher/
    // SubmitNodeStorage, e.g. FeatureRenderDispatcher.prepareFrame(...)
    // .executeSolid()/.executeTranslucent(), per Vivecraft/VivecraftMod's own
    // vivecraft$renderGizmos()). Reusing the main frame's shared dispatcher/storage for
    // this one-off nested "render a single entity's cross-portal projection right now"
    // call risks colliding with whatever submission the main frame already has in
    // flight (double-submission, premature clearing, etc.) -- a real redesign, not a
    // rename, and one that needs an actual game launch to verify rather than guessing
    // blind. Stubbed to a no-op for now (entity projections through portals just won't
    // render, a visual regression only -- same precedent as the other render-pipeline
    // items stubbed pending real launch testing).
    @Override
    public void ip_myRenderEntity(
        Entity entity,
        double cameraX,
        double cameraY,
        double cameraZ,
        float partialTick,
        PoseStack matrixStack,
        MultiBufferSource vertexConsumerProvider
    ) {
    }
    
    @Override
    public PostChain portal_getTransparencyShader() {
        return transparencyChain;
    }
    
    @Override
    public void portal_setTransparencyShader(PostChain arg) {
        transparencyChain = arg;
    }
    
    @Override
    public RenderBuffers ip_getRenderBuffers() {
        return renderBuffers;
    }
    
    @Override
    public void ip_setRenderBuffers(RenderBuffers arg) {
        renderBuffers = arg;
    }
    
    @Override
    public Frustum portal_getFrustum() {
        return cullingFrustum;
    }
    
    @Override
    public void portal_setFrustum(Frustum arg) {
        cullingFrustum = arg;
    }
    
    @Override
    public void portal_fullyDispose() {
        deinitTransparency();
        
        // TODO MC 26.1: starBuffer/skyBuffer/darkBuffer/cloudBuffer no longer exist on
        // LevelRenderer (sky/cloud rendering moved to dedicated SkyRenderer/CloudRenderer
        // classes) - nothing to dispose here anymore for those.
        
        level = null;
    }
    
    @Override
    public void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg) {
        visibleSections = arg;
    }
    
    @Override
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList() {
        return visibleSections;
    }
}
