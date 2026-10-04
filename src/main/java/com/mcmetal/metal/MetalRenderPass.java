package com.mcmetal.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * One reusable instance per encoder. Uniform changes are tracked as a dirty bitmask and flushed to Metal in a
 * single native call right before each draw.
 */
public class MetalRenderPass implements RenderPassBackend {
	private static final int ENTRY_LONGS = 5;

	private final MetalDevice device;
	private final long context;
	private final MemorySegment bindEntries;
	private final @Nullable Object[] uniforms = new Object[Native.MAX_UNIFORMS];
	private @Nullable MetalRenderPipeline pipeline;
	private long dirtyUniforms;
	private RenderPass.RenderArea renderArea = new RenderPass.RenderArea(0, 0, 0, 0);
	private int debugGroups;

	MetalRenderPass(final MetalDevice device, final Arena arena) {
		this.device = device;
		this.context = device.context();
		this.bindEntries = arena.allocate(ValueLayout.JAVA_LONG, (long) Native.MAX_UNIFORMS * ENTRY_LONGS);
	}

	void begin(final RenderPass.RenderArea area) {
		this.renderArea = area;
		this.pipeline = null;
		this.dirtyUniforms = 0L;
		this.debugGroups = 0;
		Arrays.fill(this.uniforms, null);
	}

	void end() {
		while (this.debugGroups > 0) {
			this.popDebugGroup();
		}
		this.pipeline = null;
		Arrays.fill(this.uniforms, null);
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
		if (this.device.labels()) {
			this.debugGroups++;
			Native.pushDebugGroup(this.context, label.get());
		}
	}

	@Override
	public void popDebugGroup() {
		if (this.device.labels()) {
			if (this.debugGroups == 0) {
				throw new IllegalStateException("Can't pop more debug groups than was pushed!");
			}
			this.debugGroups--;
			Native.popDebugGroup(this.context);
		}
	}

	@Override
	public void setPipeline(final BackendRenderPipeline pipeline) {
		if (!(pipeline instanceof MetalRenderPipeline metalPipeline)) {
			throw new IllegalArgumentException("Pipeline must be instance of MetalRenderPipeline");
		}
		this.pipeline = metalPipeline;
		Native.passSetPipeline(this.context, metalPipeline.handle());
		Arrays.fill(this.uniforms, null);
		int count = metalPipeline.uniforms().size();
		this.dirtyUniforms = count >= 64 ? -1L : (1L << count) - 1L;
	}

	@Override
	public void setUniform(final int index, final @Nullable Object value) {
		this.uniforms[index] = value;
		this.dirtyUniforms |= 1L << index;
	}

	@Override
	public void pushConstants(final ByteBuffer value) {
		Native.passSetBytes(this.context, Native.PUSH_CONSTANT_BUFFER_INDEX, MemoryUtil.memAddress(value), value.remaining());
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		Native.passSetScissor(this.context, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		Native.passSetScissor(this.context, this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer != null) {
			Native.passSetVertexBuffer(this.context, slot, ((MetalBuffer) vertexBuffer.buffer()).handle(), vertexBuffer.offset());
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		Native.passSetIndexBuffer(this.context, ((MetalBuffer) indexBuffer).handle(), indexType.bytes);
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		this.flushUniforms();
		Native.passDrawIndexed(this.context, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.flushUniforms();
		Native.passMultiDrawIndexed(this.context, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.flushUniforms();
		Native.passDrawIndexedIndirect(this.context, ((MetalBuffer) commands.buffer()).handle(), commands.offset(), drawCount);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		this.flushUniforms();
		Native.passDraw(this.context, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.flushUniforms();
		Native.passMultiDraw(this.context, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.flushUniforms();
		Native.passDrawIndirect(this.context, ((MetalBuffer) commands.buffer()).handle(), commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
	}

	private void flushUniforms() {
		long dirty = this.dirtyUniforms;
		MetalRenderPipeline pipeline = this.pipeline;
		if (dirty == 0L || pipeline == null) {
			return;
		}
		List<BindGroupLayout.UniformDescription> descriptions = pipeline.uniforms();
		int[] stageMasks = pipeline.stageMasks();
		MemorySegment entries = this.bindEntries;
		int count = 0;
		for (int i = 0; i < descriptions.size(); i++) {
			if ((dirty & (1L << i)) == 0L || stageMasks[i] == 0) {
				continue;
			}
			BindGroupLayout.UniformDescription description = descriptions.get(i);
			Object value = this.uniforms[i];
			if (value == null) {
				throw new IllegalStateException("Missing uniform " + description.name() + " (should be " + description.type() + ")");
			}
			long base = (long) count * ENTRY_LONGS;
			long kind;
			long handle;
			long extra;
			switch (description.type()) {
				case UNIFORM_BUFFER -> {
					GpuBufferSlice slice = (GpuBufferSlice) value;
					kind = Native.BIND_BUFFER;
					handle = ((MetalBuffer) slice.buffer()).handle();
					extra = slice.offset();
				}
				case COMBINED_IMAGE_SAMPLER -> {
					TextureViewAndSampler textureAndSampler = (TextureViewAndSampler) value;
					kind = Native.BIND_TEXTURE_SAMPLER;
					handle = ((MetalTextureView) textureAndSampler.view()).handle();
					extra = ((MetalSampler) textureAndSampler.sampler()).handle();
				}
				case TEXEL_BUFFER -> {
					GpuBufferSlice slice = (GpuBufferSlice) value;
					kind = Native.BIND_TEXTURE;
					handle = ((MetalBuffer.Direct) slice.buffer()).texelTexture(description.gpuFormat());
					extra = 0L;
				}
				default -> throw new IllegalStateException("Unexpected uniform type " + description.type());
			}
			entries.setAtIndex(ValueLayout.JAVA_LONG, base, kind | ((long) stageMasks[i] << 8));
			entries.setAtIndex(ValueLayout.JAVA_LONG, base + 1, i);
			entries.setAtIndex(ValueLayout.JAVA_LONG, base + 2, handle);
			entries.setAtIndex(ValueLayout.JAVA_LONG, base + 3, extra);
			count++;
		}
		this.dirtyUniforms = 0L;
		if (count > 0) {
			Native.passBind(this.context, entries.address(), count);
		}
	}
}
