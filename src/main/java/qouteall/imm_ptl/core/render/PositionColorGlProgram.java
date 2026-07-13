package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import qouteall.q_misc_util.my_util.TriangleConsumer;

/**
 * A minimal raw-OpenGL (LWJGL) position-only triangle renderer with a uniform color,
 * used to draw the portal view-area mask geometry and full-screen triangles directly
 * into the stencil/depth/color buffers, bypassing Blaze3D's
 * {@code RenderPipeline}/{@code RenderPass}/{@code GpuBuffer} abstraction entirely.
 * <p>
 * This exists because MC 26.1's {@code RenderPipeline} (via
 * {@code com.mojang.blaze3d.pipeline.DepthStencilState}) has no dynamic stencil-test
 * state at all - a pipeline-based draw call cannot participate in the raw
 * {@code glStencilFunc}/{@code glStencilOp} state {@code RendererUsingStencil} sets up
 * around these masking draws. Since the old {@code ShaderInstance}/{@code Tesselator}/
 * {@code BufferBuilder}/{@code BufferUploader} classes this used to be built on no
 * longer exist either, this hand-compiles a tiny GLSL program and issues real
 * {@code glDrawArrays} calls directly via LWJGL, using {@link GlStateManager}'s
 * wrapped GL calls wherever one exists (so its internal state cache, e.g. "current
 * program", stays in sync with what vanilla/Sodium code observes on its next draw).
 */
public class PositionColorGlProgram {
    private static final String VERTEX_SRC =
        "#version 150\n" +
            "in vec3 Position;\n" +
            "uniform mat4 ModelViewMat;\n" +
            "uniform mat4 ProjMat;\n" +
            "void main() {\n" +
            "    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_SRC =
        "#version 150\n" +
            "uniform vec4 Color;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = Color;\n" +
            "}\n";

    private static int programId = -1;
    private static int vaoId = -1;
    private static int vboId = -1;
    private static int uniformModelView;
    private static int uniformProjection;
    private static int uniformColor;

    private static final FloatArrayList vertexBuffer = new FloatArrayList();

    public static final TriangleConsumer VERTEX_OUTPUT = PositionColorGlProgram::addTriangle;

    private static void ensureInitialized() {
        if (programId != -1) {
            return;
        }

        int vertexShader = GlStateManager.glCreateShader(GL20.GL_VERTEX_SHADER);
        GlStateManager.glShaderSource(vertexShader, VERTEX_SRC);
        GlStateManager.glCompileShader(vertexShader);
        checkShaderCompileStatus(vertexShader, "PositionColorGlProgram vertex shader");

        int fragmentShader = GlStateManager.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GlStateManager.glShaderSource(fragmentShader, FRAGMENT_SRC);
        GlStateManager.glCompileShader(fragmentShader);
        checkShaderCompileStatus(fragmentShader, "PositionColorGlProgram fragment shader");

        int program = GlStateManager.glCreateProgram();
        GlStateManager.glAttachShader(program, vertexShader);
        GlStateManager.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GlStateManager.glLinkProgram(program);

        int linkStatus = GlStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS);
        if (linkStatus == 0) {
            String log = GlStateManager.glGetProgramInfoLog(program, 32768);
            GlStateManager.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link PositionColorGlProgram: " + log);
        }

        GlStateManager.glDeleteShader(vertexShader);
        GlStateManager.glDeleteShader(fragmentShader);

        programId = program;
        uniformModelView = GlStateManager._glGetUniformLocation(programId, "ModelViewMat");
        uniformProjection = GlStateManager._glGetUniformLocation(programId, "ProjMat");
        uniformColor = GlStateManager._glGetUniformLocation(programId, "Color");

        vaoId = GlStateManager._glGenVertexArrays();
        vboId = GlStateManager._glGenBuffers();
    }

    private static void checkShaderCompileStatus(int shader, String name) {
        int status = GlStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (status == 0) {
            String log = GlStateManager.glGetShaderInfoLog(shader, 32768);
            throw new IllegalStateException("Failed to compile " + name + ": " + log);
        }
    }

    /**
     * Starts a draw batch: binds the program, sets its uniforms, and clears the
     * accumulated vertex buffer. Call {@link #addTriangle} (or pass
     * {@link #VERTEX_OUTPUT} to a {@link TriangleConsumer}-based mesh builder) to
     * accumulate triangles, then {@link #end()} to actually issue the draw call.
     */
    // TEMP DIAGNOSTIC (2026-07-12): kept so end() can manually re-transform the
    // first vertex to check whether the geometry actually lands on-screen in valid
    // clip space. Remove once root-caused/fixed.
    private static final Matrix4f lastModelView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();

    public static void begin(Matrix4f modelView, Matrix4f projection, Vec3 color, float alpha) {
        ensureInitialized();

        vertexBuffer.clear();

        lastModelView.set(modelView);
        lastProjection.set(projection);

        GlStateManager._glUseProgram(programId);

        float[] mv = new float[16];
        modelView.get(mv);
        GL20.glUniformMatrix4fv(uniformModelView, false, mv);

        float[] proj = new float[16];
        projection.get(proj);
        GL20.glUniformMatrix4fv(uniformProjection, false, proj);

        GL20.glUniform4f(uniformColor, (float) color.x, (float) color.y, (float) color.z, alpha);

        GlStateManager._glBindVertexArray(vaoId);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0L);
        GlStateManager._enableVertexAttribArray(0);
    }

    public static void addTriangle(
        double p0x, double p0y, double p0z,
        double p1x, double p1y, double p1z,
        double p2x, double p2y, double p2z
    ) {
        vertexBuffer.add((float) p0x);
        vertexBuffer.add((float) p0y);
        vertexBuffer.add((float) p0z);
        vertexBuffer.add((float) p1x);
        vertexBuffer.add((float) p1y);
        vertexBuffer.add((float) p1z);
        vertexBuffer.add((float) p2x);
        vertexBuffer.add((float) p2y);
        vertexBuffer.add((float) p2z);
    }

