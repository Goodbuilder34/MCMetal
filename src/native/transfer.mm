// Blits, clears and deferred clear folding.

#include "internal.h"

extern "C" void mcm_blit_copy_buffer(void *handle, void *src, int64_t src_offset, void *dst, int64_t dst_offset, int64_t length) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] copy buffer %lld bytes", (long long)length);
	McmContext *ctx = (McmContext *)handle;
	if (length <= 0) return;
	static const bool hoist = getenv("MCMETAL_NO_UPLOAD_HOIST") == nullptr;
	if (hoist && !ctx->commandBuffer) {
		// Nothing encoded yet this frame: the frame's own command buffer will do.
	} else if (hoist && !ctx->frameBuffers.count(src) && !ctx->frameBuffers.count(dst)) {
		ctx->uploadsHoisted++;
		[upload_encoder(ctx) copyFromBuffer:(__bridge id<MTLBuffer>)src
		                       sourceOffset:(NSUInteger)src_offset
		                           toBuffer:(__bridge id<MTLBuffer>)dst
		                  destinationOffset:(NSUInteger)dst_offset
		                               size:(NSUInteger)length];
		return;
	}
	use_buffer(ctx, src);
	use_buffer(ctx, dst);
	[blit_encoder(ctx) copyFromBuffer:(__bridge id<MTLBuffer>)src
	                     sourceOffset:(NSUInteger)src_offset
	                         toBuffer:(__bridge id<MTLBuffer>)dst
	                destinationOffset:(NSUInteger)dst_offset
	                             size:(NSUInteger)length];
}

extern "C" void mcm_blit_buffer_to_texture(void *handle, void *buffer, int64_t offset, int32_t bytes_per_row, int32_t bytes_per_image, void *texture, int32_t slice, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] upload -> %s %dx%d", tex_desc((__bridge id<MTLTexture>)texture), width, height);
	McmContext *ctx = (McmContext *)handle;
	if (width <= 0 || height <= 0) return;
	use_buffer(ctx, buffer);
	[blit_encoder(ctx) copyFromBuffer:(__bridge id<MTLBuffer>)buffer
	                     sourceOffset:(NSUInteger)offset
	                sourceBytesPerRow:(NSUInteger)bytes_per_row
	              sourceBytesPerImage:(NSUInteger)bytes_per_image
	                       sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
	                        toTexture:(__bridge id<MTLTexture>)texture
	                 destinationSlice:(NSUInteger)slice
	                 destinationLevel:(NSUInteger)mip
	                destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
}

extern "C" void mcm_blit_texture_to_buffer(void *handle, void *texture, int32_t slice, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height, void *buffer, int64_t offset, int32_t bytes_per_row, int32_t bytes_per_image) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] readback %s %dx%d", tex_desc((__bridge id<MTLTexture>)texture), width, height);
	McmContext *ctx = (McmContext *)handle;
	if (width <= 0 || height <= 0) return;
	use_buffer(ctx, buffer);
	id<MTLTexture> tex = (__bridge id<MTLTexture>)texture;
	MTLBlitOption options = MTLBlitOptionNone;
	if (tex.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) options = MTLBlitOptionDepthFromDepthStencil;
	[blit_encoder(ctx) copyFromTexture:tex
	                       sourceSlice:(NSUInteger)slice
	                       sourceLevel:(NSUInteger)mip
	                      sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)
	                        sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
	                          toBuffer:(__bridge id<MTLBuffer>)buffer
	                 destinationOffset:(NSUInteger)offset
	            destinationBytesPerRow:(NSUInteger)bytes_per_row
	          destinationBytesPerImage:(NSUInteger)bytes_per_image
	                           options:options];
}

