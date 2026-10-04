package com.mcmetal.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public class MetalSampler implements GpuSampler {
	private final MetalDevice device;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	private final long handle;
	private boolean closed;

	MetalSampler(
		final MetalDevice device,
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
		this.device = device;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod;
		// Same mip policy as the Vulkan backend: a max LOD of ~0 means "no mip blending".
		double lod = maxLod.orElse(1000.0);
		this.handle = Native.samplerCreate(
			device.context(),
			addressModeU == AddressMode.REPEAT,
			addressModeV == AddressMode.REPEAT,
			minFilter == FilterMode.LINEAR,
			magFilter == FilterMode.LINEAR,
			lod > 0.25 ? 2 : 1,
			maxAnisotropy,
			(float) Math.max(0.25, lod)
		);
	}

	long handle() {
		return this.handle;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
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
			this.device.createCommandEncoder().queueForDestroy(() -> Native.release(handle));
		}
	}
}
