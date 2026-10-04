package com.mcmetal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

public class MetalTexture extends BaseGpuTexture {
	private final MetalDevice device;
	private final long handle;
	private boolean closed;
	private int references = 1;

	MetalTexture(
		final MetalDevice device,
		final @GpuTexture.Usage int usage,
		final String label,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.device = device;
		boolean cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		this.handle = Native.textureCreate(device.context(), MetalConst.format(format), width, height, cubemap ? 1 : depthOrLayers, mipLevels, cubemap);
		if (this.handle == 0L) {
			throw new IllegalStateException("Failed to create Metal texture " + label + " (" + format + " " + width + "x" + height + ")");
		}
		if (device.labels() && !label.isEmpty()) {
			Native.setLabel(this.handle, label);
		}
	}

	long handle() {
		return this.handle;
	}

	boolean isCubemap() {
		return (this.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
	}

	void retainForView() {
		this.references++;
	}

	void releaseFromView() {
		if (--this.references == 0) {
			long handle = this.handle;
			this.device.createCommandEncoder().queueForDestroy(() -> Native.release(handle));
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.releaseFromView();
		}
	}
}
