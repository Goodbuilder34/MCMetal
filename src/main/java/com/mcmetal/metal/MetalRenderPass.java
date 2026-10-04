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
	// Shaderpack redirection: pipelines are swapped for the pack's; draws of pipelines without one are skipped.
	private boolean redirected;
	private boolean skipDraws;
	private @Nullable MetalRenderPipeline requested;
	private final long[] vertexBuffers = new long[8];
	private final long[] vertexOffsets = new long[8];
	private long indexBuffer;
	private int indexSize;
	private final MetalCommandEncoder encoder;
	private boolean scissor;
	private final int[] scissorRect = new int[4];

	MetalRenderPass(final MetalDevice device, final Arena arena, final MetalCommandEncoder encoder) {
		this.device = device;
		this.encoder = encoder;
		this.context = device.context();
		this.bindEntries = arena.allocate(ValueLayout.JAVA_LONG, (long) Native.MAX_UNIFORMS * ENTRY_LONGS);
	}

	void begin(final RenderPass.RenderArea area, final boolean redirected) {
		this.redirected = redirected;
		this.skipDraws = false;
		this.requested = null;
		java.util.Arrays.fill(this.vertexBuffers, 0L);
		this.indexBuffer = 0L;
		this.scissor = false;
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
		this.requested = metalPipeline;
		Arrays.fill(this.uniforms, null);
		this.applyPipeline(metalPipeline);
	}

	private void applyPipeline(final MetalRenderPipeline requested) {
		MetalRenderPipeline pipeline = requested;
		this.skipDraws = false;
		PackHooks hooks = this.redirected ? PackHooks.current() : null;
		if (hooks != null) {
			pipeline = hooks.substitute(requested);
			if (pipeline == null) {
				this.skipDraws = true;
				this.pipeline = null;
				return;
			}
		}
		if (hooks != null) {
			long[] attachments = hooks.attachments(pipeline);
			if (attachments != null && this.encoder.switchAttachments(attachments)) {
				this.restoreState();
			}
		}
		this.pipeline = pipeline;
		Native.passSetPipeline(this.context, pipeline.handle());
		this.bindVertexPull();
		int count = pipeline.uniforms().size();
		this.dirtyUniforms = count >= 64 ? -1L : (1L << count) - 1L;
		if (hooks != null) {
			hooks.bindPackResources(pipeline);
		}
	}

	/** After the native pass was suspended and begun again: restore pipeline, buffers and bindings. */
	void resume() {
		if (this.requested != null) {
			this.applyPipeline(this.requested);
		}
		this.restoreState();
	}

	/** Re-sets buffers and scissor on a newly begun native pass. */
	private void restoreState() {
		if (this.scissor) {
			Native.passSetScissor(this.context, this.scissorRect[0], this.scissorRect[1], this.scissorRect[2], this.scissorRect[3]);
		}
		for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
			if (this.vertexBuffers[slot] != 0L) {
				Native.passSetVertexBuffer(this.context, slot, this.vertexBuffers[slot], this.vertexOffsets[slot]);
			}
		}
		if (this.indexBuffer != 0L) {
			Native.passSetIndexBuffer(this.context, this.indexBuffer, this.indexSize);
		}
	}

	/** Pack pipelines that read their vertices directly get vertex buffer 0 as a plain buffer too. */
	private void bindVertexPull() {
		MetalRenderPipeline pipeline = this.pipeline;
		if (pipeline == null || pipeline.vertexPullSlot() < 0 || this.vertexBuffers[0] == 0L) {
			return;
		}
		this.bindEntries.setAtIndex(ValueLayout.JAVA_LONG, 0, Native.BIND_BUFFER | (1L << 8));
		this.bindEntries.setAtIndex(ValueLayout.JAVA_LONG, 1, pipeline.vertexPullSlot());
		this.bindEntries.setAtIndex(ValueLayout.JAVA_LONG, 2, this.vertexBuffers[0]);
		this.bindEntries.setAtIndex(ValueLayout.JAVA_LONG, 3, this.vertexOffsets[0]);
		this.bindEntries.setAtIndex(ValueLayout.JAVA_LONG, 4, 0L);
		Native.passBind(this.context, this.bindEntries.address(), 1);
	}

	/** Re-binds the pack's resources of the current pipeline (e.g. after per-draw uniforms changed). */
	public void rebindPack() {
		PackHooks hooks = PackHooks.current();
		if (hooks != null && this.pipeline != null && this.redirected) {
			hooks.bindPackResources(this.pipeline);
		}
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
		this.scissor = true;
		this.scissorRect[0] = x;
		this.scissorRect[1] = y;
		this.scissorRect[2] = width;
		this.scissorRect[3] = height;
		Native.passSetScissor(this.context, x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.scissor = false;
		Native.passSetScissor(this.context, this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer != null) {
			long handle = ((MetalBuffer) vertexBuffer.buffer()).handle();
			Native.passSetVertexBuffer(this.context, slot, handle, vertexBuffer.offset());
			if (slot < this.vertexBuffers.length) {
				this.vertexBuffers[slot] = handle;
				this.vertexOffsets[slot] = vertexBuffer.offset();
			}
			if (slot == 0) {
				this.bindVertexPull();
			}
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBuffer = ((MetalBuffer) indexBuffer).handle();
		this.indexSize = indexType.bytes;
		Native.passSetIndexBuffer(this.context, this.indexBuffer, this.indexSize);
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		if (this.skipDraws) {
			return;
		}
		this.flushUniforms();
		Native.passDrawIndexed(this.context, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (this.skipDraws) {
			return;
		}
		this.flushUniforms();
		Native.passMultiDrawIndexed(this.context, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (this.skipDraws) {
			return;
		}
		this.flushUniforms();
		Native.passDrawIndexedIndirect(this.context, ((MetalBuffer) commands.buffer()).handle(), commands.offset(), drawCount);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (this.skipDraws) {
			return;
		}
		this.flushUniforms();
		Native.passDraw(this.context, vertexCount, instanceCount, firstVertex, firstInstance);
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (this.skipDraws) {
			return;
		}
		this.flushUniforms();
		Native.passMultiDraw(this.context, MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (this.skipDraws) {
			return;
		}
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
