package com.mcmetal.metal;

import com.mcmetal.MCMetal;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class MetalRenderPipeline implements BackendRenderPipeline {
	private final MetalDevice device;
	private final long handle;
	private final List<BindGroupLayout.UniformDescription> uniforms;
	private final int[] stageMasks;
	private final String name;
	private BackendRenderPipeline.@Nullable CreateInfo createInfo;
	private @Nullable Object pack;
	/** Buffer index where a pack pipeline's vertex stage reads vertex buffer 0 directly, or -1. */
	private int vertexPullSlot = -1;
	private boolean closed;

	private MetalRenderPipeline(final MetalDevice device, final long handle, final List<BindGroupLayout.UniformDescription> uniforms, final int[] stageMasks,
		final String name) {
		this.device = device;
		this.handle = handle;
		this.uniforms = uniforms;
		this.stageMasks = stageMasks;
		this.name = name;
	}

	/** A pipeline built by the shaderpack runtime: the game's uniform layout, with the pack's shaders. */
	public static MetalRenderPipeline createPack(final MetalDevice device, final long handle, final List<BindGroupLayout.UniformDescription> uniforms,
		final int[] stageMasks, final String name, final Object pack) {
		MetalRenderPipeline pipeline = new MetalRenderPipeline(device, handle, uniforms, stageMasks, name);
		pipeline.pack = pack;
		return pipeline;
	}

	public void setVertexPullSlot(final int slot) {
		this.vertexPullSlot = slot;
	}

	int vertexPullSlot() {
		return this.vertexPullSlot;
	}

	long handle() {
		return this.handle;
	}

	public long nativeHandle() {
		return this.handle;
	}

	public List<BindGroupLayout.UniformDescription> uniforms() {
		return this.uniforms;
	}

	/** The game pipeline's name, e.g. "minecraft:pipeline/solid_terrain". */
	public String name() {
		return this.name;
	}

	/** How the game created this pipeline (null for pack pipelines). */
	public BackendRenderPipeline.@Nullable CreateInfo createInfo() {
		return this.createInfo;
	}

	/** The shaderpack runtime's data for a pack pipeline. */
	public @Nullable Object pack() {
		return this.pack;
	}

	/** Per uniform: bit 0 = used by the vertex stage, bit 1 = used by the fragment stage. */
	int[] stageMasks() {
		return this.stageMasks;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			long handle = this.handle;
			this.device.createCommandEncoder().queueForDestroy(() -> Native.pipelineDestroy(handle));
		}
	}

	/** {@code -Dmcmetal.dumpShaders=<dir>} writes every translated shader there, for inspection. */
	private static final @Nullable String DUMP_DIR = System.getProperty("mcmetal.dumpShaders");

	private static void dumpShader(final String pipeline, final ShaderType type, final String source) {
		if (DUMP_DIR == null) {
			return;
		}
		String name = pipeline.replaceAll("[^A-Za-z0-9_.-]", "_") + (type == ShaderType.VERTEX ? ".vert" : ".frag") + ".metal";
		try {
			java.nio.file.Path dir = java.nio.file.Path.of(DUMP_DIR);
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.writeString(dir.resolve(name), source);
		} catch (java.io.IOException e) {
			MCMetal.LOGGER.warn("Couldn't dump shader {}", name, e);
		}
	}

	static @Nullable MetalRenderPipeline compile(final MetalDevice device, final BackendRenderPipeline.CreateInfo info) {
		List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
		if (uniforms.size() > Native.MAX_UNIFORMS) {
			MCMetal.LOGGER.error("Pipeline {} uses {} uniforms, more than the {} Metal slots reserved", info.name(), uniforms.size(), Native.MAX_UNIFORMS);
			return null;
		}

		long vertexLibrary = 0L;
		long fragmentLibrary = 0L;
		long vertexFunction = 0L;
		long fragmentFunction = 0L;
		try {
			int[] stageMasks = new int[uniforms.size()];
			for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
				MetalShaderCompiler.Result msl = MetalShaderCompiler.translate(shader.module(), shader.entryPoint(), uniforms.size());
				dumpShader(info.name(), shader.module().type(), msl.source());
				long library;
				try {
					library = Native.libraryCreate(device.context(), msl.source());
				} catch (IllegalStateException e) {
					MCMetal.LOGGER.error("Metal rejected translated {} shader {}:\n{}\n--- source ---\n{}", shader.module().type(), shader.name(), e.getMessage(), msl.source());
					return null;
				}
				long function = Native.functionCreate(library, msl.entryPoint());
				boolean vertex = shader.module().type() == ShaderType.VERTEX;
				if (vertex) {
					vertexLibrary = library;
					vertexFunction = function;
				} else {
					fragmentLibrary = library;
					fragmentFunction = function;
				}
				if (function == 0L) {
					MCMetal.LOGGER.error("Entry point {} missing from translated shader {}", msl.entryPoint(), shader.name());
					return null;
				}
				for (int i = 0; i < stageMasks.length; i++) {
					if ((msl.usedUniforms() & (1L << i)) != 0L) {
						stageMasks[i] |= vertex ? 1 : 2;
					}
				}
			}
			if (vertexFunction == 0L) {
				MCMetal.LOGGER.error("Pipeline {} has no vertex shader", info.name());
				return null;
			}

			long handle = Native.pipelineCreate(device.context(), vertexFunction, fragmentFunction, encodeDescriptor(info), info.name());
			MetalRenderPipeline pipeline = new MetalRenderPipeline(device, handle, uniforms, stageMasks, info.name());
			pipeline.createInfo = info;
			return pipeline;
		} catch (RuntimeException e) {
			MCMetal.LOGGER.error("Couldn't compile Metal pipeline {}", info.name(), e);
			return null;
		} finally {
			// The pipeline state object keeps what it needs; functions and libraries can go immediately.
			Native.release(vertexFunction);
			Native.release(fragmentFunction);
			Native.release(vertexLibrary);
			Native.release(fragmentLibrary);
		}
	}

	/** See mcm_pipeline_create in src/native/mcmetal.mm for the layout. */
	static int[] encodeDescriptor(final BackendRenderPipeline.CreateInfo info) {
		IntArrayList d = new IntArrayList(64);
		List<@Nullable ColorTargetState> colors = info.colorTargetStates();
		DepthStencilState depth = info.depthStencilState();
		d.add(colors.size());
		d.add(depth != null ? 1 : 0);
		d.add(depth != null ? MetalConst.compareOp(depth.depthTest()) : 0);
		d.add(depth != null && depth.writeDepth() ? 1 : 0);
		d.add(Float.floatToRawIntBits(depth != null ? depth.depthBiasConstant() : 0.0F));
		d.add(Float.floatToRawIntBits(depth != null ? depth.depthBiasScaleFactor() : 0.0F));
		d.add(info.cull() ? 1 : 0);
		d.add(info.polygonMode() == PolygonMode.WIREFRAME ? 1 : 0);
		d.add(MetalConst.primitive(info.primitiveTopology()));
		d.add(info.vertexBuffers().size());
		d.add(info.attribBindings().size());

		for (ColorTargetState color : colors) {
			if (color == null) {
				d.addElements(d.size(), new int[]{-1, 0, 0, 0, 0, 0, 0, 0, 0});
				continue;
			}
			d.add(MetalConst.format(color.format()));
			d.add(color.writeMask());
			if (color.blendFunction().isPresent()) {
				BlendFunction blend = color.blendFunction().get();
				d.add(1);
				d.add(MetalConst.blendOp(blend.color().op()));
				d.add(MetalConst.blendOp(blend.alpha().op()));
				d.add(MetalConst.blendFactor(blend.color().sourceFactor()));
				d.add(MetalConst.blendFactor(blend.color().destFactor()));
				d.add(MetalConst.blendFactor(blend.alpha().sourceFactor()));
				d.add(MetalConst.blendFactor(blend.alpha().destFactor()));
			} else {
				d.addElements(d.size(), new int[]{0, 0, 0, 0, 0, 0, 0});
			}
		}
		for (BackendRenderPipeline.CreateInfo.VertexBuffer buffer : info.vertexBuffers()) {
			d.add(buffer.bufferSlot());
			d.add(buffer.stride());
			d.add(buffer.stepRate());
		}
		for (BackendRenderPipeline.CreateInfo.AttribBinding attribute : info.attribBindings()) {
			d.add(attribute.location());
			d.add(attribute.bufferSlot());
			d.add(attribute.offset());
			d.add(MetalConst.format(attribute.format()));
		}
		return d.toIntArray();
	}
}