extern "C" void mcm_blit_texture_to_texture(void *handle, void *src, void *dst, int32_t mip, int32_t src_x, int32_t src_y, int32_t dst_x, int32_t dst_y, int32_t width, int32_t height) {
	if (pass_log((McmContext *)handle)) { mcm_log("[pass] copy %s", tex_desc((__bridge id<MTLTexture>)src)); mcm_log("[pass]   -> %s %dx%d", tex_desc((__bridge id<MTLTexture>)dst), width, height); }
	McmContext *ctx = (McmContext *)handle;
	if (width <= 0 || height <= 0) return;
	[blit_encoder(ctx) copyFromTexture:(__bridge id<MTLTexture>)src
	                       sourceSlice:0
	                       sourceLevel:(NSUInteger)mip
	                      sourceOrigin:MTLOriginMake((NSUInteger)src_x, (NSUInteger)src_y, 0)
	                        sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
	                         toTexture:(__bridge id<MTLTexture>)dst
	                  destinationSlice:0
	                  destinationLevel:(NSUInteger)mip
	                 destinationOrigin:MTLOriginMake((NSUInteger)dst_x, (NSUInteger)dst_y, 0)];
}

void clear_texture_now(McmContext *ctx, id<MTLTexture> tex, NSUInteger mip, NSUInteger slice, MTLClearColor color, double depth) {
	@autoreleasepool {
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		if (is_depth_format(tex.pixelFormat) || tex.pixelFormat == MTLPixelFormatStencil8) {
			if (tex.pixelFormat != MTLPixelFormatStencil8) {
				pass.depthAttachment.texture = tex;
				pass.depthAttachment.level = mip;
				pass.depthAttachment.slice = slice;
				pass.depthAttachment.loadAction = MTLLoadActionClear;
				pass.depthAttachment.storeAction = MTLStoreActionStore;
				pass.depthAttachment.clearDepth = depth;
			}
			if (has_stencil(tex.pixelFormat)) {
				pass.stencilAttachment.texture = tex;
				pass.stencilAttachment.level = mip;
				pass.stencilAttachment.slice = slice;
				pass.stencilAttachment.loadAction = MTLLoadActionClear;
				pass.stencilAttachment.storeAction = MTLStoreActionStore;
			}
		} else {
			pass.colorAttachments[0].texture = tex;
			pass.colorAttachments[0].level = mip;
			pass.colorAttachments[0].slice = slice;
			pass.colorAttachments[0].loadAction = MTLLoadActionClear;
			pass.colorAttachments[0].storeAction = MTLStoreActionStore;
			pass.colorAttachments[0].clearColor = color;
		}
		id<MTLRenderCommandEncoder> encoder = [command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
		[encoder endEncoding];
	}
}

// Executes every deferred clear that no render pass absorbed. Must run before anything else can observe the
// textures: blits, other passes, presentation and submission.
void flush_clears(McmContext *ctx) {
	for (int i = 0; i < ctx->pendingCount; i++) {
		McmContext::PendingClear &clear = ctx->pending[i];
		if (clear.texture) {
			clear_texture_now(ctx, clear.texture, clear.mip, clear.slice, clear.color, clear.depth);
			clear.texture = nil;
		}
	}
	ctx->pendingCount = 0;
}

// Finds (and removes) a deferred clear covering exactly this attachment, which may be a single-level view.
bool take_clear(McmContext *ctx, id<MTLTexture> attachment, McmContext::PendingClear *out) {
	id<MTLTexture> base = attachment.parentTexture ? attachment.parentTexture : attachment;
	NSUInteger level = attachment.parentTexture ? attachment.parentRelativeLevel : 0;
	for (int i = 0; i < ctx->pendingCount; i++) {
		McmContext::PendingClear &clear = ctx->pending[i];
		if (clear.texture == base && clear.mip == level && clear.slice == 0) {
			*out = clear;
			clear.texture = nil;
			return true;
		}
	}
	return false;
}

extern "C" void mcm_clear_texture(void *handle, void *texture, int32_t mip, int32_t slice, float r, float g, float b, float a, double depth) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] clear %s", tex_desc((__bridge id<MTLTexture>)texture));
	McmContext *ctx = (McmContext *)handle;
	static const bool foldClears = getenv("MCMETAL_NO_CLEAR_FOLD") == nullptr;
	if (!foldClears) {
		end_blit(ctx);
		end_render(ctx);
		clear_texture_now(ctx, (__bridge id<MTLTexture>)texture, (NSUInteger)mip, (NSUInteger)slice, MTLClearColorMake(r, g, b, a), depth);
		return;
	}
	// Close any open blit encoder so a later copy from this texture starts a new one (which flushes the clear).
	end_blit(ctx);
	// A lingering pass stays open: the clear is only recorded here, and whatever consumes it either merges it into
	// that pass (merge_with_clears) or ends the pass before it lands (begin_pass, blits, submit all end_render first).
	if (!ctx->lingering) end_render(ctx);
	id<MTLTexture> tex = (__bridge id<MTLTexture>)texture;
	for (int i = 0; i < ctx->pendingCount; i++) {
		McmContext::PendingClear &clear = ctx->pending[i];
		if (clear.texture == tex && clear.mip == (NSUInteger)mip && clear.slice == (NSUInteger)slice) {
			clear.color = MTLClearColorMake(r, g, b, a);
			clear.depth = depth;
			return;
		}
	}
	if (ctx->pendingCount == 16) {
		end_render(ctx);
		flush_clears(ctx);
	}
	ctx->pending[ctx->pendingCount++] = {tex, (NSUInteger)mip, (NSUInteger)slice, MTLClearColorMake(r, g, b, a), depth};
}

