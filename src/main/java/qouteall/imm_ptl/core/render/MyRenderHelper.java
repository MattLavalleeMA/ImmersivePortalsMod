package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.my_util.SignalBiArged;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glReadPixels;

// TODO MC 26.1: This class's custom immediate-mode draw helpers (portal area shader,
// screen triangle, framebuffer-to-framebuffer blit) used ShaderInstance/Uniform/
// BufferBuilder/BufferUploader/Tesselator, which no longer exist - shaders are now
// compiled centrally by ShaderManager into RenderPipeline objects drawn via RenderPass/
// GpuBuffer (see com.mojang.blaze3d.systems.RenderPass, com.mojang.blaze3d.pipeline.
// RenderPipeline). Re-implementing these draws needs a small custom RenderPipeline +
// GpuBuffer wrapper layer (see Distant Horizons' "Blaze" wrapper package for reference)
// plus in-game testing - out of scope for a static-analysis-only pass. Stubbed as no-ops
// below so the rest of the mod (and the stencil-based portal renderer scaffolding) still
// compiles; portal content will not actually render until this is redone.
public class MyRenderHelper {
    
    public static final Minecraft client = Minecraft.getInstance();
    
    public static final SignalBiArged<ResourceProvider, Consumer<Object>> loadShaderSignal =
        new SignalBiArged<>();
    
    public static void init() {
        // TODO MC 26.1: used to load custom ShaderInstances (portal_area, blit_screen_noblend,
        // portal_draw_fb_in_area) - stubbed, see class-level TODO.
    }
    
    // vanilla hardcodes the shader namespace to be "minecraft"
    private static ResourceProvider getResourceFactory(ResourceProvider resourceManager) {
        ResourceProvider resourceFactory = new ResourceProvider() {
            @Override
            public Optional<Resource> getResource(Identifier resourceLocation) {
                Identifier corrected = McHelper.newResourceLocation(
                    "immersive_portals", resourceLocation.getPath());
                return resourceManager.getResource(corrected);
            }
        };
        return resourceFactory;
    }
    
    // Raw GL id of a persistent, depth-attachment-less framebuffer object used only
    // to temporarily house the main render target's color texture as
    // GL_COLOR_ATTACHMENT0 while compositing a portal (see drawPortalAreaWithFramebuffer).
    // Lazily created; never resized/recreated since attaching a differently-sized
    // texture to an existing FBO's color attachment is valid GL. Mirrors Distant
    // Horizons' GlDhFramebuffer/GlDhApplyShader.renderToMcTexture() pattern (see
    // docs/portal-rendering-pipeline-and-invisible-content-bug.md section 2.13) --
    // deliberately NOT reusing the real main-target FBO for this draw, since that one
    // still has the real depth texture attached and simultaneously sampling +
    // targeting the same depth texture in one draw is a feedback hazard.
    private static int compositeFboId = -1;
    
