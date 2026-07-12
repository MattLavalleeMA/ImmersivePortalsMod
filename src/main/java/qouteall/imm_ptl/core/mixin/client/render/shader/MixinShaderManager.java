package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.io.Reader;

// MC 26.1: re-anchors the custom clip-plane shader-source injection
// (ShaderCodeTransformation, driving FrontClipping's "iportal_ClippingEquation" uniform
// -- see shader_transformation.yaml) onto vanilla's new central shader compilation point.
// ShaderInstance/Program (the old per-shader compile classes this hooked into before)
// are gone; shaders are now all loaded through the private static
// ShaderManager.loadShader(Identifier, Resource, ShaderType, Map, Builder), which reads
// raw GLSL text via `IOUtils.toString(Reader)` (confirmed via decompiled 26.1.2 source)
// before handing it to the GlslPreprocessor. Wrapping that exact call is the same
// technique already used (and working) for Sodium's own shader loader in
// MixinSodiumShaderLoader.java -- this is the vanilla-shader equivalent of that fix,
// closing the gap for shaders NOT loaded through Sodium/Iris's own loaders.
// NOTE: this only re-establishes the GLSL source injection (the uniform declaration +
// gl_ClipDistance write). The other half -- actually setting
// "iportal_ClippingEquation"'s value once per draw against the new
// RenderPipeline/RenderPass/GpuBuffer-uniform model (FrontClipping
// .updateClippingEquationUniformForCurrentShader/.unsetClippingUniform, currently
// stubbed no-ops) -- is a separate, still-open piece; see docs/migration-26.1-plan.md.
@Mixin(ShaderManager.class)
public abstract class MixinShaderManager {
    @WrapOperation(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/Reader;)Ljava/lang/String;",
            remap = false
        )
    )
    private static String ip_transformShaderSource(
        Reader reader,
        Operation<String> operation,
        @Local(argsOnly = true) Identifier location,
        @Local(argsOnly = true) ShaderType type
    ) {
        String source = operation.call(reader);
        return ShaderCodeTransformation.transform(
            type == ShaderType.VERTEX
                ? ShaderCodeTransformation.ShaderType.vs
                : ShaderCodeTransformation.ShaderType.fs,
            location.toString(), source
        );
    }
}
