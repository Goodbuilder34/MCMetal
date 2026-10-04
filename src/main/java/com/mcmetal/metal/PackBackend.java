package com.mcmetal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Direct Metal access for the shaderpack runtime: its own textures, pipelines compiled from MSL, and full-screen
 * passes. Everything is encoded into the frame's command buffer in order with the game's own work.
 */
public final class PackBackend {
	private static final int ENTRY_LONGS = 5;

	private final MetalDevice device;
	private final long context;
	private final Arena arena = Arena.ofShared();
	private final MemorySegment colors = this.arena.allocate(ValueLayout.JAVA_LONG, 8);
	private final MemorySegment clearFlags = this.arena.allocate(ValueLayout.JAVA_INT, 8);
	private final MemorySegment clearColors = this.arena.allocate(ValueLayout.JAVA_FLOAT, 32);
	private final MemorySegment entries = this.arena.allocate(ValueLayout.JAVA_LONG, 128L * ENTRY_LONGS);
	private final MemorySegment bytes = this.arena.allocate(65536, 16);

	public PackBackend(final MetalDevice device) {
		this.device = device;
		this.context = device.context();
	}

	public MetalDevice device() {
		return this.device;
	}

	public MetalCommandEncoder encoder() {
		return this.device.createCommandEncoder();
	}

	public static long handle(final GpuTexture texture) {
		return ((MetalTexture) texture).handle();
	}

	public static int blendFactorCode(final com.mojang.renderpearl.api.pipeline.BlendFactor factor) {
		return MetalConst.blendFactor(factor);
	}

	public static int formatCode(final GpuFormat format) {
		return MetalConst.format(format);
	}

	// ---------------------------------------------------------------------------------------------
	// Resources
	// ---------------------------------------------------------------------------------------------

	public long createTexture(final GpuFormat format, final int width, final int height, final int mips, final String label) {
		long handle = Native.textureCreate(this.context, MetalConst.format(format), Math.max(1, width), Math.max(1, height), 1, Math.max(1, mips), false);
		if (handle == 0L) {
			throw new IllegalStateException("Couldn't create " + label + " (" + format + " " + width + "x" + height + ")");
		}
		if (this.device.labels()) {
			Native.setLabel(handle, label);
		}
		return handle;
	}

	public long create3dTexture(final GpuFormat format, final int width, final int height, final int depth, final String label) {
		// The texture API has no 3D textures; a 2D texture array stands in (sampled through the same slot it reads zeros).
		return this.createTexture(format, width, height, 1, label);
	}

	/** Releases a texture once the frames that may use it have finished. */
	public void release(final long handle) {
		if (handle != 0L) {
			this.encoder().queueForDestroy(() -> Native.release(handle));
		}
	}

