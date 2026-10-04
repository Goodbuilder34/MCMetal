// Buffers, textures and samplers.

#include "internal.h"

extern "C" void *mcm_buffer_create(void *handle, int64_t length) {
	McmContext *ctx = (McmContext *)handle;
	// Apple Silicon has unified memory: shared storage lets the CPU write straight into GPU-visible pages.
	id<MTLBuffer> buffer = [ctx->device newBufferWithLength:(NSUInteger)std::max<int64_t>(length, 4)
	                                                options:MTLResourceStorageModeShared | MTLResourceHazardTrackingModeTracked];
	return buffer ? (__bridge_retained void *)buffer : nullptr;
}

extern "C" void *mcm_buffer_contents(void *buffer) {
	return [(__bridge id<MTLBuffer>)buffer contents];
}

extern "C" void *mcm_texture_create(void *handle, int32_t format, int32_t width, int32_t height, int32_t layers, int32_t mips, int32_t cubemap) {
	McmContext *ctx = (McmContext *)handle;
	MTLPixelFormat pixelFormat = pixel_format(format);
	if (pixelFormat == MTLPixelFormatInvalid) {
		mcm_log("unsupported texture format code %d", format);
		return nullptr;
	}
	MTLTextureDescriptor *desc = [MTLTextureDescriptor new];
	desc.pixelFormat = pixelFormat;
	desc.width = (NSUInteger)std::max(width, 1);
	desc.height = (NSUInteger)std::max(height, 1);
	desc.mipmapLevelCount = (NSUInteger)std::max(mips, 1);
	if (cubemap) {
		desc.textureType = MTLTextureTypeCube;
	} else if (layers > 1) {
		desc.textureType = MTLTextureType2DArray;
		desc.arrayLength = (NSUInteger)layers;
	} else {
		desc.textureType = MTLTextureType2D;
	}
	// Private storage lets the GPU use lossless framebuffer/texture compression.
	desc.storageMode = MTLStorageModePrivate;
	desc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
	id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:desc];
	return texture ? (__bridge_retained void *)texture : nullptr;
}

extern "C" void *mcm_texture_view_create(void *handle, int32_t format, int32_t base_mip, int32_t mips, int32_t cubemap) {
	(void)format;
	id<MTLTexture> texture = (__bridge id<MTLTexture>)handle;
	MTLTextureType type = cubemap ? MTLTextureTypeCube : MTLTextureType2D;
	NSUInteger slices = cubemap ? 6 : 1;
	id<MTLTexture> view = [texture newTextureViewWithPixelFormat:texture.pixelFormat
	                                                 textureType:type
	                                                      levels:NSMakeRange((NSUInteger)base_mip, (NSUInteger)mips)
	                                                      slices:NSMakeRange(0, slices)];
	return view ? (__bridge_retained void *)view : nullptr;
}

extern "C" void *mcm_texture_buffer_create(void *handle, void *buffer_handle, int32_t format, int32_t width, int32_t bytes_per_row) {
	McmContext *ctx = (McmContext *)handle;
	id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)buffer_handle;
	MTLPixelFormat pixelFormat = pixel_format(format);
	NSUInteger alignment = [ctx->device minimumTextureBufferAlignmentForPixelFormat:pixelFormat];
	NSUInteger rowBytes = ((NSUInteger)bytes_per_row + alignment - 1) / alignment * alignment;
	if (rowBytes > buffer.length) {
		mcm_log("texel buffer of %lu bytes too small for aligned row of %lu bytes", (unsigned long)buffer.length, (unsigned long)rowBytes);
		return nullptr;
	}
	MTLTextureDescriptor *desc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pixelFormat
	                                                                                     width:(NSUInteger)width
	                                                                           resourceOptions:buffer.resourceOptions
	                                                                                     usage:MTLTextureUsageShaderRead];
	id<MTLTexture> texture = [buffer newTextureWithDescriptor:desc offset:0 bytesPerRow:rowBytes];
	return texture ? (__bridge_retained void *)texture : nullptr;
}

extern "C" void *mcm_sampler_create(void *handle, int32_t repeat_u, int32_t repeat_v, int32_t linear_min, int32_t linear_mag, int32_t mip_mode, int32_t max_anisotropy, float max_lod) {
	McmContext *ctx = (McmContext *)handle;
	MTLSamplerDescriptor *desc = [MTLSamplerDescriptor new];
	desc.sAddressMode = repeat_u ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
	desc.tAddressMode = repeat_v ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
	desc.rAddressMode = MTLSamplerAddressModeClampToEdge;
	desc.minFilter = linear_min ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
	desc.magFilter = linear_mag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
	desc.mipFilter = mip_mode == 2 ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
	desc.maxAnisotropy = (NSUInteger)std::clamp(max_anisotropy, 1, 16);
	desc.lodMinClamp = 0.0f;
	desc.lodMaxClamp = max_lod;
	id<MTLSamplerState> sampler = [ctx->device newSamplerStateWithDescriptor:desc];
	return sampler ? (__bridge_retained void *)sampler : nullptr;
}
