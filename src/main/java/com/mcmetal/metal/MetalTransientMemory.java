package com.mcmetal.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit bump allocator. On unified memory "staging", "GPU" and "GPU mapped" memory are all the same
 * shared MTLBuffer blocks, so uploads are a single memcpy with no copy pass. Blocks return to the free list
 * once the submit that used them has completed on the GPU.
 */
public class MetalTransientMemory implements TransientMemory {
	private static final long BLOCK_SIZE = 1L << 20;
	private static final long MAX_CPU_ALIGNMENT = 16L;
	private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);

	private final MetalCommandEncoder encoder;
	private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpuAllocator = new TransientBlockAllocator<>(
		BLOCK_SIZE, MAX_CPU_ALIGNMENT, TransientBlockAllocator.Allocator.CpuBlock.memalloc()
	);
	private final TransientBlockAllocator<Block> gpuAllocator;
	private long submitIndex;

	MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
		this.encoder = encoder;
		this.gpuAllocator = new TransientBlockAllocator<>(
			BLOCK_SIZE,
			MAX_GPU_ALIGNMENT,
			TransientBlockAllocator.Allocator.create(size -> Block.allocate(device, size), block -> Native.release(block.handle()))
		);
	}

	long submitIndex() {
		return this.submitIndex;
	}

	void endSubmit() {
		this.cpuAllocator.rotate().run();
		// Blocks used this submit go back on the free list only after the GPU is done with them.
		Runnable recycle = this.gpuAllocator.rotate();
		this.encoder.queueForDestroy(recycle);
		this.submitIndex++;
	}

	void destroy() {
		this.cpuAllocator.close();
		this.gpuAllocator.close();
	}

	private GpuBufferSlice.MappedView allocateShared(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		TransientBlockAllocator.Allocation<Block> alloc = this.gpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
		Block block = alloc.block();
		MetalBuffer.Transient buffer = new MetalBuffer.Transient(this, block.handle(), block.contents(), usage, block.size(), this.submitIndex);
		ByteBuffer cpu = MemoryUtil.memByteBuffer(block.contents() + alloc.offset(), (int) alloc.size());
		return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, alloc.offset(), alloc.size()), cpu, () -> {});
	}

	@Override
	public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
		TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> alloc = this.cpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
		return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int) alloc.size());
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice allocateGpu(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize).slice();
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(final long size, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		return this.upload(data, alignment, usage, minimumAllocation, elementSize);
	}

	private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage, final long minimumAllocation, final long elementSize) {
		long totalSize = 0L;
		for (ByteBuffer buffer : data) {
			totalSize = Mth.roundToward(totalSize + buffer.remaining(), alignment);
		}
		GpuBufferSlice.MappedView mapped = this.allocateShared(totalSize, alignment, usage, minimumAllocation, elementSize);
		long target = MemoryUtil.memAddress(mapped.data());
		long length = mapped.slice().length();
		long offset = 0L;
		for (ByteBuffer buffer : data) {
			MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), target + offset, Math.min(length - offset, buffer.remaining()));
			offset = Mth.roundToward(offset + buffer.remaining(), alignment);
			if (offset >= length) {
				break;
			}
		}
		return mapped.slice();
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUpload(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		return this.multiUpload(data, alignment, usage);
	}

	/** Packs the largest uploads that still fit into the current block first, like the Vulkan backend. */
	private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, final @GpuBuffer.Usage int usage) {
		ReferenceArrayList<GpuBufferSlice> result = new ReferenceArrayList<>();
		result.size(data.size());
		IntArrayList pending = IntArrayList.toList(IntStream.range(0, data.size()));
		pending.sort(IntComparator.comparing(index -> data.get(index).remaining()));
		while (!pending.isEmpty()) {
			int chosen = -1;
			for (int i = pending.size() - 1; i >= 0; i--) {
				if (this.gpuAllocator.canAllocateInCurrentBlock(data.get(pending.getInt(i)).remaining(), alignment)) {
					chosen = pending.removeInt(i);
					break;
				}
			}
			if (chosen == -1) {
				chosen = pending.popInt();
			}
			ByteBuffer source = data.get(chosen);
			GpuBufferSlice.MappedView view = this.allocateShared(source.remaining(), alignment, usage, source.remaining(), 1L);
			MemoryUtil.memCopy(source, view.data());
			result.set(chosen, view.slice());
		}
		return result;
	}

	record Block(long handle, long contents, long size) implements TransientBlockAllocator.Allocator.Block {
		static Block allocate(final MetalDevice device, final long size) {
			long handle = Native.bufferCreate(device.context(), size);
			if (handle == 0L) {
				throw new OutOfMemoryError("Failed to allocate transient Metal block of " + size + " bytes");
			}
			if (device.labels()) {
				Native.setLabel(handle, "MCMetal transient block");
			}
			return new Block(handle, Native.bufferContents(handle), size);
		}

		@Override
		public boolean suboptimal() {
			return false;
		}
	}
}
