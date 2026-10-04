package com.mcmetal.shaderpack;

import com.mcmetal.MCMetal;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslConstexprSampler;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * Compiles transformed pack GLSL to Metal Shading Language: shaderc (Vulkan target, relaxed rules, so loose uniforms
 * land in a default uniform block) to SPIR-V, then SPIRV-Cross to MSL with resources placed by a {@link BindingPlan}.
 * Returns the reflected layout of the default uniform block so the runtime can fill it by uniform name.
 */
public final class PackCompiler {
	/** Metal buffer slot of each stage's default uniform block (slot 16 is push constants, 24+ vertex buffers). */
	public static final int DEFAULT_BLOCK_SLOT = 18;

	public enum ShaderStage { VERTEX, FRAGMENT, COMPUTE }

	public record UniformMember(String name, int offset, int baseType, int vecSize, int columns, int arraySize, int arrayStride, int matrixStride) {
	}

	public record UniformBlock(int size, List<UniformMember> members) {
	}

	/** A sampled texture: where it goes in Metal and what kind of image the shader declared. */
	public record TextureBinding(String name, int texture, int sampler, int dimension, boolean depthCompare, boolean integer) {
	}

	public record Stage(String msl, String entryPoint, @Nullable UniformBlock defaults, List<TextureBinding> textures, List<String> blocks, boolean pullsVertices) {
	}

	/** Sampler state baked into the shader (MSL constexpr sampler), so pack textures need no sampler slots. */
	public record SamplerSpec(boolean linear, int mip, boolean repeat, boolean compare) {
		public static final SamplerSpec LINEAR_CLAMP = new SamplerSpec(true, 0, false, false);
		public static final SamplerSpec NEAREST_CLAMP = new SamplerSpec(false, 0, false, false);
		public static final SamplerSpec LINEAR_REPEAT = new SamplerSpec(true, 0, true, false);
		public static final SamplerSpec COMPARE_LINEAR = new SamplerSpec(true, 0, false, true);
		public static final SamplerSpec COMPARE_NEAREST = new SamplerSpec(false, 0, false, true);
	}

	/** Decides the Metal slots for a program's resources (shared by its stages, so they agree). */
	public interface BindingPlan {
		/** Metal texture slot for a sampler uniform, or -1 if it isn't supported (it's then left unbound). */
		int texture(String name);

		/** Metal sampler slot for a sampler uniform the game binds itself (atlas, lightmap); unused when {@link #samplerState} isn't null. */
		int sampler(String name, boolean depthCompare);

		/** Fixed sampler state for a pack texture, or null when the sampler comes from a slot at runtime. */
		default @Nullable SamplerSpec samplerState(final String name, final boolean depthCompare) {
			return null;
		}

		/** Metal buffer slot for a named uniform block other than the default block, or -1. */
		default int block(String name) {
			return -1;
		}
	}

	private static final int SPV_DECORATION_BINDING = 33;
	private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;
	private static final int MSL_VERSION_3_0 = 30000;

	private PackCompiler() {
	}

	/**
	 * Whether shaderc optimizes the SPIR-V before SPIRV-Cross (-Dmcmetal.packShaderOpt=false to turn off). Metal's
	 * compiler optimizes again, but spirv-opt also folds the pack's many constant-expression branches and dead code,
	 * which shortens what Metal compiles and sometimes what it can't remove itself.
	 */
	private static final boolean OPTIMIZE = !"false".equalsIgnoreCase(System.getProperty("mcmetal.packShaderOpt"));

	public static ByteBuffer toSpirv(final String name, final String source, final ShaderStage stage) {
		return toSpirv(name, source, stage, OPTIMIZE);
	}

