// Device creation, internal shaders and generic object helpers.

#include "internal.h"

#define MCM_ABI_VERSION 3

static const char *INTERNAL_SHADERS = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct FullscreenOut { float4 position [[position]]; };

static float4 fullscreen_position(uint vid, float z) {
	float2 uv = float2((vid << 1) & 2, vid & 2);
	return float4(uv * 2.0 - 1.0, z, 1.0);
}

vertex FullscreenOut mcm_clear_vs(uint vid [[vertex_id]], constant float &depth [[buffer(0)]]) {
	FullscreenOut out;
	out.position = fullscreen_position(vid, depth);
	return out;
}

fragment float4 mcm_clear_fs(constant float4 &color [[buffer(0)]]) {
	return color;
}

vertex FullscreenOut mcm_present_vs(uint vid [[vertex_id]]) {
	FullscreenOut out;
	out.position = fullscreen_position(vid, 0.0);
	return out;
}

// The game renders with Vulkan's convention (row 0 = NDC y -1), so the image is flipped on present.
fragment float4 mcm_present_fs(FullscreenOut in [[stage_in]], texture2d<float, access::read> source [[texture(0)]], constant uint2 &size [[buffer(0)]]) {
	uint2 p = uint2(in.position.xy);
	return float4(source.read(uint2(p.x, size.y - 1 - p.y)).rgb, 1.0);
}
)METAL";

id<MTLRenderPipelineState> make_internal_pipeline(McmContext *ctx, NSString *vs, NSString *fs, MTLPixelFormat color, MTLPixelFormat depth) {
	MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
	desc.vertexFunction = [ctx->internalLibrary newFunctionWithName:vs];
	desc.fragmentFunction = [ctx->internalLibrary newFunctionWithName:fs];
	desc.colorAttachments[0].pixelFormat = color;
	desc.depthAttachmentPixelFormat = depth;
	if (has_stencil(depth)) desc.stencilAttachmentPixelFormat = depth;
	NSError *error = nil;
	id<MTLRenderPipelineState> state = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
	if (!state) mcm_log("internal pipeline %s failed: %s", vs.UTF8String, error.localizedDescription.UTF8String);
	return state;
}

extern "C" int32_t mcm_abi_version(void) {
	return MCM_ABI_VERSION;
}

extern "C" void mcm_release(void *object) {
	if (object) CFRelease(object);
}

extern "C" void mcm_set_label(void *resource, const char *label) {
	if (!resource || !label) return;
	id object = (__bridge id)resource;
	if ([object respondsToSelector:@selector(setLabel:)]) {
		[object setLabel:[NSString stringWithUTF8String:label]];
	}
}

extern "C" void *mcm_device_create(char *name_out, int32_t name_len, int64_t *info_out) {
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		if (!device) return nullptr;

		McmContext *ctx = new McmContext();
		ctx->device = device;
		ctx->queue = [device newCommandQueueWithMaxCommandBufferCount:16];
		ctx->queue.label = @"MCMetal";
		ctx->event = [device newSharedEvent];
		ctx->presentQueue = [device newCommandQueue];
		ctx->presentQueue.label = @"MCMetal present";
		ctx->retired = [NSMutableArray new];
		ctx->clearPipelines = [NSMutableDictionary new];
		ctx->inPassClearPipelines = [NSMutableDictionary new];

		MTLDepthStencilDescriptor *disabled = [MTLDepthStencilDescriptor new];
		disabled.depthCompareFunction = MTLCompareFunctionAlways;
		disabled.depthWriteEnabled = NO;
		ctx->depthDisabled = [device newDepthStencilStateWithDescriptor:disabled];

		MTLDepthStencilDescriptor *clear = [MTLDepthStencilDescriptor new];
		clear.depthCompareFunction = MTLCompareFunctionAlways;
		clear.depthWriteEnabled = YES;
		ctx->clearDepthState = [device newDepthStencilStateWithDescriptor:clear];

		NSError *error = nil;
		ctx->internalLibrary = [device newLibraryWithSource:[NSString stringWithUTF8String:INTERNAL_SHADERS] options:nil error:&error];
		if (!ctx->internalLibrary) {
			mcm_log("internal shader library failed: %s", error.localizedDescription.UTF8String);
			delete ctx;
			return nullptr;
		}
		ctx->presentPipeline = make_internal_pipeline(ctx, @"mcm_present_vs", @"mcm_present_fs", MTLPixelFormatBGRA8Unorm, MTLPixelFormatInvalid);

		if (name_out && name_len > 0) {
			strncpy(name_out, device.name.UTF8String, (size_t)name_len - 1);
			name_out[name_len - 1] = 0;
		}
		if (info_out) {
			int family = 0;
			MTLGPUFamily families[] = {MTLGPUFamilyApple9, MTLGPUFamilyApple8, MTLGPUFamilyApple7};
			int numbers[] = {9, 8, 7};
			for (int i = 0; i < 3; i++) {
				if ([device supportsFamily:families[i]]) {
					family = numbers[i];
					break;
				}
			}
			info_out[MCM_INFO_MAX_BUFFER_LENGTH] = (int64_t)device.maxBufferLength;
			info_out[MCM_INFO_WORKING_SET_SIZE] = (int64_t)device.recommendedMaxWorkingSetSize;
			info_out[MCM_INFO_GPU_FAMILY] = family;
			info_out[MCM_INFO_UNIFIED_MEMORY] = device.hasUnifiedMemory ? 1 : 0;
		}
		return ctx;
	}
}

extern "C" void mcm_device_destroy(void *handle) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx) return;
	end_blit(ctx);
	end_render(ctx);
	commit_uploads(ctx);
	if (ctx->commandBuffer) {
		[ctx->commandBuffer commit];
		[ctx->commandBuffer waitUntilCompleted];
		ctx->commandBuffer = nil;
	}
	delete ctx;
}
