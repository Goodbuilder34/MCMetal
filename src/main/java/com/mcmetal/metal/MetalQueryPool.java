package com.mcmetal.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

/**
 * Apple GPUs only sample timestamps at encoder boundaries, which doesn't fit the per-command query model,
 * so timestamp queries report "unavailable" and the game falls back to CPU timing.
 */
public class MetalQueryPool implements GpuQueryPool {
	private final int size;

	MetalQueryPool(final int size) {
		this.size = size;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public OptionalLong getValue(final int index) {
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(final int index, final int count) {
		if (index + count > this.size) {
			throw new IndexOutOfBoundsException("getValues would read out-of-bounds for " + count + " starting at " + index + " of " + this.size);
		}
		OptionalLong[] result = new OptionalLong[count];
		Arrays.fill(result, OptionalLong.empty());
		return result;
	}

	@Override
	public void close() {
	}
}