	public static ByteBuffer toSpirv(final String name, final String source, final ShaderStage stage, final boolean optimize) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			if (optimize) {
				Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
				// Keeps OpName: bindings, uniform blocks and their members are matched by name.
				Shaderc.shaderc_compile_options_set_generate_debug_info(options);
			} else {
				Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
			}
			int kind = switch (stage) {
				case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
				case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
				case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
			};
			// Large sources don't fit LWJGL's thread-local stack, so encode them on the heap.
			ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, false);
			ByteBuffer nameBuffer = MemoryUtil.memUTF8(name);
			ByteBuffer entryBuffer = MemoryUtil.memUTF8("main");
			long result = Shaderc.shaderc_compile_into_spv(compiler, sourceBuffer, kind, nameBuffer, entryBuffer, options);
			MemoryUtil.memFree(sourceBuffer);
			MemoryUtil.memFree(nameBuffer);
			MemoryUtil.memFree(entryBuffer);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new PackException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	public static Stage compile(final String name, final String source, final ShaderStage stage, final BindingPlan plan) {
		return compile(name, source, stage, plan, true);
	}

	/**
	 * @param optimize whether spirv-opt may run (when enabled at all). It adds about half again to a program's compile
	 * time, so it is left to full-screen passes, which run per screen pixel; gbuffers variants are many and their
	 * cost is mostly in the pack's own math, which Metal's compiler optimizes anyway.
	 */
	public static Stage compile(final String name, final String source, final ShaderStage stage, final BindingPlan plan, final boolean optimize) {
		if (OPTIMIZE && optimize) {
			try {
				return compileWith(name, source, stage, plan, true);
			} catch (RuntimeException e) {
				// The optimizer occasionally trips over pack code the unoptimized path accepts.
				MCMetal.LOGGER.debug("[pack] {}: optimized compile failed, retrying unoptimized: {}", name, e.getMessage());
			}
		}
		return compileWith(name, source, stage, plan, false);
	}

	private static Stage compileWith(final String name, final String source, final ShaderStage stage, final BindingPlan plan, final boolean optimize) {
		ByteBuffer spirv = toSpirv(name, source, stage, optimize);
		try {
			return toMsl(name, spirv, stage, plan);
		} finally {
			MemoryUtil.memFree(spirv);
		}
	}

	private static Stage toMsl(final String name, final ByteBuffer spirv, final ShaderStage stage, final BindingPlan plan) {
		int executionModel = switch (stage) {
			case VERTEX -> 0;
			case FRAGMENT -> 4;
			case COMPUTE -> 5;
		};
		long context = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			check(Spvc.spvc_context_create(out), "create context", 0L, name);
			context = out.get(0);
			IntBuffer words = spirv.asIntBuffer();
			check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), out), "parse SPIR-V", context, name);
			long ir = out.get(0);
			check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, 0, out), "create MSL compiler", context, name);
			long compiler = out.get(0);

			check(Spvc.spvc_compiler_create_compiler_options(compiler, out), "create options", context, name);
			long options = out.get(0);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_0);
			Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_PAD_FRAGMENT_OUTPUT_COMPONENTS, true);
			Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
			if (stage == ShaderStage.VERTEX) {
				// Same convention as the game's own shaders: Vulkan-style y-down clip space.
				Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
			}
			check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install options", context, name);

			check(Spvc.spvc_compiler_create_shader_resources(compiler, out), "reflect", context, name);
			long resources = out.get(0);
			SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);

			// Uniform blocks and samplers may share a (set, binding) pair: merge their MSL slots into one entry each.
			Map<Long, int[]> bindings = new java.util.LinkedHashMap<>();
			UniformBlock defaults = null;
			List<String> blocks = new ArrayList<>();
			for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack)) {
				String blockName = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
				String instanceName = resource.nameString();
				int slot;
				if (blockName.contains("gl_DefaultUniformBlock") || instanceName.contains("gl_DefaultUniformBlock") || blockName.isEmpty()) {
					slot = DEFAULT_BLOCK_SLOT;
					defaults = reflectBlock(compiler, resource.base_type_id(), stack);
				} else {
					slot = plan.block(blockName);
					blocks.add(blockName);
					if (slot < 0) {
						throw new PackException(name + ": unexpected uniform block " + blockName);
					}
				}
				merge(bindings, compiler, resource.id(), slot, -1, -1);
			}

			List<TextureBinding> textures = new ArrayList<>();
			for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack)) {
				String sampler = resource.nameString();
				long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
				// Arrays of samplers aren't used by packs; the base type carries the image info.
				long imageType = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
				boolean depth = Spvc.spvc_type_get_image_is_depth(imageType);
				int dimension = Spvc.spvc_type_get_image_dimension(imageType);
				long sampledType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_image_sampled_type(imageType));
				int base = Spvc.spvc_type_get_basetype(sampledType);
				boolean integer = base == Spvc.SPVC_BASETYPE_INT32 || base == Spvc.SPVC_BASETYPE_UINT32;
				int texture = plan.texture(sampler);
				if (texture < 0) {
					texture = 127;  // unbound: reads zero
				}
				SamplerSpec spec = plan.samplerState(sampler, depth);
				int samplerSlot = spec == null ? plan.sampler(sampler, depth) : -1;
				if (spec != null) {
					SpvcMslConstexprSampler constexpr = SpvcMslConstexprSampler.calloc(stack);
					Spvc.spvc_msl_constexpr_sampler_init(constexpr);
					int filter = spec.linear() ? Spvc.SPVC_MSL_SAMPLER_FILTER_LINEAR : Spvc.SPVC_MSL_SAMPLER_FILTER_NEAREST;
					int address = spec.repeat() ? Spvc.SPVC_MSL_SAMPLER_ADDRESS_REPEAT : Spvc.SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
					constexpr.min_filter(filter).mag_filter(filter)
						.mip_filter(spec.mip() == 2 ? Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_LINEAR : spec.mip() == 1 ? Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_NEAREST : Spvc.SPVC_MSL_SAMPLER_MIP_FILTER_NONE)
						.s_address(address).t_address(address).r_address(address);
					if (spec.compare() && depth) {
						constexpr.compare_enable(true).compare_func(Spvc.SPVC_MSL_SAMPLER_COMPARE_FUNC_LESS_EQUAL);
					}
					check(Spvc.spvc_compiler_msl_remap_constexpr_sampler(compiler, resource.id(), constexpr), "remap sampler", context, name);
				}
				merge(bindings, compiler, resource.id(), -1, texture, samplerSlot);
				textures.add(new TextureBinding(sampler, texture, samplerSlot, dimension, depth, integer));
				if (Spvc.spvc_type_get_num_array_dimensions(type) > 0) {
					throw new PackException(name + ": sampler arrays are not supported (" + sampler + ")");
				}
			}
			if (!list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, stack).isEmpty()) {
				throw new PackException(name + ": image load/store (custom images) is not supported yet");
			}
			boolean pullsVertices = false;
			for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, stack)) {
				String blockName = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
				if (!blockName.equals(GbuffersEnvironment.VERTEX_DATA) || stage != ShaderStage.VERTEX) {
					throw new PackException(name + ": shader storage buffers are not supported yet");
				}
				pullsVertices = true;
				merge(bindings, compiler, resource.id(), GbuffersEnvironment.VERTEX_DATA_SLOT, -1, -1);
			}

			for (Map.Entry<Long, int[]> entry : bindings.entrySet()) {
				int[] b = entry.getValue();
				Spvc.spvc_msl_resource_binding_init(binding);
				binding.stage(executionModel).desc_set((int) (entry.getKey() >>> 32)).binding((int) (long) entry.getKey())
					.msl_buffer(Math.max(b[0], 0)).msl_texture(Math.max(b[1], 0)).msl_sampler(Math.max(b[2], 0));
				check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add resource binding", context, name);
			}
			check(Spvc.spvc_compiler_compile(compiler, out), "compile", context, name);
			String msl = MemoryUtil.memUTF8(out.get(0));
			String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, "main", executionModel);
			return new Stage(msl, entry != null ? entry : "main0", defaults, textures, blocks, pullsVertices);
		} finally {
			if (context != 0L) {
				Spvc.spvc_context_destroy(context);
			}
		}
	}

	private static void merge(final Map<Long, int[]> bindings, final long compiler, final int id, final int buffer, final int texture, final int sampler) {
		// Give every resource its own (set 0, binding n): glslang may hand the same binding to different blocks.
		int slot = bindings.size();
		Spvc.spvc_compiler_set_decoration(compiler, id, SPV_DECORATION_DESCRIPTOR_SET, 0);
		Spvc.spvc_compiler_set_decoration(compiler, id, SPV_DECORATION_BINDING, slot);
		int[] b = bindings.computeIfAbsent((long) slot, k -> new int[]{-1, -1, -1});
		if (buffer >= 0) {
			b[0] = buffer;
		}
		if (texture >= 0) {
			b[1] = texture;
		}
		if (sampler >= 0) {
			b[2] = sampler;
		}
	}

	private static List<SpvcReflectedResource> list(final long resources, final int type, final MemoryStack stack) {
		PointerBuffer listOut = stack.callocPointer(1);
		PointerBuffer countOut = stack.callocPointer(1);
		Spvc.spvc_resources_get_resource_list_for_type(resources, type, listOut, countOut);
		int count = (int) countOut.get(0);
		List<SpvcReflectedResource> result = new ArrayList<>(count);
		if (count > 0) {
			SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(listOut.get(0), count);
			for (int i = 0; i < count; i++) {
				result.add(buffer.get(i));
			}
		}
		return result;
	}

	private static UniformBlock reflectBlock(final long compiler, final int typeId, final MemoryStack stack) {
		long type = Spvc.spvc_compiler_get_type_handle(compiler, typeId);
		int count = Spvc.spvc_type_get_num_member_types(type);
		List<UniformMember> members = new ArrayList<>(count);
		IntBuffer value = stack.callocInt(1);
		for (int i = 0; i < count; i++) {
			String member = Spvc.spvc_compiler_get_member_name(compiler, typeId, i);
			Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, value);
			int offset = value.get(0);
			long memberType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_member_type(type, i));
			int arraySize = Spvc.spvc_type_get_num_array_dimensions(memberType) > 0 ? Spvc.spvc_type_get_array_dimension(memberType, 0) : 0;
			int arrayStride = 0;
			if (arraySize > 0) {
				Spvc.spvc_compiler_type_struct_member_array_stride(compiler, type, i, value);
				arrayStride = value.get(0);
			}
			int columns = Spvc.spvc_type_get_columns(memberType);
			int matrixStride = 0;
			if (columns > 1) {
				Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, value);
				matrixStride = value.get(0);
			}
			members.add(new UniformMember(member, offset, Spvc.spvc_type_get_basetype(memberType), Spvc.spvc_type_get_vector_size(memberType), columns, arraySize,
				arrayStride, matrixStride));
		}
		PointerBuffer size = stack.callocPointer(1);
		Spvc.spvc_compiler_get_declared_struct_size(compiler, type, size);
		return new UniformBlock((int) size.get(0), members);
	}

	private static void check(final int result, final String step, final long context, final String name) {
		if (result != Spvc.SPVC_SUCCESS) {
			String detail = context != 0L ? Spvc.spvc_context_get_last_error_string(context) : null;
			throw new PackException(name + ": SPIRV-Cross failed to " + step + (detail != null ? ": " + detail : ""));
		}
	}

	/** Names of a SPIR-V vertex shader's inputs by location (the game's shaders keep debug names). */
	public static java.util.Map<Integer, String> inputNames(final ByteBuffer spirv) {
		java.util.Map<Integer, String> names = new java.util.HashMap<>();
		long context = 0L;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer out = stack.callocPointer(1);
			if (Spvc.spvc_context_create(out) != Spvc.SPVC_SUCCESS) {
				return names;
			}
			context = out.get(0);
			IntBuffer words = spirv.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
			if (Spvc.spvc_context_parse_spirv(context, words, words.remaining(), out) != Spvc.SPVC_SUCCESS) {
				return names;
			}
			long ir = out.get(0);
			if (Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir, 0, out) != Spvc.SPVC_SUCCESS) {
				return names;
			}
			long compiler = out.get(0);
			Spvc.spvc_compiler_create_shader_resources(compiler, out);
			long resources = out.get(0);
			for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, stack)) {
				int location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), 30);
				names.put(location, resource.nameString());
			}
		} finally {
			if (context != 0L) {
				Spvc.spvc_context_destroy(context);
			}
		}
		return names;
	}
}
