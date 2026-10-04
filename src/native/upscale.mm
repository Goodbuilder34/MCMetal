// Spatial upscaling (MetalFX) for the shaderpack runtime's reduced render resolution.

#include "internal.h"
#import <MetalFX/MetalFX.h>

namespace {

struct Upscaler {
	id<MTLFXSpatialScaler> scaler;
	NSUInteger inWidth = 0, inHeight = 0, outWidth = 0, outHeight = 0;
	MTLPixelFormat inFormat = MTLPixelFormatInvalid, outFormat = MTLPixelFormatInvalid;
	// When the destination lacks a usage the scaler needs, it writes here and the result is copied over.
	id<MTLTexture> staging;
	bool failed = false;
};

Upscaler &upscaler() {
	static Upscaler u;
	return u;
}

}  // namespace

extern "C" int32_t mcm_upscale_supported(void *handle) {
	McmContext *ctx = (McmContext *)handle;
	return [MTLFXSpatialScalerDescriptor supportsDevice:ctx->device] ? 1 : 0;
}

extern "C" int32_t mcm_upscale(void *handle, void *source_handle, void *destination_handle) {
	McmContext *ctx = (McmContext *)handle;
	id<MTLTexture> source = (__bridge id<MTLTexture>)source_handle;
	id<MTLTexture> destination = (__bridge id<MTLTexture>)destination_handle;
	if (!source || !destination) return 0;
	end_blit(ctx);
	end_render(ctx);
	// The scaler overwrites the whole destination; a deferred clear of it would be wasted.
	McmContext::PendingClear dropped;
	take_clear(ctx, destination, &dropped);
	flush_clears(ctx);
	Upscaler &u = upscaler();
	@autoreleasepool {
		if (!u.scaler || u.inWidth != source.width || u.inHeight != source.height || u.outWidth != destination.width || u.outHeight != destination.height
		    || u.inFormat != source.pixelFormat || u.outFormat != destination.pixelFormat) {
			if (u.failed && u.inWidth == source.width && u.outWidth == destination.width) return 0;
			u = Upscaler();
			u.inWidth = source.width;
			u.inHeight = source.height;
			u.outWidth = destination.width;
			u.outHeight = destination.height;
			u.inFormat = source.pixelFormat;
			u.outFormat = destination.pixelFormat;
			MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
			desc.inputWidth = source.width;
			desc.inputHeight = source.height;
			desc.outputWidth = destination.width;
			desc.outputHeight = destination.height;
			desc.colorTextureFormat = source.pixelFormat;
			desc.outputTextureFormat = destination.pixelFormat;
			// The pack's final pass output is tonemapped, display-referred color.
			desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
			u.scaler = [desc newSpatialScalerWithDevice:ctx->device];
			if (!u.scaler) {
				mcm_log("MetalFX spatial scaler unavailable for %lux%lu -> %lux%lu", (unsigned long)source.width, (unsigned long)source.height,
				        (unsigned long)destination.width, (unsigned long)destination.height);
				u.failed = true;
				return 0;
			}
			MTLTextureUsage needed = u.scaler.outputTextureUsage;
			if ((destination.usage & needed) != needed) {
				MTLTextureDescriptor *staging = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:destination.pixelFormat width:destination.width
				                                                                                  height:destination.height mipmapped:NO];
				staging.storageMode = MTLStorageModePrivate;
				staging.usage = needed | MTLTextureUsageShaderRead;
				u.staging = [ctx->device newTextureWithDescriptor:staging];
				u.staging.label = @"Upscale output";
			}
			if ((source.usage & u.scaler.colorTextureUsage) != u.scaler.colorTextureUsage) {
				mcm_log("upscale source lacks usage %lu", (unsigned long)u.scaler.colorTextureUsage);
				u.scaler = nil;
				u.failed = true;
				return 0;
			}
		}
		u.scaler.colorTexture = source;
		u.scaler.inputContentWidth = source.width;
		u.scaler.inputContentHeight = source.height;
		u.scaler.outputTexture = u.staging ?: destination;
		[u.scaler encodeToCommandBuffer:command_buffer(ctx)];
		if (u.staging) {
			id<MTLBlitCommandEncoder> blit = blit_encoder(ctx);
			[blit copyFromTexture:u.staging toTexture:destination];
			end_blit(ctx);
		}
	}
	return 1;
}
