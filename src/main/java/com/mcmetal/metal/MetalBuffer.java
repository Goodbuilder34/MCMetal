package com.mcmetal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** All buffers live in shared (unified) memory; {@link #contents()} is a stable CPU pointer for the buffer's lifetime. */
public abstract class MetalBuffer extends BaseGpuBuffer {
	static final int UNIFORM_OFFSET_ALIGNMENT = 16;
	// Texel buffers are viewed as linear textures whose rows need this alignment.
	private static final long TEXEL_BUFFER_PADDING = 256L;

	protected MetalBuffer(final @GpuBuffer.Usage int usage, final long size) {
		super(usage, size);
	}

	abstract long handle();

	abstract long contents();

	public static class Direct extends MetalBuffer {
		private final MetalDevice device;
		private final long handle;
		private final long contents;
		private boolean closed;
		private int mappings;
		private @Nullable GpuFormat texelFormat;
		private long texelTexture;

		Direct(final MetalDevice device, final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
			super(usage, size);
			this.device = device;
			// MSL rounds trailing vec3 members up to 16 bytes, so a std140 block can be 12 bytes on the CPU side
			// but 16 in the shader; padding keeps the shader's view inside the allocation.
			long padding = (usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0 ? TEXEL_BUFFER_PADDING : UNIFORM_OFFSET_ALIGNMENT;
			long allocation = (size + padding - 1) / padding * padding;
			this.handle = Native.bufferCreate(device.context(), allocation);
			if (this.handle == 0L) {
				throw new OutOfMemoryError("Failed to allocate Metal buffer of " + size + " bytes");
			}
			this.contents = Native.bufferContents(this.handle);
			if (label != null && device.labels()) {
				Native.setLabel(this.handle, label.get());
			}
		}

		@Override
		long handle() {
			return this.handle;
		}

		@Override
		long contents() {
			return this.contents;
		}

		/** Linear texture view used to bind this buffer as a {@code samplerBuffer}; cached per format. */
		long texelTexture(final GpuFormat format) {
			if (this.texelTexture == 0L || this.texelFormat != format) {
				if (this.texelTexture != 0L) {
					long previous = this.texelTexture;
					this.device.createCommandEncoder().queueForDestroy(() -> Native.release(previous));
				}
				int width = (int) (this.size() / format.blockSize());
				this.texelTexture = Native.textureBufferCreate(this.device.context(), this.handle, MetalConst.format(format), width, width * format.blockSize());
				this.texelFormat = format;
				if (this.texelTexture == 0L) {
					throw new IllegalStateException("Couldn't create texel buffer view (" + format + ") for buffer of " + this.size() + " bytes");
				}
			}
			return this.texelTexture;
		}

		@Override
		public boolean isClosed() {
			return this.closed;
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				if (this.mappings != 0) {
					throw new IllegalStateException("Attempt to close a mapped buffer");
				}
				long handle = this.handle;
				long texel = this.texelTexture;
				this.device.createCommandEncoder().queueForDestroy(() -> {
					Native.release(texel);
					Native.release(handle);
				});
			}
		}

		@Override
		public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
			if (this.closed) {
				throw new IllegalStateException("Buffer already closed");
			}
			if (!read && !write) {
				throw new IllegalArgumentException("At least read or write must be true");
			}
			if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
				throw new IllegalStateException("Buffer is not readable");
			}
			if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
				throw new IllegalStateException("Buffer is not writable");
			}
			if (offset < 0L || length < 0L || offset + length > this.size()) {
				throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " from " + this.size() + " size buffer");
			}
			if (length > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
			}
			this.mappings++;
			return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.contents + offset, (int) length), new Runnable() {
				private boolean done;

				@Override
				public void run() {
					if (!this.done) {
						this.done = true;
						Direct.this.mappings--;
					}
				}
			});
		}
	}

	/** A sub-range of a per-submit transient block. Becomes closed once its submit has been sent. */
	static final class Transient extends MetalBuffer {
		private final MetalTransientMemory owner;
		private final long handle;
		private final long contents;
		private final long submitIndex;
		private boolean closed;

		Transient(final MetalTransientMemory owner, final long handle, final long contents, final @GpuBuffer.Usage int usage, final long size, final long submitIndex) {
			super(usage, size);
			this.owner = owner;
			this.handle = handle;
			this.contents = contents;
			this.submitIndex = submitIndex;
		}

		@Override
		long handle() {
			return this.handle;
		}

		@Override
		long contents() {
			return this.contents;
		}

		@Override
		public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
			throw new IllegalStateException("Cannot map transient buffer");
		}

		@Override
		public boolean isClosed() {
			if (!this.closed) {
				this.closed = this.submitIndex < this.owner.submitIndex();
			}
			return this.closed;
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@Override
		public GpuBufferSlice slice(final long offset, final long length) {
			throw new IllegalStateException("Cannot slice transient buffer");
		}

		@Override
		public GpuBufferSlice slice() {
			throw new IllegalStateException("Cannot slice transient buffer");
		}
	}
}
