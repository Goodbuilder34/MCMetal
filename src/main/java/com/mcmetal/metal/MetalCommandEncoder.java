package com.mcmetal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.DestructionQueue;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Records everything into one MTLCommandBuffer per submit (one per frame). Up to two submits are in flight;
 * resources released by the game are destroyed only after the GPU has finished every submit that could use them.
 */
public class MetalCommandEncoder implements CommandEncoderBackend {
	private static final int MAX_SUBMITS_IN_FLIGHT = 2;
	private static final long SUBMIT_TIMEOUT_NS = 5_000_000_000L;
	private static final int MAX_COLOR_ATTACHMENTS = 8;

	private final MetalDevice device;
	private final long context;
	private final MetalTransientMemory transientMemory;
	private final DestructionQueue<Runnable> destroyQueue = new DestructionQueue<>(MAX_SUBMITS_IN_FLIGHT, Runnable::run);
	// Scratch memory for begin-pass arguments; reused every pass so the hot path never allocates.
	private final Arena arena = Arena.ofShared();
	private final MemorySegment passTextures = this.arena.allocate(ValueLayout.JAVA_LONG, MAX_COLOR_ATTACHMENTS);
	private final MemorySegment passClearFlags = this.arena.allocate(ValueLayout.JAVA_INT, MAX_COLOR_ATTACHMENTS);
	private final MemorySegment passClearColors = this.arena.allocate(ValueLayout.JAVA_FLOAT, MAX_COLOR_ATTACHMENTS * 4L);
	private final MetalRenderPass renderPass;
	private long submitted;
	private boolean inRenderPass;
	// Arguments of the open pass, for resuming it after a suspension (see suspendRenderPass).
	private int passCount;
	private long passDepth;
	private RenderPass.RenderArea passArea = new RenderPass.RenderArea(0, 0, 0, 0);

	MetalCommandEncoder(final MetalDevice device) {
		this.device = device;
		this.context = device.context();
		this.transientMemory = new MetalTransientMemory(device, this);
		this.renderPass = new MetalRenderPass(device, this.arena, this);
	}

	void destroy() {
		this.transientMemory.endSubmit();
		long last = Native.submit(this.context);
		Native.await(this.context, last, -1L);
		this.destroyQueue.close();
		this.transientMemory.destroy();
		this.destroyQueue.close();
		this.arena.close();
	}

	public void queueForDestroy(final Runnable destroy) {
		this.destroyQueue.add(destroy);
	}