	public void upload(final long texture, final ByteBuffer data, final int width, final int height, final int bytesPerPixel) {
		MetalBuffer.Direct staging = new MetalBuffer.Direct(this.device, null, com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_SRC, data.remaining());
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), staging.contents(), data.remaining());
		Native.blitBufferToTexture(this.context, staging.handle(), 0L, width * bytesPerPixel, width * bytesPerPixel * height, texture, 0, 0, 0, 0, width, height);
		staging.close();
	}

	public void copy(final long source, final long destination, final int width, final int height) {
		Native.blitTextureToTexture(this.context, source, destination, 0, 0, 0, 0, 0, width, height);
	}

	public void clear(final long texture, final float r, final float g, final float b, final float a) {
		Native.clearTexture(this.context, texture, 0, 0, r, g, b, a, 1.0);
	}

	public void clearDepth(final long texture, final double depth) {
		Native.clearTexture(this.context, texture, 0, 0, 0.0F, 0.0F, 0.0F, 0.0F, depth);
	}

	public void generateMipmaps(final long texture) {
		Native.generateMipmaps(this.context, texture);
	}

	public boolean upscaleSupported() {
		return Native.upscaleSupported(this.context);
	}

	/** Upscales {@code source} into the whole of {@code destination} (MetalFX); false when that isn't possible. */
	public boolean upscale(final long source, final long destination) {
		return Native.upscale(this.context, source, destination);
	}

	// ---------------------------------------------------------------------------------------------
	// Pipelines
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds a pipeline from two MSL sources.
	 * @param descriptor the mcm_pipeline_create layout (see src/native/pipeline.mm)
	 */
	public long createPipeline(final String vertexMsl, final String vertexEntry, final String fragmentMsl, final String fragmentEntry, final int[] descriptor,
		final String label) {
		long vertexLibrary = 0L;
		long fragmentLibrary = 0L;
		long vertexFunction = 0L;
		long fragmentFunction = 0L;
		try {
			vertexLibrary = Native.libraryCreate(this.context, vertexMsl);
			fragmentLibrary = Native.libraryCreate(this.context, fragmentMsl);
			vertexFunction = Native.functionCreate(vertexLibrary, vertexEntry);
			fragmentFunction = Native.functionCreate(fragmentLibrary, fragmentEntry);
			if (vertexFunction == 0L || fragmentFunction == 0L) {
				throw new IllegalStateException("Missing entry point in " + label);
			}
			return Native.pipelineCreate(this.context, vertexFunction, fragmentFunction, descriptor, label);
		} finally {
			Native.release(vertexFunction);
			Native.release(fragmentFunction);
			Native.release(vertexLibrary);
			Native.release(fragmentLibrary);
		}
	}

	public void destroyPipeline(final long pipeline) {
		if (pipeline != 0L) {
			this.encoder().queueForDestroy(() -> Native.pipelineDestroy(pipeline));
		}
	}

	/** Wraps a pack pipeline so it can stand in for a game pipeline inside the game's own render passes. */
	public MetalRenderPipeline wrap(final long pipeline, final List<BindGroupLayout.UniformDescription> uniforms, final int[] stageMasks, final String name,
		final Object pack) {
		return MetalRenderPipeline.createPack(this.device, pipeline, uniforms, stageMasks, name, pack);
	}

	/** The descriptor the game used for one of its pipelines (vertex layout, blending...), as an int array to edit. */
	public static int[] descriptorOf(final MetalRenderPipeline vanilla) {
		return MetalRenderPipeline.encodeDescriptor(java.util.Objects.requireNonNull(vanilla.createInfo()));
	}

	// ---------------------------------------------------------------------------------------------
	// Passes (outside the game's own passes)
	// ---------------------------------------------------------------------------------------------

	/** Begins a pass on our own attachments. clears: per color attachment a clear color or null to load. */
	public void beginPass(final long[] colorTextures, final float @Nullable [][] clears, final long depth, final double clearDepth, final int width, final int height) {
		this.beginPass(colorTextures, clears, depth, clearDepth, width, height, false);
	}

	/**
	 * As above; {@code overwrite} promises that the pass writes every pixel of every color attachment it doesn't clear
	 * (no blending, no discard, full coverage), so their old contents needn't be loaded.
	 */
	public void beginPass(final long[] colorTextures, final float @Nullable [][] clears, final long depth, final double clearDepth, final int width, final int height,
		final boolean overwrite) {
		this.beginPass(colorTextures, clears, depth, clearDepth, width, height, overwrite, null);
	}

	public void beginPass(final long[] colorTextures, final float @Nullable [][] clears, final long depth, final double clearDepth, final int width, final int height,
		final boolean overwrite, final @Nullable String label) {
		int n = Math.min(colorTextures.length, 8);
		for (int i = 0; i < n; i++) {
			this.colors.setAtIndex(ValueLayout.JAVA_LONG, i, colorTextures[i]);
			float[] clear = clears != null ? clears[i] : null;
			this.clearFlags.setAtIndex(ValueLayout.JAVA_INT, i, clear != null ? 1 : overwrite ? 2 : 0);
			if (clear != null) {
				for (int c = 0; c < 4; c++) {
					this.clearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + c, clear[c]);
				}
			}
		}
		if (label != null && this.device.labels()) {
			try (Arena labelArena = Arena.ofConfined()) {
				Native.beginPass(this.context, this.colors.address(), this.clearFlags.address(), this.clearColors.address(), n, depth, !Double.isNaN(clearDepth),
					Double.isNaN(clearDepth) ? 0.0 : clearDepth, 0, 0, width, height, Native.cString(labelArena, label));
			}
			return;
		}
		Native.beginPass(this.context, this.colors.address(), this.clearFlags.address(), this.clearColors.address(), n, depth, !Double.isNaN(clearDepth),
			Double.isNaN(clearDepth) ? 0.0 : clearDepth, 0, 0, width, height, 0L);
	}

	public void endPass() {
		Native.passSuspend(this.context);
	}

	public void setPipeline(final long pipeline) {
		Native.passSetPipeline(this.context, pipeline);
	}

	/** Binds textures: each entry {stageMask, slot, textureHandle}. */
	public void bindTextures(final int[] stages, final int[] slots, final long[] textures, final int count) {
		for (int i = 0; i < count; i++) {
			long base = (long) i * ENTRY_LONGS;
			this.entries.setAtIndex(ValueLayout.JAVA_LONG, base, Native.BIND_TEXTURE | ((long) stages[i] << 8));
			this.entries.setAtIndex(ValueLayout.JAVA_LONG, base + 1, slots[i]);
			this.entries.setAtIndex(ValueLayout.JAVA_LONG, base + 2, textures[i]);
			this.entries.setAtIndex(ValueLayout.JAVA_LONG, base + 3, 0L);
		}
		if (count > 0) {
			Native.passBind(this.context, this.entries.address(), count);
		}
	}

	/** Scratch memory for uniform data (valid until the next call that uses it). */
	public ByteBuffer scratch(final int size) {
		ByteBuffer buffer = this.bytes.asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
		MemoryUtil.memSet(MemoryUtil.memAddress(buffer), 0, size);
		return buffer.limit(size);
	}

	public void setStageBytes(final int stages, final int slot, final ByteBuffer data) {
		Native.passSetStageBytes(this.context, stages, slot, MemoryUtil.memAddress(data), data.remaining());
	}

	public void draw(final int vertices) {
		Native.passDraw(this.context, vertices, 1, 0, 0);
	}
}
