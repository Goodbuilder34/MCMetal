package com.mcmetal.metal;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/**
 * SPIR-V (from Mojang's GLSL frontend) to Metal Shading Language via SPIRV-Cross.
 *
 * <p>Binding contract (must match src/native/mcmetal.h): uniform {@code i} of the pipeline (set 0, binding i) maps to
 * buffer/texture/sampler slot {@code i} in both stages, push constants to buffer 16, and vertex buffer slot {@code s}
 * to buffer {@code 24 + s} (declared by the pipeline's vertex descriptor).
 */
final class MetalShaderCompiler {
	private static final int MSL_VERSION_3_0 = 30000;
	private static final int EXECUTION_MODEL_VERTEX = 0;
	private static final int EXECUTION_MODEL_FRAGMENT = 4;
	private static final int CAPTURE_MODE_COPY = 0;

	private MetalShaderCompiler() {
	}

	record Result(String source, String entryPoint, long usedUniforms) {
	}

	static Result translate(final SpvModule module, final String entryPoint, final int uniformCount) {
		boolean vertex = module.type() == ShaderType.VERTEX;
		int executionModel = vertex ? EXECUTION_MODEL_VERTEX : EXECUTION_MODEL_FRAGMENT;
		IntBuffer spirv = module.spv().asIntBuffer();
		long context = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(Spvc.spvc_context_create(out), "create context", 0L);
			context = out.get(0);
			check(Spvc.spvc_context_parse_spirv(context, spirv, spirv.remaining(), out), "parse SPIR-V", context);
			long ir = out.get(0);
			check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, CAPTURE_MODE_COPY, out), "create MSL compiler", context);
			long compiler = out.get(0);

			check(Spvc.spvc_compiler_create_compiler_options(compiler, out), "create options", context);
			long options = out.get(0);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_0);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
			if (vertex) {
				// The game is written against Vulkan's y-down clip space; Metal's is y-up.
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
			}
			check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install options", context);
			check(Spvc.spvc_compiler_set_entry_point(compiler, entryPoint, executionModel), "set entry point", context);

			SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
			for (int i = 0; i < uniformCount; i++) {
				Spvc.spvc_msl_resource_binding_init(binding);
				binding.stage(executionModel).desc_set(0).binding(i).msl_buffer(i).msl_texture(i).msl_sampler(i);
				check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add resource binding", context);
			}
			Spvc.spvc_msl_resource_binding_init(binding);
			binding.stage(executionModel)
				.desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET)
				.binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING)
				.msl_buffer(Native.PUSH_CONSTANT_BUFFER_INDEX);
			check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add push constant binding", context);

			check(Spvc.spvc_compiler_compile(compiler, out), "compile", context);
			String source = MemoryUtil.memUTF8(out.get(0));

			long used = 0L;
			for (int i = 0; i < uniformCount; i++) {
				if (Spvc.spvc_compiler_msl_is_resource_used(compiler, executionModel, 0, i)) {
					used |= 1L << i;
				}
			}
			String cleansed = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, entryPoint, executionModel);
			return new Result(source, cleansed != null ? cleansed : entryPoint, used);
		} finally {
			if (context != 0L) {
				Spvc.spvc_context_destroy(context);
			}
		}
	}

	private static void check(final int result, final String step, final long context) {
		if (result != Spvc.SPVC_SUCCESS) {
			String detail = context != 0L ? Spvc.spvc_context_get_last_error_string(context) : null;
			throw new IllegalStateException("SPIRV-Cross failed to " + step + (detail != null ? ": " + detail : ""));
		}
	}
}