	@Override
	public void submit() {
		if (this.inRenderPass) {
			throw new IllegalStateException("Cannot submit while inside a render pass");
		}
		this.transientMemory.endSubmit();
		this.submitted = Native.submit(this.context);
		long mustBeComplete = this.submitted - (MAX_SUBMITS_IN_FLIGHT - 1);
		if (mustBeComplete > 0 && !Native.await(this.context, mustBeComplete, SUBMIT_TIMEOUT_NS)) {
			throw new IllegalStateException("5s timeout reached waiting for Metal submit " + mustBeComplete + " (GPU hang?)");
		}
		this.destroyQueue.rotate();
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		int count = Math.min(colors.size(), MAX_COLOR_ATTACHMENTS);
		PackHooks hooks = PackHooks.current();
		if (hooks != null) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> first = count > 0 ? colors.get(0) : null;
			RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
			MetalTexture color = first != null ? ((MetalTextureView) first.textureView()).texture() : null;
			MetalTexture depthTexture = depthAttachment != null ? ((MetalTextureView) depthAttachment.textureView()).texture() : null;
			PackHooks.Redirect redirect = hooks.redirect(color, depthTexture);
			if (redirect != null) {
				int n = Math.min(redirect.colors().length, MAX_COLOR_ATTACHMENTS);
				for (int i = 0; i < n; i++) {
					this.passTextures.setAtIndex(ValueLayout.JAVA_LONG, i, redirect.colors()[i]);
					this.passClearFlags.setAtIndex(ValueLayout.JAVA_INT, i, 0);
				}
				// The game's depth clears use its reversed convention (0 = far); pack depth is OpenGL's (1 = far).
				boolean clear = depthAttachment != null && depthAttachment.clearValue().isPresent();
				RenderPass.RenderArea area = redirect.width() > 0 ? new RenderPass.RenderArea(0, 0, redirect.width(), redirect.height()) : descriptor.renderArea();
				this.beginNative(n, redirect.depth(), clear && redirect.depth() != 0L, 1.0, area, descriptor);
				this.inRenderPass = true;
				this.renderPass.begin(area, true);
				return this.renderPass;
			}
		}
		for (int i = 0; i < count; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
			if (attachment == null) {
				this.passTextures.setAtIndex(ValueLayout.JAVA_LONG, i, 0L);
				this.passClearFlags.setAtIndex(ValueLayout.JAVA_INT, i, 0);
				continue;
			}
			this.passTextures.setAtIndex(ValueLayout.JAVA_LONG, i, ((MetalTextureView) attachment.textureView()).handle());
			Optional<Vector4fc> clear = attachment.clearValue();
			this.passClearFlags.setAtIndex(ValueLayout.JAVA_INT, i, clear.isPresent() ? 1 : 0);
			if (clear.isPresent()) {
				Vector4fc color = clear.get();
				this.passClearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L, color.x());
				this.passClearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 1, color.y());
				this.passClearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 2, color.z());
				this.passClearColors.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 3, color.w());
			}
		}

		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		long depthHandle = depth != null ? ((MetalTextureView) depth.textureView()).handle() : 0L;
		boolean clearDepth = depth != null && depth.clearValue().isPresent();
		double depthValue = clearDepth ? depth.clearValue().getAsDouble() : 0.0;
		RenderPass.RenderArea area = descriptor.renderArea();

		this.beginNative(count, depthHandle, clearDepth, depthValue, area, descriptor);
		this.inRenderPass = true;
		this.renderPass.begin(area, false);
		return this.renderPass;
	}

	private void beginNative(final int count, final long depthHandle, final boolean clearDepth, final double depthValue, final RenderPass.RenderArea area,
		final @Nullable RenderPassDescriptor descriptor) {
		this.passCount = count;
		this.passDepth = depthHandle;
		this.passArea = area;
		if (this.device.labels() && descriptor != null) {
			try (Arena labelArena = Arena.ofConfined()) {
				Native.beginPass(
					this.context, this.passTextures.address(), this.passClearFlags.address(), this.passClearColors.address(), count, depthHandle, clearDepth, depthValue,
					area.x(), area.y(), area.width(), area.height(), Native.cString(labelArena, descriptor.label().get())
				);
			}
		} else {
			Native.beginPass(
				this.context, this.passTextures.address(), this.passClearFlags.address(), this.passClearColors.address(), count, depthHandle, clearDepth, depthValue,
				area.x(), area.y(), area.width(), area.height(), 0L
			);
		}
	}

	/** Whether the game currently has a render pass open. */
	public boolean inRenderPass() {
		return this.inRenderPass;
	}

	/** Ends the open native pass so other work can be encoded; {@link #resumeRenderPass} continues it. */
	public void suspendRenderPass() {
		Native.passSuspend(this.context);
	}

	/** Begins the suspended pass again on the same attachments (loading their contents) and restores its state. */
	public void resumeRenderPass() {
		for (int i = 0; i < this.passCount; i++) {
			this.passClearFlags.setAtIndex(ValueLayout.JAVA_INT, i, 0);
		}
		this.beginNative(this.passCount, this.passDepth, false, 0.0, this.passArea, null);
		this.renderPass.resume();
	}

	/**
	 * Continues the open pass on other color attachments (same depth, contents loaded). Returns false when they are
	 * already the current ones.
	 */
	boolean switchAttachments(final long[] colors) {
		int n = Math.min(colors.length, MAX_COLOR_ATTACHMENTS);
		if (n == this.passCount) {
			boolean same = true;
			for (int i = 0; i < n && same; i++) {
				same = this.passTextures.getAtIndex(ValueLayout.JAVA_LONG, i) == colors[i];
			}
			if (same) {
				return false;
			}
		}
		Native.passSuspend(this.context);
		for (int i = 0; i < n; i++) {
			this.passTextures.setAtIndex(ValueLayout.JAVA_LONG, i, colors[i]);
			this.passClearFlags.setAtIndex(ValueLayout.JAVA_INT, i, 0);
		}
		this.beginNative(n, this.passDepth, false, 0.0, this.passArea, null);
		return true;
	}

	public MetalRenderPass currentRenderPass() {
		return this.renderPass;
	}

	@Override
	public void submitRenderPass() {
		if (!this.inRenderPass) {
			throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
		}
		this.renderPass.end();
		Native.endPass(this.context);
		this.inRenderPass = false;
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
		MetalTexture texture = (MetalTexture) colorTexture;
		PackHooks hooks = PackHooks.current();
		if (hooks != null && hooks.clearColor(texture, clearColor)) {
			return;
		}
		for (int mip = 0; mip < texture.getMipLevels(); mip++) {
			Native.clearTexture(this.context, texture.handle(), mip, 0, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), 0.0);
		}
	}

	@Override
	public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		this.clearColorTexture(colorTexture, clearColor);
		this.clearDepthTexture(depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final Vector4fc clearColor,
		final GpuTexture depthTexture,
		final double clearDepth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight,
		final int mipLevel
	) {
		Native.clearRegion(
			this.context, ((MetalTexture) colorTexture).handle(), ((MetalTexture) depthTexture).handle(), mipLevel, regionX, regionY, regionWidth, regionHeight,
			clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), clearDepth
		);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		MetalTexture texture = (MetalTexture) depthTexture;
		PackHooks hooks = PackHooks.current();
		if (hooks != null && hooks.clearDepth(texture, clearDepth)) {
			return;
		}
		for (int mip = 0; mip < texture.getMipLevels(); mip++) {
			Native.clearTexture(this.context, texture.handle(), mip, 0, 0.0F, 0.0F, 0.0F, 0.0F, clearDepth);
		}
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
		// Ordered with the surrounding GPU work, so it can't be a direct write into the (possibly in-flight) buffer.
		GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 1L, GpuBuffer.USAGE_COPY_SRC);
		Native.blitCopyBuffer(
			this.context, ((MetalBuffer) staging.buffer()).handle(), staging.offset(), ((MetalBuffer) destination.buffer()).handle(), destination.offset(), data.remaining()
		);
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		Native.blitCopyBuffer(
			this.context, ((MetalBuffer) source.buffer()).handle(), source.offset(), ((MetalBuffer) target.buffer()).handle(), target.offset(), source.length()
		);
	}

	@Override
	public void writeToTexture(
		final GpuTexture destination,
		final ByteBuffer source,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height
	) {
		int texelSize = destination.getFormat().blockSize();
		GpuBufferSlice staging = this.transientMemory.uploadStaging(source, Math.max(texelSize, 16), GpuBuffer.USAGE_COPY_SRC);
		int bytesPerRow = width * texelSize;
		Native.blitBufferToTexture(
			this.context, ((MetalBuffer) staging.buffer()).handle(), staging.offset(), bytesPerRow, bytesPerRow * height, ((MetalTexture) destination).handle(),
			depthOrLayer, mipLevel, destX, destY, width, height
		);
	}

	@Override
	public void copyBufferToTexture(
		final GpuBufferSlice source,
		final int sourceX,
		final int sourceY,
		final int sourceWidth,
		final int sourceHeight,
		final GpuTexture destination,
		final int destinationX,
		final int destinationY,
		final int copyWidth,
		final int copyHeight,
		final int mipLevel,
		final int arrayLayer
	) {
		int texelSize = destination.getFormat().blockSize();
		long skipBytes = (sourceX + (long) sourceY * sourceWidth) * texelSize;
		int bytesPerRow = sourceWidth * texelSize;
		Native.blitBufferToTexture(
			this.context, ((MetalBuffer) source.buffer()).handle(), source.offset() + skipBytes, bytesPerRow, bytesPerRow * sourceHeight,
			((MetalTexture) destination).handle(), arrayLayer, mipLevel, destinationX, destinationY, copyWidth, copyHeight
		);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(
		final GpuTexture source,
		final GpuBuffer destination,
		final long offset,
		final Runnable callback,
		final int mipLevel,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		int texelSize = texelSizeForCopy(source.getFormat());
		int bytesPerRow = width * texelSize;
		Native.blitTextureToBuffer(
			this.context, ((MetalTexture) source).handle(), 0, mipLevel, x, y, width, height, ((MetalBuffer) destination).handle(), offset, bytesPerRow,
			bytesPerRow * height
		);
		// Runs once this submit has completed on the GPU, i.e. when the data is readable.
		this.queueForDestroy(callback);
	}

	private static int texelSizeForCopy(final GpuFormat format) {
		// Depth/stencil copies only ever read the depth plane.
		return format == GpuFormat.D32_FLOAT_S8_UINT || format == GpuFormat.D24_UNORM_S8_UINT ? 4 : format.blockSize();
	}

	@Override
	public void copyTextureToTexture(
		final GpuTexture source,
		final GpuTexture destination,
		final int mipLevel,
		final int destX,
		final int destY,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height
	) {
		Native.blitTextureToTexture(
			this.context, ((MetalTexture) source).handle(), ((MetalTexture) destination).handle(), mipLevel, sourceX, sourceY, destX, destY, width, height
		);
	}

	@Override
	public GpuFence createFence() {
		long target = this.submitted + 1;
		return new GpuFence() {
			private boolean completed;

			@Override
			public boolean awaitCompletion(final long timeoutNS) {
				if (!this.completed) {
					if (target > MetalCommandEncoder.this.submitted) {
						if (timeoutNS == 0L) {
							return false;
						}
						throw new IllegalStateException("Cannot wait on a fence for the current submit");
					}
					this.completed = Native.await(MetalCommandEncoder.this.context, target, timeoutNS);
				}
				return this.completed;
			}

			@Override
			public void close() {
				this.completed = true;
			}
		};
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
	}
}