extern "C" void mcm_clear_region(void *handle, void *color_texture, void *depth_texture, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height, float r, float g, float b, float a, double depth) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] clear region %dx%d", width, height);
	McmContext *ctx = (McmContext *)handle;
	end_blit(ctx);
	end_render(ctx);
	flush_clears(ctx);
	@autoreleasepool {
		id<MTLTexture> color = (__bridge id<MTLTexture>)color_texture;
		id<MTLTexture> depthTex = (__bridge id<MTLTexture>)depth_texture;
		NSNumber *key = @(((uint64_t)color.pixelFormat << 32) | (uint64_t)depthTex.pixelFormat);
		id<MTLRenderPipelineState> state = ctx->clearPipelines[key];
		if (!state) {
			state = make_internal_pipeline(ctx, @"mcm_clear_vs", @"mcm_clear_fs", color.pixelFormat, depthTex.pixelFormat);
			if (!state) return;
			ctx->clearPipelines[key] = state;
		}

		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = color;
		pass.colorAttachments[0].level = (NSUInteger)mip;
		pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;
		pass.depthAttachment.texture = depthTex;
		pass.depthAttachment.level = (NSUInteger)mip;
		pass.depthAttachment.loadAction = MTLLoadActionLoad;
		pass.depthAttachment.storeAction = MTLStoreActionStore;
		if (has_stencil(depthTex.pixelFormat)) {
			pass.stencilAttachment.texture = depthTex;
			pass.stencilAttachment.level = (NSUInteger)mip;
			pass.stencilAttachment.loadAction = MTLLoadActionLoad;
			pass.stencilAttachment.storeAction = MTLStoreActionStore;
		}

		NSUInteger targetWidth = std::max<NSUInteger>(color.width >> mip, 1);
		NSUInteger targetHeight = std::max<NSUInteger>(color.height >> mip, 1);
		NSUInteger sx = std::min<NSUInteger>((NSUInteger)std::max(x, 0), targetWidth);
		NSUInteger sy = std::min<NSUInteger>((NSUInteger)std::max(y, 0), targetHeight);
		NSUInteger sw = std::min<NSUInteger>((NSUInteger)std::max(width, 0), targetWidth - sx);
		NSUInteger sh = std::min<NSUInteger>((NSUInteger)std::max(height, 0), targetHeight - sy);

		id<MTLRenderCommandEncoder> encoder = [command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
		if (sw > 0 && sh > 0) {
			float clearColor[4] = {r, g, b, a};
			float clearDepth = (float)depth;
			[encoder setRenderPipelineState:state];
			[encoder setDepthStencilState:ctx->clearDepthState];
			[encoder setScissorRect:(MTLScissorRect){sx, sy, sw, sh}];
			[encoder setVertexBytes:&clearDepth length:sizeof(clearDepth) atIndex:0];
			[encoder setFragmentBytes:clearColor length:sizeof(clearColor) atIndex:0];
			[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		}
		[encoder endEncoding];
	}
}