// TEMP DIAGNOSTIC (2026-07-12): records the vertex/triangle count and relevant GL
    // fixed-function state of the last end() call, so callers (RendererUsingStencil)
    // can log it alongside the occlusion-query result. Remove once root-caused/fixed.
    public static int lastVertexCount = -1;
    public static String lastDrawStateDiag = "";

    /**
     * Uploads the accumulated vertex buffer and issues the actual draw call, then
     * unbinds. No-op (draws nothing) if no triangles were accumulated since
     * {@link #begin}.
     */
    public static void end() {
        int vertexCount = vertexBuffer.size() / 3;
        
        if (vertexCount > 0) {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer.toFloatArray(), GL15.GL_STREAM_DRAW);
            GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        }
        
        // TEMP DIAGNOSTIC (2026-07-12): manually re-transform EVERY vertex through
        // the same modelView/projection the shader used, to check whether the
        // geometry actually lands within valid clip space / where on screen (not
        // just the first vertex -- a single off-screen corner is normal for a
        // close-up quad, need the full picture to tell if the WHOLE thing misses).
        // Remove once root-caused/fixed.
        if (vertexCount > 0) {
            int winW = qouteall.imm_ptl.core.render.renderer.PortalRenderer.client.getWindow().getWidth();
            int winH = qouteall.imm_ptl.core.render.renderer.PortalRenderer.client.getWindow().getHeight();
            StringBuilder sb = new StringBuilder();
            float minNdcX = Float.MAX_VALUE, maxNdcX = -Float.MAX_VALUE;
            float minNdcY = Float.MAX_VALUE, maxNdcY = -Float.MAX_VALUE;
            boolean anyOnScreen = false;
            for (int i = 0; i < vertexCount; i++) {
                org.joml.Vector4f clip = new org.joml.Vector4f(
                    vertexBuffer.getFloat(i * 3), vertexBuffer.getFloat(i * 3 + 1), vertexBuffer.getFloat(i * 3 + 2), 1.0f
                );
                clip.mul(lastModelView);
                clip.mul(lastProjection);
                if (clip.w != 0) {
                    float ndcX = clip.x / clip.w;
                    float ndcY = clip.y / clip.w;
                    float ndcZ = clip.z / clip.w;
                    minNdcX = Math.min(minNdcX, ndcX);
                    maxNdcX = Math.max(maxNdcX, ndcX);
                    minNdcY = Math.min(minNdcY, ndcY);
                    maxNdcY = Math.max(maxNdcY, ndcY);
                    boolean onScreen = ndcX >= -1 && ndcX <= 1 && ndcY >= -1 && ndcY <= 1 && ndcZ >= -1 && ndcZ <= 1 && clip.w > 0;
                    anyOnScreen |= onScreen;
                    sb.append("[v").append(i).append(" w=").append(clip.w)
                        .append(" ndc=(").append(ndcX).append(",").append(ndcY).append(",").append(ndcZ)
                        .append(") onScreen=").append(onScreen).append("] ");
                }
                else {
                    sb.append("[v").append(i).append(" w=0 degenerate] ");
                }
            }
            qouteall.q_misc_util.Helper.log(
                "[PORTAL-SKIP-DIAG] allVertexTransforms anyOnScreen=" + anyOnScreen
                    + " ndcXRange=(" + minNdcX + "," + maxNdcX + ") ndcYRange=(" + minNdcY + "," + maxNdcY + ") "
                    + sb
            );
        }
        
        // TEMP DIAGNOSTIC (2026-07-12): capture fixed-function state that could make
        // an otherwise-correct draw produce zero passing samples (culling, depth
        // func/test, stencil func/test), captured BEFORE unbinding so it reflects the
        // exact state the draw above just executed under.
        lastVertexCount = vertexCount;
        lastDrawStateDiag = "cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE)
            + " cullFaceMode=" + GL11.glGetInteger(GL11.GL_CULL_FACE_MODE)
            + " frontFace=" + GL11.glGetInteger(GL11.GL_FRONT_FACE)
            + " depthTest=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
            + " depthFunc=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
            + " depthMask=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
            + " stencilTest=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
            + " stencilFunc=" + GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
            + " stencilRef=" + GL11.glGetInteger(GL11.GL_STENCIL_REF)
            + " stencilValueMask=" + GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK)
            + " colorMask=" + java.util.Arrays.toString(getBooleanv(GL11.GL_COLOR_WRITEMASK, 4));
        
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);
        
        vertexBuffer.clear();
        
        // TEMP DIAGNOSTIC (2026-07-12): unconditional GL error check (bypasses
        // IPGlobal.doCheckGlError, which defaults to false) to help diagnose the
        // portal-viewport visual bugs reported after this class was introduced.
        // Remove once root-caused/fixed.
        qouteall.imm_ptl.core.CHelper.doCheckGlError();
    }
    
    // TEMP DIAGNOSTIC helper (2026-07-12): remove alongside the rest of this file's
    // temp diagnostics once root-caused/fixed.
    private static boolean[] getBooleanv(int pname, int count) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(count);
        org.lwjgl.opengl.GL11.glGetBooleanv(pname, buf);
        boolean[] result = new boolean[count];
        for (int i = 0; i < count; i++) {
            result[i] = buf.get(i) != 0;
        }
        return result;
    }
}
