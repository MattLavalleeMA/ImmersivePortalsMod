package qouteall.imm_ptl.core.render;

import net.minecraft.util.profiling.Profiler;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.mixin.client.render.IERenderSystem;
import qouteall.imm_ptl.core.mixin.client.render.IESectionRenderDispatcher;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Stack;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class MyGameRenderer {
    public static final Minecraft client = Minecraft.getInstance();
    
    private static final LimitedLogger limitedLogger = new LimitedLogger(10);
    
//    public static final int MAX_SECONDARY_BUFFER_NUM = 2;
    
    // portal rendering and outer world rendering uses different buffer builder storages
    private static Stack<RenderBuffers> secondaryRenderBuffers = new Stack<>();
    private static int usingRenderBuffersObjectNum = 0;
    
    // the vanilla visibility sections discovery code is multithreaded
    // when the player teleports through a portal, on the first frame it will not work normally
    // so use IP's non-multi-threaded algorithm at the first frame
    public static int vanillaTerrainSetupOverride = 0;
    
    public static boolean enablePortalCaveCulling = true;
    
    // TEMP DIAGNOSTIC (2026-07-12): tracing the Sodium "Global terrain uniforms have
    // not been updated" crash. Remove once root-caused/fixed.
    public static int portalRenderDepth = 0;
    
    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            secondaryRenderBuffers.clear();
        });
    }
    
    @Nullable
    private static RenderBuffers acquireRenderBuffersObject() {
//        if (usingRenderBuffersObjectNum >= MAX_SECONDARY_BUFFER_NUM) {
//            return null;
//        }
        usingRenderBuffersObjectNum++;
        
        if (secondaryRenderBuffers.isEmpty()) {
            return new RenderBuffers(0);
        }
        else {
            return secondaryRenderBuffers.pop();
        }
    }
    
    private static void returnRenderBuffersObject(RenderBuffers renderBuffers) {
        usingRenderBuffersObjectNum--;
        secondaryRenderBuffers.push(renderBuffers);
    }
    
    public static void renderWorldNew(
        WorldRenderInfo worldRenderInfo,
        Consumer<Runnable> invokeWrapper
    ) {
        WorldRenderInfo.pushRenderInfo(worldRenderInfo);
        
        switchAndRenderTheWorld(
            worldRenderInfo.world,
            worldRenderInfo.cameraPos,
            worldRenderInfo.cameraPos,
            invokeWrapper,
            worldRenderInfo.renderDistance,
            worldRenderInfo.doRenderHand
        );
        
        WorldRenderInfo.popRenderInfo();
    }
    
    private static void switchAndRenderTheWorld(
        ClientLevel newWorld,
        Vec3 thisTickCameraPos,
        Vec3 lastTickCameraPos,
        Consumer<Runnable> invokeWrapper,
        int renderDistance,
        boolean doRenderHand
    ) {
        if (!enablePortalCaveCulling) {
            client.smartCull = false;
        }
        
        if (!PortalRendering.shouldEnableSodiumCaveCulling()) {
            client.smartCull = false;
        }
        
        ResourceKey<Level> newDimension = newWorld.dimension();
        
        Helper.log("[SODIUM-DIAG] enter switchAndRenderTheWorld from=" +
            client.level.dimension().identifier() + " to=" + newDimension.identifier() +
            " depth=" + portalRenderDepth);
        portalRenderDepth++;
        
        LevelRenderer worldRenderer = ClientWorldLoader.getWorldRenderer(newDimension);
        
        CHelper.checkGlError();
        
        IEGameRenderer ieGameRenderer = (IEGameRenderer) client.gameRenderer;
        DimensionRenderHelper helper =
            ClientWorldLoader.getDimensionRenderHelper(newDimension);
        Camera newCamera = new Camera();
        
        // store old state
        ClientLevel oldWorld = client.level;
        LevelRenderer oldWorldRenderer = client.levelRenderer;
        Lightmap oldLightmap = ieGameRenderer.ip_getLightmap();
        boolean oldNoClip = client.player.noPhysics;
        boolean oldDoRenderHand = ieGameRenderer.ip_getDoRenderHand();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> oldChunkInfoList =
            ((IEWorldRenderer) oldWorldRenderer).portal_getChunkInfoList();
        HitResult oldCrosshairTarget = client.hitResult;
        Camera oldCamera = client.gameRenderer.getMainCamera();
        PostChain oldTransparencyShader = ((IEWorldRenderer) worldRenderer).portal_getTransparencyShader();
        RenderBuffers oldRenderBuffers = ((IEWorldRenderer) worldRenderer).ip_getRenderBuffers();
        RenderBuffers oldClientRenderBuffers = client.renderBuffers();
        SectionBufferBuilderPack oldSectionRenderDispatcherFixedBuffers =
            ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                .ip_getFixedBuffers();
        Frustum oldFrustum = ((IEWorldRenderer) worldRenderer).portal_getFrustum();
        
        // the projection matrix contains view bobbing.
        // the view bobbing is related with scale
        // TODO MC 26.1: RenderSystem.getProjectionMatrix()/GameRenderer.resetProjectionMatrix(Matrix4f)
        // were both removed -- the projection matrix is now GPU-buffer-backed
        // (RenderSystem.getProjectionMatrixBuffer()), not a plain CPU-side Matrix4f, so it
        // can no longer be captured/restored by value. RenderSystem itself now provides a
        // matching save/restore pair for exactly this purpose.
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack oldModelViewStack = IERenderSystem.ip_getModelViewStack();
        
        ObjectArrayList<SectionRenderDispatcher.RenderSection> newChunkInfoList =
            VisibleSectionDiscovery.takeList();
        ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(newChunkInfoList);
        
        Object irisPipeline = IrisInterface.invoker.getPipeline(worldRenderer);
        
        // switch (note: it will no longer switch the world that client player is in )
        ((IEMinecraftClient) client).ip_setWorldRenderer(worldRenderer);
        client.level = newWorld;
        ieGameRenderer.ip_setLightmapTextureManager(helper.lightmap);
        
        // TODO MC 26.1: BlockEntityRenderDispatcher.level field was removed entirely with
        // no replacement found (confirmed via javap) -- stubbed out, same as the identical
        // situation in ClientTeleportationManager.changePlayerDimension.
        client.player.noPhysics = true;
        ieGameRenderer.ip_setDoRenderHand(doRenderHand);
        
        // MC 26.1: FogRenderer no longer has static per-dimension color state to
        // shadow-swap (the old MixinFogRenderer/StaticFieldsSwappingManager
        // mechanism is gone -- see FogRendererContext). Instead, save the outer
        // world's CameraRenderState.fogType/fogData here and directly install a
        // freshly-computed replacement for the dimension about to be rendered
        // below, mirroring GameRenderer.extractCamera()'s own real logic (the
        // only place vanilla itself computes this). This is a source-confirmed
        // translation, but still needs real in-game testing to confirm portal
        // fog actually looks right end-to-end (see docs/migration-26.1-plan.md).
        //
        // MC 26.1 also split GameRenderer's per-frame work into separate update()/
        // extract()/render() phases (confirmed via decompiled source): the
        // gameRenderState.levelRenderState.cameraRenderState object (pos/rotation/
        // projection/view matrices/cull frustum - NOT just fog) is populated exactly
        // ONCE per real frame, for the OUTER camera only, by GameRenderer.extract()
        // (a private extractCamera() call this mod cannot hook without a Mixin
        // accessor). renderLevel() itself just reads that already-extracted
        // CameraRenderState directly (confirmed: no extractRenderState() call inside
        // renderLevel) - so without doing this ourselves, nested portal-content
        // rendering would silently reuse the OUTER world's camera matrices instead
        // of the destination dimension's. Since CameraRenderState/LevelRenderState
        // fields are all public and mutable, swap in a fresh CameraRenderState object
        // for the duration of this nested render (simpler/safer than deep-copying
        // every mutable field of the old one to restore later), and populate it
        // ourselves via Camera.extractRenderState() (public), which also runs
        // MixinCamera's existing getViewRotationMatrix() wrap that supplies the
        // correct portal rotation/mirror transform.
        LevelRenderState levelRenderState =
            client.gameRenderer.getGameRenderState().levelRenderState;
        CameraRenderState oldCameraRenderState = levelRenderState.cameraRenderState;
        CameraRenderState cameraRenderState = new CameraRenderState();
        levelRenderState.cameraRenderState = cameraRenderState;
        ((IEParticleManager) client.particleEngine).ip_setWorld(newWorld);
        if (BlockManipulationClient.remotePointedDim == newDimension) {
            client.hitResult = BlockManipulationClient.remoteHitResult;
        }
        if (!PortalRendering.shouldRenderHitResult()) {
            client.hitResult = null;
        }
        
        // set up the new camera: level/entity/fov-modifier must be set before
        // update() (which computes rotation/fov/perspective/cull-frustum from the
        // focused entity's real - wrong-dimension - position), then override the
        // position with the portal-transformed one update() got wrong.
        newCamera.setLevel(newWorld);
        ((IECamera) newCamera).portal_setFocusedEntity(client.getCameraEntity());
        ((IECamera) newCamera).ip_setFovModifier(1.0f);
        newCamera.update(client.getDeltaTracker());
        ((IECamera) newCamera).portal_setPos(thisTickCameraPos);
        
        // BUGFIX (2026-07-12): this Sodium context creation/switch used to happen
        // AFTER worldRenderer.update(newCamera) below (30+ lines later in the
        // original ordering). Since client.levelRenderer/client.level were already
        // switched to the destination dimension earlier in this function,
        // worldRenderer.update(newCamera) would run cullTerrain()/setupTerrain()
        // against whichever Sodium RenderSectionManager context was CURRENTLY active
        // at that moment (the previous context, e.g. stale data from this
        // dimension's last use) -- then, immediately afterwards, this call would
        // swap in a brand new, never-updated context (schedules an update but does
        // NOT synchronously run it) right before renderLevel() tries to actually
        // draw with it. That's exactly the "Global terrain uniforms have not been
        // updated" Sodium crash reported after repeatedly traversing portals -- the
        // freshly-swapped-in context's uniforms were never populated before being
        // drawn with. Moving this block to BEFORE worldRenderer.update(newCamera)
        // ensures update()'s cullTerrain()/setupTerrain() call runs against the
        // SAME context that is about to be rendered with, actually populating its
        // uniforms.
        Object newSodiumContext = SodiumInterface.invoker.createNewContext(renderDistance);
        SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
        
        // MC 26.1: GameRenderer.update() (a separate per-real-frame lifecycle phase
        // from render()/renderLevel()) is the ONLY place vanilla calls
        // `LevelRenderer.update(Camera)` -> `cullTerrain(...)` -> `compileSections(...)`
        // - and it only ever does so for the OUTER world's LevelRenderer (whatever
        // client.levelRenderer was at the start of the frame, before any nested portal
        // rendering swaps it). Sodium's own per-frame terrain-uniform setup
        // (SodiumWorldRenderer.setupTerrain(), confirmed via MixinSodiumWorldRenderer's
        // diagnostic logging) hooks into this same vanilla call chain, so without
        // calling it here too, the destination dimension's LevelRenderer/
        // SodiumWorldRenderer never gets its per-frame terrain visibility/uniform
        // buffer populated before renderLevel() tries to draw its chunks - causing
        // Sodium's "Global terrain uniforms have not been updated" crash the first
        // time a portal's content is actually rendered.
        worldRenderer.update(newCamera);
        
        ieGameRenderer.ip_setCamera(newCamera);
        
        float cameraEntityPartialTicks = newCamera.getCameraEntityPartialTicks(client.getDeltaTracker());
        newCamera.extractRenderState(cameraRenderState, cameraEntityPartialTicks);
        
        cameraRenderState.fogType = newCamera.getFluidInCamera();
        cameraRenderState.fogData = ieGameRenderer.ip_getFogRenderer().setupFog(
            newCamera,
            renderDistance,
            client.getDeltaTracker(),
            client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick()),
            newWorld
        );
        
        RenderBuffers newRenderBuffers = null;
        if (IPGlobal.useSecondaryEntityVertexConsumer) {
            newRenderBuffers = acquireRenderBuffersObject();
            if (newRenderBuffers != null) {
                ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(newRenderBuffers);
                ((IEMinecraftClient) client).ip_setRenderBuffers(newRenderBuffers);
                
                /*
                  the vanilla buffer pack may be used by {@link net.minecraft.client.renderer.MultiBufferSource.BufferSource}
                  The BufferSource does not always immediately finish building.
                  Reusing that may cause "Already Building" error in Buffer Builder when doing main-thread chunk rebuilding.
                  This does not occur in vanilla because vanilla does main-thread chunk rebuilding before entity rendering. With portal rendering it could do chunk rebuilding after some entity rendering.
                 */
                ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
                    .ip_setFixedBuffers(newRenderBuffers.fixedBufferPack());
            }
            else{
                // draw the content in the buffers,
                // to avoid messing with content in the portals
                // TODO it may draw with wrong stencil func here
                client.renderBuffers().bufferSource().endBatch();
            }
        }
        
        ((IEWorldRenderer) worldRenderer).portal_setTransparencyShader(null);
        
        IERenderSystem.ip_setModelViewStack(new Matrix4fStack(16));
        // TODO MC 26.1: RenderSystem.applyModelViewMatrix() was removed -- the model-view
        // matrix is read live from RenderSystem.getModelViewStack() at draw time now,
        // there's no separate "apply to shader state" step left to call anymore.
        
        IrisInterface.invoker.setPipeline(worldRenderer, null);
        
        //update lightmap
        if (!RenderStates.isDimensionRendered(newDimension)) {
            helper.forceUpdate();
        }
        
        //invoke rendering
        invokeWrapper.accept(() -> {
            Profiler.get().push("render_portal_content");
            client.gameRenderer.renderLevel(
                client.getDeltaTracker()
            );
            Profiler.get().pop();
        });
        
        SodiumInterface.invoker.switchContextWithCurrentWorldRenderer(newSodiumContext);
        
        //recover
        
        ((IEMinecraftClient) client).ip_setWorldRenderer(oldWorldRenderer);
        client.level = oldWorld;
        ieGameRenderer.ip_setLightmapTextureManager(oldLightmap);
        // TODO MC 26.1: see BlockEntityRenderDispatcher.level TODO above.
        client.player.noPhysics = oldNoClip;
        ieGameRenderer.ip_setDoRenderHand(oldDoRenderHand);
        
        ((IEParticleManager) client.particleEngine).ip_setWorld(oldWorld);
        client.hitResult = oldCrosshairTarget;
        ieGameRenderer.ip_setCamera(oldCamera);
        
        ((IEWorldRenderer) worldRenderer).portal_setTransparencyShader(oldTransparencyShader);
        
        levelRenderState.cameraRenderState = oldCameraRenderState;
        
        ((IEWorldRenderer) oldWorldRenderer).portal_setChunkInfoList(oldChunkInfoList);
        VisibleSectionDiscovery.returnList(newChunkInfoList);
        
        ((IEWorldRenderer) worldRenderer).ip_setRenderBuffers(oldRenderBuffers);
        ((IEMinecraftClient) client).ip_setRenderBuffers(oldClientRenderBuffers);
        ((IESectionRenderDispatcher) worldRenderer.getSectionRenderDispatcher())
            .ip_setFixedBuffers(oldSectionRenderDispatcherFixedBuffers);
        if (newRenderBuffers != null) {
            returnRenderBuffersObject(newRenderBuffers);
        }
        
        ((IEWorldRenderer) worldRenderer).portal_setFrustum(oldFrustum);
        
        RenderSystem.restoreProjectionMatrix();
        IERenderSystem.ip_setModelViewStack(oldModelViewStack);
        // TODO MC 26.1: see RenderSystem.applyModelViewMatrix() TODO above -- no longer needed.
        
        IrisInterface.invoker.setPipeline(worldRenderer, irisPipeline);
        
        client.getEntityRenderDispatcher()
            .prepare(
                oldCamera,
                client.crosshairPickEntity
            );
        
        CHelper.checkGlError();
        
        client.smartCull = true;
        
        portalRenderDepth--;
        Helper.log("[SODIUM-DIAG] exit switchAndRenderTheWorld dim=" +
            newDimension.identifier() + " backTo=" + oldWorld.dimension().identifier() +
            " depth=" + portalRenderDepth);
    }
    
    /**
     * {@link LevelRenderer#renderLevel}
     */
    @IPVanillaCopy
    public static void resetFogState() {
        Camera camera = client.gameRenderer.getMainCamera();
        float darkenWorldAmount = client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick());
        
        // TODO MC 26.1: DimensionSpecialEffects (and its isFoggyAt()/the old boolean
        // "isFoggy" param) was removed entirely -- FogRenderer.setupFog() now computes
        // fogginess internally from the camera's current fluid/block context instead of
        // taking it as an external input (confirmed via decompiled source), so there's
        // nothing left for us to compute here at all.
        FogRenderer fogRenderer = ((IEGameRenderer) client.gameRenderer).ip_getFogRenderer();
        FogData fogData = fogRenderer.setupFog(
            camera, client.options.getEffectiveRenderDistance(), client.getDeltaTracker(),
            darkenWorldAmount, client.level
        );
        fogRenderer.updateBuffer(fogData);
    }
    
    public static void updateFogColor() {
        FogRenderer fogRenderer = ((IEGameRenderer) client.gameRenderer).ip_getFogRenderer();
        FogData fogData = fogRenderer.setupFog(
            client.gameRenderer.getMainCamera(),
            client.options.getEffectiveRenderDistance(),
            client.getDeltaTracker(),
            client.gameRenderer.getBossOverlayWorldDarkening(RenderStates.getPartialTick()),
            client.level
        );
        fogRenderer.updateBuffer(fogData);
    }
    
    /**
     * {@link LevelRenderer#renderLevel}
     */
    @IPVanillaCopy
    public static void resetDiffuseLighting() {
        ClientLevel world = client.level;
        assert world != null;
        // TODO MC 26.1: DimensionSpecialEffects.constantAmbientLight() was removed --
        // DimensionType now declares its cardinal lighting mode directly
        // (DimensionType.cardinalLightType(), a CardinalLighting.Type enum: DEFAULT/NETHER),
        // and Lighting itself became an instance (via GameRenderer.getLighting()) with a
        // single updateLevel(CardinalLighting.Type) method instead of separate static
        // setupLevel()/setupNetherLevel() methods.
        client.gameRenderer.getLighting().updateLevel(world.dimensionType().cardinalLightType());
    }
    
    
}