    /**
     * Composites a portal's separately-rendered nested-world content (already drawn
     * into {@code textureProvider}, an offscreen buffer the same size as the main
     * window) onto the main render target's color texture, by drawing the portal's
     * own screen-facing quad geometry via {@link PositionTexturedGlProgram} and
     * sampling {@code textureProvider}'s color texture per-fragment at the same
     * screen pixel. The quad's own rasterized shape is the mask -- no stencil test
     * needed.
     * <p>
     * Occlusion against real world geometry already in front of the portal is
     * decided manually in {@link PositionTexturedGlProgram}'s fragment shader by
     * comparing this quad's own rasterized depth against the main render target's
     * real depth texture (sampled here and passed in) -- see that class's javadoc
     * for why this replaced both hardware occlusion queries (unreliable, section
     * 2.12) and a depth-priming approach tried afterward (also unreliable --
     * clobbered by vanilla's own sky pass, section 2.16+/session notes).
     * <p>
     * This draw targets {@link #compositeFboId} (the main color texture reattached
     * to a depth-attachment-less FBO each call, so this draw can safely run with
     * hardware depth test disabled, and sampling the main target's real depth
     * texture here is never a read/write feedback hazard since it's never attached
     * to this FBO) and then restores the GL framebuffer binding to {@code
     * restoreToFbo} (the raw FBO id that was actually bound for the main render
     * target before this portal started rendering, captured by the caller --
     * necessary because swapping {@code Minecraft.mainRenderTarget} via {@code
     * ip_setFrameBuffer} only changes which target *future* high-level draws are
     * issued against, it does not itself rebind anything at the raw GL level).
     */
    public static void drawPortalAreaWithFramebuffer(
        Portal portal,
        RenderTarget textureProvider,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix,
        int restoreToFbo
    ) {
        com.mojang.blaze3d.textures.GpuTexture colorTexture = textureProvider.getColorTexture();
        if (colorTexture == null) {
            return;
        }
        int rawGlColorTextureId = ((com.mojang.blaze3d.opengl.GlTexture) colorTexture).glId();
        
        RenderTarget mainTarget = client.getMainRenderTarget();
        com.mojang.blaze3d.textures.GpuTexture mainColorTexture = mainTarget.getColorTexture();
        com.mojang.blaze3d.textures.GpuTexture mainDepthTexture = mainTarget.getDepthTexture();
        if (mainColorTexture == null || mainDepthTexture == null) {
            return;
        }
        int rawGlMainColorTextureId = ((com.mojang.blaze3d.opengl.GlTexture) mainColorTexture).glId();
        int rawGlMainDepthTextureId = ((com.mojang.blaze3d.opengl.GlTexture) mainDepthTexture).glId();
        
        if (compositeFboId == -1) {
            compositeFboId = GlStateManager.glGenFramebuffers();
        }
        
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, compositeFboId);
        GlStateManager._glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, rawGlMainColorTextureId, 0
        );
        
        GlStateManager._disableDepthTest();
        GlStateManager._enableCull();
        
        PositionTexturedGlProgram.begin(
            modelViewMatrix, projectionMatrix,
            rawGlColorTextureId, rawGlMainDepthTextureId,
            textureProvider.width, textureProvider.height
        );
        
        Vec3 originRelativeToCamera = portal.getOriginPos().subtract(CHelper.getCurrentCameraPos());
        portal.renderViewAreaMesh(originRelativeToCamera, PositionTexturedGlProgram.VERTEX_OUTPUT);
        
        PositionTexturedGlProgram.end();
        
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, restoreToFbo);
        GlStateManager._enableDepthTest();
        
        CHelper.checkGlError();
    }
    
    // Raw GL id of a persistent, single-purpose scratch FBO used only by the
    // debugRead*Pixel diagnostics below -- deliberately separate from compositeFboId
    // so a diagnostic read can never clobber the attachment compositing itself
    // depends on. Lazily created.
    private static int debugScratchFboId = -1;
    
    private static int ensureDebugScratchFbo() {
        if (debugScratchFboId == -1) {
            debugScratchFboId = GlStateManager.glGenFramebuffers();
        }
        return debugScratchFboId;
    }
    
    // TEMP DIAGNOSTIC (2026-07-12): pixel-level readback helpers added to pin down
    // the "portal content sometimes shows real-world background instead of nested
    // content" bug (docs/portal-rendering-pipeline-and-invisible-content-bug.md
    // section 2.16+) with real numbers instead of screenshot-guessing -- reads a
    // single texel from an arbitrary raw GL texture (depth or color) by temporarily
    // attaching it to a dedicated scratch FBO, without disturbing whatever FBO is
    // currently bound for real rendering. Remove once root-caused/fixed.
    public static float debugReadDepthPixel(int rawGlDepthTextureId, int x, int y) {
        int prevFbo = GlStateManager.getFrameBuffer(GL30.GL_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, ensureDebugScratchFbo());
        GlStateManager._glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, rawGlDepthTextureId, 0
        );
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();
        glReadPixels(x, y, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, floatBuffer);
        float value = floatBuffer.get(0);
        
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        return value;
    }
    
    public static float[] debugReadColorPixel(int rawGlColorTextureId, int x, int y) {
        int prevFbo = GlStateManager.getFrameBuffer(GL30.GL_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, ensureDebugScratchFbo());
        GlStateManager._glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, rawGlColorTextureId, 0
        );
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();
        glReadPixels(x, y, 1, 1, org.lwjgl.opengl.GL11.GL_RGBA, GL_FLOAT, floatBuffer);
        float[] result = new float[4];
        floatBuffer.get(result);
        
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        return result;
    }
    
    public static void renderScreenTriangle() {
        renderScreenTriangle(255, 255, 255, 255);
    }
    
    public static void renderScreenTriangle(Vec3 color) {
        renderScreenTriangle(
            (int) (color.x * 255),
            (int) (color.y * 255),
            (int) (color.z * 255),
            255
        );
    }
    
    public static void testOneTriangle(int r, int g, int b, int a) {
        // TODO MC 26.1: see class-level TODO - stubbed no-op.
    }
    
    /**
     * {@link RenderTarget#blitToScreen()}
     * <p>
     * Draws a screen-covering quad (two triangles, NDC coords, identity model/view/
     * projection matrices) via {@link PositionColorGlProgram}, a small raw-GL program
     * that bypasses Blaze3D's RenderPipeline - see that class's javadoc for why.
     */
    @IPVanillaCopy
    public static void renderScreenTriangle(int r, int g, int b, int a) {
        Matrix4f identity = new Matrix4f();
        
        PositionColorGlProgram.begin(
            identity, identity,
            new Vec3(r / 255.0, g / 255.0, b / 255.0), a / 255.0f
        );
        
        PositionColorGlProgram.addTriangle(
            1, -1, 0,
            1, 1, 0,
            -1, 1, 0
        );
        PositionColorGlProgram.addTriangle(
            -1, 1, 0,
            -1, -1, 0,
            1, -1, 0
        );
        
        PositionColorGlProgram.end();
    }
    
    /**
     * {@link RenderTarget#blitToScreen()}
     */
    public static void drawScreenFrameBuffer(
        RenderTarget textureProvider,
        boolean doUseAlphaBlend,
        boolean doEnableModifyAlpha
    ) {
        int x = 0;
        int y = 0;
        
        int viewportWidth = textureProvider.width;
        int viewportHeight = textureProvider.height;
        
        drawFramebufferWithCoordinatesAndDimensions(
            textureProvider, doUseAlphaBlend, doEnableModifyAlpha,
            x, y, viewportWidth, viewportHeight
        );
    }

    public static void drawFramebuffer(
            RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
            float xMin, float xMax, float yMin, float yMax
    ) {
        drawFramebufferWithCoordinatesAndDimensions(
                textureProvider,
                doUseAlphaBlend, doEnableModifyAlpha,
                0, 0,
                client.getWindow().getWidth(),
                client.getWindow().getHeight()
        );
    }

    public static void drawFramebufferWithViewport(
            RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
            float left, float right, float bottom, float up,
            int viewportWidth, int viewportHeight
    ) {
        drawFramebufferWithCoordinatesAndDimensions(
                textureProvider,
                doUseAlphaBlend, doEnableModifyAlpha,
                0, 0,
                viewportWidth, viewportHeight
        );
    }
    
    public static void drawFramebufferWithBounds(
        RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
        int xMin, int xMax, int yMin, int yMax
    ) {

        drawFramebufferWithCoordinatesAndDimensions(
            textureProvider,
            doUseAlphaBlend, doEnableModifyAlpha,
            xMin, yMin,
            Mth.abs(xMax - xMin),
            Mth.abs(yMax - yMin)
        );
    }
    
    /**
     * {@link RenderTarget#blitToScreen()}
     */
    @IPVanillaCopy
    public static void drawFramebufferWithCoordinatesAndDimensions(
        RenderTarget textureProvider, boolean doUseAlphaBlend, boolean doEnableModifyAlpha,
        int x, int y, int viewportWidth, int viewportHeight
    ) {
        // TODO MC 26.1: see class-level TODO - stubbed no-op (used ShaderInstance-based blit).
    }
    
    // it will remove the light sections that are marked to be removed
    // if not, light data will cause minor memory leak
    // and wrongly remove the light data when the chunks get reloaded to client
    // this should not run before world rendering or the smooth lighting may become abnormal in section edge
    public static void lateUpdateLight() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.getClientWorlds().forEach(world -> {
            if (!RenderStates.isDimensionRendered(world.dimension())) {
                world.getChunkSource().getLightEngine().runLightUpdates();
            }
        });
    }
    
    /**
     * If we don't do this
     * the future created in {@link SectionRenderDispatcher#uploadSectionLayer}
     * may never complete
     *
     * TODO MC 26.1: {@code SectionRenderDispatcher.uploadAllPendingUploads()} was removed
     * with no direct replacement (confirmed via javap -- the per-section async-upload-future
     * pumping concept from the old chunk-render pipeline doesn't appear to exist anymore in
     * the new one, which reworked chunk section compiling/uploading around
     * {@code RenderRegionCache}/{@code SectionCompiler}/{@code SectionMesh}). Stubbed as a
     * no-op for now (this was only a workaround for non-actively-rendered dimensions'
     * section uploads potentially stalling, gated behind the {@code IPCGlobal.earlyRemoteUpload}
     * debug toggle) -- needs real in-game testing across dimensions to confirm whether the
     * new pipeline still has this problem at all, and if so, what the equivalent fix is.
     */
    public static void earlyRemoteUpload() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        
        ClientWorldLoader.WORLD_RENDERER_MAP.forEach((dim, worldRenderer) -> {
            if (client.level.dimension() != dim) {
                // TODO MC 26.1: no replacement found for uploadAllPendingUploads(); see above
            }
        });
    }
    
    public static void applyMirrorFaceCulling() {
        glCullFace(GL_FRONT);
    }
    
    public static void recoverFaceCulling() {
        glCullFace(GL_BACK);
    }
    
    public static void clearAlphaTo1(RenderTarget mcFrameBuffer) {
        // TODO MC 26.1: RenderTarget.bindWrite/RenderSystem.clear(int,boolean) removed;
        // clearing now goes through RenderSystem.getDevice().createCommandEncoder()
        // .clearColorAndDepthTextures(...) operating on GpuTexture objects directly.
        // Stubbed no-op pending the broader RendererUsingFrameBuffer redesign.
    }
    
    public static void restoreViewPort() {
        Minecraft client = Minecraft.getInstance();
        GlStateManager._viewport(
            0,
            0,
            client.getWindow().getWidth(),
            client.getWindow().getHeight()
        );
    }
    
    public static float transformFogDistance(float value) {
        if (!WorldRenderInfo.isFogEnabled()) {
            return value * 23333;
        }
        
        // just disable fog for fuse-view portals for now
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            
            if (renderingPortal.isFuseView()) {
                return value * 23333;
            }
        }
        
        // as non-fuse-view portals does not apply scale transformation to modelview,
        // there is no need to transform fog distance (both with and without sodium)
        
        return value;
    }
    
    private static boolean debugEnabled = false;
    
    public static void debugFramebufferDepth() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;
        
        int width = client.getMainRenderTarget().width;
        int height = client.getMainRenderTarget().height;
        
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();
        
        glReadPixels(
            0, 0, width, height,
            GL_DEPTH_COMPONENT, GL_FLOAT, floatBuffer
        );
        
        float[] data = new float[width * height];
        
        floatBuffer.rewind();
        floatBuffer.get(data);
        
        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();
        
        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];
            
            datum = (datum - minValue) / (maxValue - minValue);
            
            grayData[i] = (byte) (datum * 255);
        }
        
        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        
        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );
        
        System.out.println("oops");
    }
    
    public static void debugFramebufferColorRed() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;
        
        int width = client.getMainRenderTarget().width;
        int height = client.getMainRenderTarget().height;
        
        
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        
        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();
        
        glReadPixels(
            0, 0, width, height,
            GL_RED, GL_FLOAT, floatBuffer
        );
        
        float[] data = new float[width * height];
        
        floatBuffer.rewind();
        floatBuffer.get(data);
        
        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();
        
        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];
            
            datum = (datum - minValue) / (maxValue - minValue);
            
            grayData[i] = (byte) (datum * 255);
        }
        
        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        
        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );
        
        System.out.println("oops");
    }
}
