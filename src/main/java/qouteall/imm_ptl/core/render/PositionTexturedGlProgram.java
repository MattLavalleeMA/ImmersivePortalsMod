package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import qouteall.q_misc_util.my_util.TriangleConsumer;

/**
 * A minimal raw-OpenGL (LWJGL) position-only triangle renderer that samples a given
 * raw GL texture id per-fragment using the screen pixel coordinate ({@code
 * gl_FragCoord}) as the UV, bypassing Blaze3D's {@code RenderPipeline}/{@code
 * RenderPass}/{@code GpuBuffer} abstraction entirely.
 * <p>
 * Used to composite a portal's separately-rendered nested-world content (drawn into
 * its own offscreen {@link SecondaryFrameBuffer}, at the same resolution/viewport as
 * the main window) onto the main framebuffer: drawing the portal's own screen-facing
 * quad geometry (transformed by the SAME outer-camera modelView/projection matrices
 * used for the rest of the frame) means the draw call's own rasterized shape *is*
 * the mask -- no stencil test or clip planes needed.
 * <p>
 * Occlusion ("is something already in front of the portal surface") is decided
 * manually in the fragment shader instead of relying on the GPU's hardware depth
 * test/occlusion queries (both proved unreliable for this draw, see
 * docs/portal-rendering-pipeline-and-invisible-content-bug.md sections 2.12/2.16+)
 * -- a real depth texture of the scene already rendered so far ({@code
 * SceneDepthTex}) is sampled per-fragment and compared against this quad's own
 * {@code gl_FragCoord.z} (both computed from the SAME outer-camera modelView/
 * projection matrices used for the rest of the frame, so directly comparable),
 * discarding the fragment if the real scene is nearer. This is combined with a
 * simple "was anything actually drawn here" alpha check on the source color
 * texture (cleared to alpha 0 before the nested content render) as a defense-in-
 * depth fallback for the rare case nothing rendered at all (e.g. unloaded chunks
 * at the destination). Draw targets a color-only framebuffer (no depth
 * attachment) with hardware depth test disabled, so sampling the real depth
 * texture here is never a read/write feedback hazard.
 * <p>
 * Sibling to {@link PositionColorGlProgram} (same rationale for why this bypasses the
 * normal pipeline -- see that class's javadoc).
 */
public class PositionTexturedGlProgram {
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
            "uniform sampler2D ColorTex;\n" +
            "uniform sampler2D SceneDepthTex;\n" +
            "uniform vec2 ViewportSize;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec2 uv = gl_FragCoord.xy / ViewportSize;\n" +
            "    vec4 c = texture(ColorTex, uv);\n" +
            "    if (c.a < 0.5) {\n" +
            "        discard;\n" +
            "    }\n" +
            "    float sceneDepth = texture(SceneDepthTex, uv).r;\n" +
            "    if (gl_FragCoord.z >= sceneDepth) {\n" +
            "        discard;\n" +
            "    }\n" +
            "    fragColor = c;\n" +
            "}\n";

    private static int programId = -1;
    private static int vaoId = -1;
    private static int vboId = -1;
    private static int uniformModelView;
    private static int uniformProjection;
    private static int uniformColorTex;
    private static int uniformSceneDepthTex;
    private static int uniformViewportSize;

    private static final FloatArrayList vertexBuffer = new FloatArrayList();

    public static final TriangleConsumer VERTEX_OUTPUT = PositionTexturedGlProgram::addTriangle;

    private static void ensureInitialized() {
        if (programId != -1) {
            return;
        }

        int vertexShader = GlStateManager.glCreateShader(GL20.GL_VERTEX_SHADER);
        GlStateManager.glShaderSource(vertexShader, VERTEX_SRC);
        GlStateManager.glCompileShader(vertexShader);
        checkShaderCompileStatus(vertexShader, "PositionTexturedGlProgram vertex shader");

        int fragmentShader = GlStateManager.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GlStateManager.glShaderSource(fragmentShader, FRAGMENT_SRC);
        GlStateManager.glCompileShader(fragmentShader);
        checkShaderCompileStatus(fragmentShader, "PositionTexturedGlProgram fragment shader");

        int program = GlStateManager.glCreateProgram();
        GlStateManager.glAttachShader(program, vertexShader);
        GlStateManager.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GlStateManager.glLinkProgram(program);

        int linkStatus = GlStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS);
        if (linkStatus == 0) {
            String log = GlStateManager.glGetProgramInfoLog(program, 32768);
            GlStateManager.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link PositionTexturedGlProgram: " + log);
        }

        GlStateManager.glDeleteShader(vertexShader);
        GlStateManager.glDeleteShader(fragmentShader);

        programId = program;
        uniformModelView = GlStateManager._glGetUniformLocation(programId, "ModelViewMat");
        uniformProjection = GlStateManager._glGetUniformLocation(programId, "ProjMat");
        uniformColorTex = GlStateManager._glGetUniformLocation(programId, "ColorTex");
        uniformSceneDepthTex = GlStateManager._glGetUniformLocation(programId, "SceneDepthTex");
        uniformViewportSize = GlStateManager._glGetUniformLocation(programId, "ViewportSize");

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
     * Starts a draw batch: binds the program, sets its uniforms (including binding
     * {@code rawGlColorTextureId} to texture unit 0 and {@code
     * rawGlSceneDepthTextureId} -- the real scene's already-rendered depth texture,
     * used for the manual per-fragment occlusion compare, see class javadoc -- to
     * texture unit 1), and clears the accumulated vertex buffer. Call
     * {@link #addTriangle} to accumulate triangles, then {@link #end()} to actually
     * issue the draw call.
     */
    public static void begin(
        Matrix4f modelView, Matrix4f projection,
        int rawGlColorTextureId, int rawGlSceneDepthTextureId,
        int viewportWidth, int viewportHeight
    ) {
        ensureInitialized();

        vertexBuffer.clear();

        GlStateManager._glUseProgram(programId);

        float[] mv = new float[16];
        modelView.get(mv);
        GL20.glUniformMatrix4fv(uniformModelView, false, mv);

        float[] proj = new float[16];
        projection.get(proj);
        GL20.glUniformMatrix4fv(uniformProjection, false, proj);

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(rawGlColorTextureId);
        GL20.glUniform1i(uniformColorTex, 0);

        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(rawGlSceneDepthTextureId);
        GL20.glUniform1i(uniformSceneDepthTex, 1);

        GlStateManager._activeTexture(GL13.GL_TEXTURE0);

        GL20.glUniform2f(uniformViewportSize, (float) viewportWidth, (float) viewportHeight);

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

        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GlStateManager._glBindVertexArray(0);

        vertexBuffer.clear();

        qouteall.imm_ptl.core.CHelper.doCheckGlError();
    }
}
