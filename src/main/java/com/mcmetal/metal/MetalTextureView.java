package com.mcmetal.metal;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public class MetalTextureView extends BaseGpuTextureView {
	private final MetalDevice device;
	private final long handle;
	private boolean closed;

	MetalTextureView(final MetalDevice device, final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
		super(texture, baseMipLevel, mipLevels);
		this.device = device;
		this.handle = Native.textureViewCreate(texture.handle(), MetalConst.format(texture.getFormat()), baseMipLevel, mipLevels, texture.isCubemap());
		if (this.handle == 0L) {
			throw new IllegalStateException("Failed to create Metal texture view for " + texture.getLabel());
		}
		texture.retainForView();
	}

	long handle() {
		return this.handle;
	}

	@Override
	public MetalTexture texture() {
		return (MetalTexture) super.texture();
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
			this.texture().releaseFromView();
		}
	}
}
