// Render passes, bindings and draws.

#include "internal.h"

static id<MTLTexture> base_texture(id<MTLTexture> texture, NSUInteger *level) {
	if (texture.parentTexture) {
		*level = texture.parentRelativeLevel;
		return texture.parentTexture;
	}
	*level = 0;
	return texture;
}

// True if the lingering encoder already has exactly these attachments and the new pass loads (not clears) them.
static bool can_merge(McmContext *ctx, void *const *color_textures, const int32_t *clear_flags, int32_t color_count, void *depth_texture, int32_t clear_depth) {
	if (!ctx->render || !ctx->lingering || ctx->pendingCount > 0 || clear_depth || color_count != ctx->passColorCount) return false;
	for (int32_t i = 0; i < color_count; i++) {
		if (clear_flags[i]) return false;
		NSUInteger level = 0;
		id<MTLTexture> base = color_textures[i] ? base_texture((__bridge id<MTLTexture>)color_textures[i], &level) : nil;
		if (base != ctx->passColors[i] || (base && level != ctx->passColorLevels[i])) return false;
	}
	NSUInteger depthLevel = 0;
	id<MTLTexture> depthBase = depth_texture ? base_texture((__bridge id<MTLTexture>)depth_texture, &depthLevel) : nil;
	return depthBase == ctx->passDepth && (!depthBase || depthLevel == ctx->passDepthLevel);
}

// Like can_merge, but the new pass also clears some of the open pass's attachments (via its clear flags or
// deferred clears). Instead of ending the pass (store + reload of every attachment), the clear is drawn inside it
// as a full-screen triangle, which on a tile-based GPU only touches tile memory.
static bool merge_with_clears(McmContext *ctx, void *const *color_textures, const int32_t *clear_flags, const float *clear_colors, int32_t color_count, void *depth_texture, int32_t clear_depth, double depth) {
	static const bool enabled = getenv("MCMETAL_NO_CLEAR_MERGE") == nullptr;
	if (!enabled || !ctx->render || !ctx->lingering || color_count != ctx->passColorCount || color_count > 1) return false;
	id<MTLTexture> colorTex = color_count == 1 && color_textures[0] ? (__bridge id<MTLTexture>)color_textures[0] : nil;
	NSUInteger colorLevel = 0, depthLevel = 0;
	id<MTLTexture> colorBase = colorTex ? base_texture(colorTex, &colorLevel) : nil;
	if (color_count == 1 && (colorBase != ctx->passColors[0] || (colorBase && colorLevel != ctx->passColorLevels[0]))) return false;
	id<MTLTexture> depthTex = depth_texture ? (__bridge id<MTLTexture>)depth_texture : nil;
	id<MTLTexture> depthBase = depthTex ? base_texture(depthTex, &depthLevel) : nil;
	if (depthBase != ctx->passDepth || (depthBase && depthLevel != ctx->passDepthLevel)) return false;
	if (depthBase && has_stencil(depthBase.pixelFormat)) return false;

	// Every deferred clear must target one of these attachments; others would have to land before the pass.
	for (int i = 0; i < ctx->pendingCount; i++) {
		McmContext::PendingClear &clear = ctx->pending[i];
		if (!clear.texture || clear.slice != 0) {
			if (clear.texture) return false;
			continue;
		}
		bool isColor = colorBase && clear.texture == colorBase && clear.mip == colorLevel;
		bool isDepth = depthBase && clear.texture == depthBase && clear.mip == depthLevel;
		if (!isColor && !isDepth) return false;
	}

	// Decide what to clear and build the pipeline before consuming any deferred clear, so a failure here leaves
	// the clears in place for the normal path.
	auto find_pending = [ctx](id<MTLTexture> base, NSUInteger level) -> int {
		for (int i = 0; i < ctx->pendingCount; i++) {
			if (ctx->pending[i].texture == base && ctx->pending[i].mip == level && ctx->pending[i].slice == 0) return i;
		}
		return -1;
	};
	int pendingColor = colorBase ? find_pending(colorBase, colorLevel) : -1;
	int pendingDepth = depthBase ? find_pending(depthBase, depthLevel) : -1;
	bool clearColor = colorBase && (clear_flags[0] || pendingColor >= 0);
	bool clearDepthNow = depthBase && (clear_depth || pendingDepth >= 0);
	MTLClearColor color = MTLClearColorMake(0, 0, 0, 0);
	if (clearColor) {
		color = clear_flags[0] ? MTLClearColorMake(clear_colors[0], clear_colors[1], clear_colors[2], clear_colors[3]) : ctx->pending[pendingColor].color;
	}
	double depthValue = clear_depth || pendingDepth < 0 ? depth : ctx->pending[pendingDepth].depth;

	id<MTLRenderPipelineState> state = nil;
	if (clearColor || clearDepthNow) {
		MTLPixelFormat colorFormat = colorBase ? colorTex.pixelFormat : MTLPixelFormatInvalid;
		MTLPixelFormat depthFormat = depthBase ? depthTex.pixelFormat : MTLPixelFormatInvalid;
		NSString *key = [NSString stringWithFormat:@"%lu-%lu-%d", (unsigned long)colorFormat, (unsigned long)depthFormat, clearColor ? 1 : 0];
		state = ctx->inPassClearPipelines[key];
		if (!state) {
			MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
			desc.vertexFunction = [ctx->internalLibrary newFunctionWithName:@"mcm_clear_vs"];
			desc.fragmentFunction = clearColor ? [ctx->internalLibrary newFunctionWithName:@"mcm_clear_fs"] : nil;
			desc.colorAttachments[0].pixelFormat = colorFormat;
			desc.colorAttachments[0].writeMask = clearColor ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
			desc.depthAttachmentPixelFormat = depthFormat;
			NSError *error = nil;
			state = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
			if (!state) {
				mcm_log("in-pass clear pipeline failed: %s", error.localizedDescription.UTF8String);
				return false;
			}
			ctx->inPassClearPipelines[key] = state;
		}
	}
	// Every pending clear is one of ours (checked above) and is now handled here.
	for (int i = 0; i < ctx->pendingCount; i++) ctx->pending[i].texture = nil;
	ctx->pendingCount = 0;
	if (!state) return true;

	id<MTLRenderCommandEncoder> encoder = ctx->render;
	float clearColorValues[4] = {(float)color.red, (float)color.green, (float)color.blue, (float)color.alpha};
	float clearDepthValue = (float)depthValue;
	[encoder setRenderPipelineState:state];
	if (depthBase) [encoder setDepthStencilState:clearDepthNow ? ctx->clearDepthState : ctx->depthDisabled];
	[encoder setCullMode:MTLCullModeNone];
	[encoder setTriangleFillMode:MTLTriangleFillModeFill];
	[encoder setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
	[encoder setScissorRect:(MTLScissorRect){0, 0, (NSUInteger)ctx->passWidth, (NSUInteger)ctx->passHeight}];
	[encoder setVertexBytes:&clearDepthValue length:sizeof(clearDepthValue) atIndex:0];
	if (clearColor) [encoder setFragmentBytes:clearColorValues length:sizeof(clearColorValues) atIndex:0];
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];

	// The game's next setPipeline rebinds everything; forget what we think is bound.
	ctx->pipeline = nullptr;
	ctx->pipelineUsable = false;
	ctx->boundState = nil;
	ctx->boundDepthState = nil;
	ctx->boundCull = -1;
	ctx->boundFill = -1;
	ctx->boundBiasConstant = NAN;
	ctx->boundBiasSlope = NAN;
	ctx->clearsMerged++;
	return true;
}

extern "C" void mcm_begin_pass(void *handle, void *const *color_textures, const int32_t *clear_flags, const float *clear_colors, int32_t color_count, void *depth_texture, int32_t clear_depth, double depth, int32_t x, int32_t y, int32_t width, int32_t height, const char *label) {
	if (pass_log((McmContext *)handle)) {
		mcm_log("[pass] BEGIN '%s' colors=%d clear=%d depthclear=%d", label ? label : "?", color_count, color_count > 0 ? clear_flags[0] : 0, clear_depth);
		for (int32_t i = 0; i < color_count; i++) mcm_log("[pass]   color%d %s", i, tex_desc((__bridge id<MTLTexture>)color_textures[i]));
		mcm_log("[pass]   depth %s", tex_desc((__bridge id<MTLTexture>)depth_texture));
	}
	McmContext *ctx = (McmContext *)handle;
	end_blit(ctx);
	ctx->passesBegun++;
	static const bool mergePasses = getenv("MCMETAL_NO_PASS_MERGE") == nullptr;
	if (mergePasses && (can_merge(ctx, color_textures, clear_flags, color_count, depth_texture, clear_depth)
	                    || merge_with_clears(ctx, color_textures, clear_flags, clear_colors, color_count, depth_texture, clear_depth, depth))) {
		ctx->lingering = false;
		ctx->passesMerged++;
		if (label) {
			@autoreleasepool {
				[ctx->render insertDebugSignpost:[NSString stringWithUTF8String:label]];
			}
		}
		mcm_pass_set_scissor(handle, x, y, width, height);
		return;
	}
	end_render(ctx);
	@autoreleasepool {
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		int32_t passWidth = 0, passHeight = 0;
		McmContext::PendingClear absorbed;
		for (int32_t i = 0; i < color_count; i++) {
			if (!color_textures[i]) continue;
			id<MTLTexture> texture = (__bridge id<MTLTexture>)color_textures[i];
			MTLRenderPassColorAttachmentDescriptor *attachment = pass.colorAttachments[(NSUInteger)i];
			attachment.texture = texture;
			attachment.storeAction = MTLStoreActionStore;
			if (clear_flags[i]) {
				attachment.loadAction = MTLLoadActionClear;
				const float *c = clear_colors + i * 4;
				attachment.clearColor = MTLClearColorMake(c[0], c[1], c[2], c[3]);
			} else if (take_clear(ctx, texture, &absorbed)) {
				attachment.loadAction = MTLLoadActionClear;
				attachment.clearColor = absorbed.color;
			} else {
				attachment.loadAction = MTLLoadActionLoad;
			}
			passWidth = (int32_t)texture.width;
			passHeight = (int32_t)texture.height;
		}
		ctx->passHasDepth = depth_texture != nullptr;
		if (depth_texture) {
			id<MTLTexture> texture = (__bridge id<MTLTexture>)depth_texture;
			pass.depthAttachment.texture = texture;
			pass.depthAttachment.storeAction = MTLStoreActionStore;
			pass.depthAttachment.loadAction = clear_depth ? MTLLoadActionClear : MTLLoadActionLoad;
			pass.depthAttachment.clearDepth = depth;
			bool absorbedDepth = !clear_depth && take_clear(ctx, texture, &absorbed);
			if (absorbedDepth) {
				pass.depthAttachment.loadAction = MTLLoadActionClear;
				pass.depthAttachment.clearDepth = absorbed.depth;
			}
			if (has_stencil(texture.pixelFormat)) {
				pass.stencilAttachment.texture = texture;
				pass.stencilAttachment.loadAction = absorbedDepth ? MTLLoadActionClear : MTLLoadActionLoad;
				pass.stencilAttachment.storeAction = MTLStoreActionStore;
			}
			if (passWidth == 0) {
				passWidth = (int32_t)texture.width;
				passHeight = (int32_t)texture.height;
			}
		}
		ctx->passWidth = passWidth;
		ctx->passHeight = passHeight;
		// Clears this pass didn't absorb may target textures it samples, so they must land first.
		flush_clears(ctx);

		id<MTLRenderCommandEncoder> encoder = [command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
		if (label) encoder.label = [NSString stringWithUTF8String:label];
		[encoder setViewport:(MTLViewport){0.0, 0.0, (double)passWidth, (double)passHeight, 0.0, 1.0}];
		// Shaders flip Y (SPIRV-Cross flip_vert_y) so screen-space winding matches Vulkan's VK_FRONT_FACE_CLOCKWISE.
		[encoder setFrontFacingWinding:MTLWindingClockwise];
		ctx->render = encoder;
		ctx->passColorCount = color_count;
		for (int32_t i = 0; i < color_count && i < 8; i++) {
			NSUInteger level = 0;
			ctx->passColors[i] = color_textures[i] ? base_texture((__bridge id<MTLTexture>)color_textures[i], &level) : nil;
			ctx->passColorLevels[i] = level;
		}
		NSUInteger depthLevel = 0;
		ctx->passDepth = depth_texture ? base_texture((__bridge id<MTLTexture>)depth_texture, &depthLevel) : nil;
		ctx->passDepthLevel = depthLevel;
		ctx->boundCull = -1;
		ctx->boundFill = -1;
		ctx->boundBiasConstant = NAN;
		ctx->boundBiasSlope = NAN;
	}
	mcm_pass_set_scissor(handle, x, y, width, height);
}

extern "C" void mcm_end_pass(void *handle) {
	// Keep the encoder open; whatever comes next either merges into it or closes it (see end_render callers).
	((McmContext *)handle)->lingering = true;
}

extern "C" void mcm_pass_set_pipeline(void *handle, void *pipeline_handle) {
	McmContext *ctx = (McmContext *)handle;
	McmPipeline *pipeline = (McmPipeline *)pipeline_handle;
	id<MTLRenderCommandEncoder> encoder = ctx->render;
	ctx->pipeline = pipeline;
	id<MTLRenderPipelineState> state = ctx->passHasDepth ? pipeline->withDepth : pipeline->withoutDepth;
	ctx->pipelineUsable = state != nil && !pipeline->skip;
	if (!state) return;

	if (state != ctx->boundState) {
		[encoder setRenderPipelineState:state];
		ctx->boundState = state;
	}
	if (ctx->passHasDepth && pipeline->depthState != ctx->boundDepthState) {
		[encoder setDepthStencilState:pipeline->depthState];
		ctx->boundDepthState = pipeline->depthState;
	}
	if ((int)pipeline->cull != ctx->boundCull) {
		[encoder setCullMode:pipeline->cull];
		ctx->boundCull = (int)pipeline->cull;
	}
	if ((int)pipeline->fill != ctx->boundFill) {
		[encoder setTriangleFillMode:pipeline->fill];
		ctx->boundFill = (int)pipeline->fill;
	}
	if (pipeline->biasConstant != ctx->boundBiasConstant || pipeline->biasSlope != ctx->boundBiasSlope) {
		[encoder setDepthBias:pipeline->biasConstant slopeScale:pipeline->biasSlope clamp:0.0f];
		ctx->boundBiasConstant = pipeline->biasConstant;
		ctx->boundBiasSlope = pipeline->biasSlope;
	}
}

extern "C" void mcm_pass_bind(void *handle, const int64_t *entries, int32_t count) {
	McmContext *ctx = (McmContext *)handle;
	id<MTLRenderCommandEncoder> encoder = ctx->render;
	for (int32_t i = 0; i < count; i++) {
		const int64_t *e = entries + i * 5;
		int kind = (int)(e[0] & 0xff);
		int stages = (int)((e[0] >> 8) & 3);
		NSUInteger index = (NSUInteger)e[1];
		switch (kind) {
			case MCM_BIND_BUFFER: {
				id<MTLBuffer> buffer = (__bridge id<MTLBuffer>)(void *)e[2];
				use_buffer(ctx, (void *)e[2]);
				if (stages & 1) [encoder setVertexBuffer:buffer offset:(NSUInteger)e[3] atIndex:index];
				if (stages & 2) [encoder setFragmentBuffer:buffer offset:(NSUInteger)e[3] atIndex:index];
				break;
			}
			case MCM_BIND_TEXTURE_SAMPLER: {
				id<MTLTexture> texture = (__bridge id<MTLTexture>)(void *)e[2];
				if (texture.buffer) use_buffer(ctx, (__bridge void *)texture.buffer);
				id<MTLSamplerState> sampler = (__bridge id<MTLSamplerState>)(void *)e[3];
				if (stages & 1) {
					[encoder setVertexTexture:texture atIndex:index];
					[encoder setVertexSamplerState:sampler atIndex:index];
				}
				if (stages & 2) {
					[encoder setFragmentTexture:texture atIndex:index];
					[encoder setFragmentSamplerState:sampler atIndex:index];
				}
				break;
			}
			case MCM_BIND_TEXTURE: {
				id<MTLTexture> texture = (__bridge id<MTLTexture>)(void *)e[2];
				if (texture.buffer) use_buffer(ctx, (__bridge void *)texture.buffer);
				if (stages & 1) [encoder setVertexTexture:texture atIndex:index];
				if (stages & 2) [encoder setFragmentTexture:texture atIndex:index];
				break;
			}
			default:
				break;
		}
	}
}

extern "C" void mcm_pass_set_bytes(void *handle, int32_t index, const void *data, int32_t length) {
	McmContext *ctx = (McmContext *)handle;
	[ctx->render setVertexBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
	[ctx->render setFragmentBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
}

extern "C" void mcm_pass_set_vertex_buffer(void *handle, int32_t slot, void *buffer, int64_t offset) {
	use_buffer((McmContext *)handle, buffer);
	McmContext *ctx = (McmContext *)handle;
	[ctx->render setVertexBuffer:(__bridge id<MTLBuffer>)buffer offset:(NSUInteger)offset atIndex:(NSUInteger)(MCM_VERTEX_BUFFER_BASE_INDEX + slot)];
}

extern "C" void mcm_pass_set_index_buffer(void *handle, void *buffer, int32_t index_size) {
	use_buffer((McmContext *)handle, buffer);
	McmContext *ctx = (McmContext *)handle;
	ctx->indexBuffer = (__bridge id<MTLBuffer>)buffer;
	ctx->indexSize = index_size;
	ctx->indexType = index_size == 4 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
}

extern "C" void mcm_pass_set_scissor(void *handle, int32_t x, int32_t y, int32_t width, int32_t height) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->render) return;
	// Metal rejects scissor rects that leave the attachment, so clamp instead of trusting the caller.
	int32_t x0 = std::clamp(x, 0, ctx->passWidth);
	int32_t y0 = std::clamp(y, 0, ctx->passHeight);
	int32_t x1 = std::clamp(x + std::max(width, 0), x0, ctx->passWidth);
	int32_t y1 = std::clamp(y + std::max(height, 0), y0, ctx->passHeight);
	[ctx->render setScissorRect:(MTLScissorRect){(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)}];
}

static void ensure_fan_indices(McmContext *ctx, uint32_t triangles) {
	if (triangles <= ctx->fanTriangles) return;
	uint32_t capacity = std::max<uint32_t>(triangles * 2, 1024);
	id<MTLBuffer> buffer = [ctx->device newBufferWithLength:capacity * 3 * sizeof(uint32_t) options:MTLResourceStorageModeShared];
	uint32_t *indices = (uint32_t *)buffer.contents;
	for (uint32_t k = 0; k < capacity; k++) {
		indices[k * 3] = 0;
		indices[k * 3 + 1] = k + 1;
		indices[k * 3 + 2] = k + 2;
	}
	if (ctx->fanIndices) [ctx->retired addObject:ctx->fanIndices];
	ctx->fanIndices = buffer;
	ctx->fanTriangles = capacity;
}

// Fans only ever come from the game's sequential index buffer, so the fan is rebuilt from the base vertex.
static void draw_fan(McmContext *ctx, int32_t vertex_count, int32_t instance_count, int32_t base_vertex, int32_t first_instance) {
	if (vertex_count < 3) return;
	uint32_t triangles = (uint32_t)vertex_count - 2;
	ensure_fan_indices(ctx, triangles);
	[ctx->render drawIndexedPrimitives:MTLPrimitiveTypeTriangle
	                        indexCount:triangles * 3
	                         indexType:MTLIndexTypeUInt32
	                       indexBuffer:ctx->fanIndices
	                 indexBufferOffset:0
	                     instanceCount:(NSUInteger)instance_count
	                        baseVertex:base_vertex
	                      baseInstance:(NSUInteger)first_instance];
}

extern "C" void mcm_pass_draw(void *handle, int32_t vertex_count, int32_t instance_count, int32_t first_vertex, int32_t first_instance) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable || vertex_count <= 0 || instance_count <= 0) return;
	if (ctx->pipeline->fan) {
		draw_fan(ctx, vertex_count, instance_count, first_vertex, first_instance);
		return;
	}
	[ctx->render drawPrimitives:ctx->pipeline->primitive
	                vertexStart:(NSUInteger)first_vertex
	                vertexCount:(NSUInteger)vertex_count
	              instanceCount:(NSUInteger)instance_count
	               baseInstance:(NSUInteger)first_instance];
}

extern "C" void mcm_pass_draw_indexed(void *handle, int32_t index_count, int32_t instance_count, int32_t first_index, int32_t vertex_offset, int32_t first_instance) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable || index_count <= 0 || instance_count <= 0 || !ctx->indexBuffer) return;
	if (ctx->pipeline->fan) {
		draw_fan(ctx, index_count, instance_count, vertex_offset + first_index, first_instance);
		return;
	}
	[ctx->render drawIndexedPrimitives:ctx->pipeline->primitive
	                        indexCount:(NSUInteger)index_count
	                         indexType:ctx->indexType
	                       indexBuffer:ctx->indexBuffer
	                 indexBufferOffset:(NSUInteger)first_index * (NSUInteger)ctx->indexSize
	                     instanceCount:(NSUInteger)instance_count
	                        baseVertex:vertex_offset
	                      baseInstance:(NSUInteger)first_instance];
}

// params: draw_count * {first_index, index_count, vertex_offset} (VkMultiDrawIndexedInfoEXT layout)
extern "C" void mcm_pass_multi_draw_indexed(void *handle, const int32_t *params, int32_t draw_count, int32_t instance_count, int32_t first_instance) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable || instance_count <= 0 || !ctx->indexBuffer) return;
	id<MTLRenderCommandEncoder> encoder = ctx->render;
	MTLPrimitiveType primitive = ctx->pipeline->primitive;
	for (int32_t i = 0; i < draw_count; i++) {
		const int32_t *p = params + i * 3;
		if (p[1] <= 0) continue;
		if (ctx->pipeline->fan) {
			draw_fan(ctx, p[1], instance_count, p[2] + p[0], first_instance);
			continue;
		}
		[encoder drawIndexedPrimitives:primitive
		                    indexCount:(NSUInteger)p[1]
		                     indexType:ctx->indexType
		                   indexBuffer:ctx->indexBuffer
		             indexBufferOffset:(NSUInteger)p[0] * (NSUInteger)ctx->indexSize
		                 instanceCount:(NSUInteger)instance_count
		                    baseVertex:p[2]
		                  baseInstance:(NSUInteger)first_instance];
	}
}

// params: draw_count * {first_vertex, vertex_count} (VkMultiDrawInfoEXT layout)
extern "C" void mcm_pass_multi_draw(void *handle, const int32_t *params, int32_t draw_count, int32_t instance_count, int32_t first_instance) {
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable || instance_count <= 0) return;
	for (int32_t i = 0; i < draw_count; i++) {
		const int32_t *p = params + i * 2;
		mcm_pass_draw(handle, p[1], instance_count, p[0], first_instance);
	}
}

extern "C" void mcm_pass_draw_indexed_indirect(void *handle, void *buffer, int64_t offset, int32_t draw_count) {
	use_buffer((McmContext *)handle, buffer);
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable || !ctx->indexBuffer) return;
	id<MTLBuffer> indirect = (__bridge id<MTLBuffer>)buffer;
	// VkDrawIndexedIndirectCommand and MTLDrawIndexedPrimitivesIndirectArguments share one layout (20 bytes).
	for (int32_t i = 0; i < draw_count; i++) {
		[ctx->render drawIndexedPrimitives:ctx->pipeline->primitive
		                         indexType:ctx->indexType
		                       indexBuffer:ctx->indexBuffer
		                 indexBufferOffset:0
		                    indirectBuffer:indirect
		              indirectBufferOffset:(NSUInteger)(offset + (int64_t)i * 20)];
	}
}

extern "C" void mcm_pass_draw_indirect(void *handle, void *buffer, int64_t offset, int32_t draw_count) {
	use_buffer((McmContext *)handle, buffer);
	McmContext *ctx = (McmContext *)handle;
	if (!ctx->pipelineUsable) return;
	id<MTLBuffer> indirect = (__bridge id<MTLBuffer>)buffer;
	for (int32_t i = 0; i < draw_count; i++) {
		[ctx->render drawPrimitives:ctx->pipeline->primitive indirectBuffer:indirect indirectBufferOffset:(NSUInteger)(offset + (int64_t)i * 16)];
	}
}

extern "C" void mcm_push_debug_group(void *handle, const char *label) {
	McmContext *ctx = (McmContext *)handle;
	@autoreleasepool {
		NSString *name = [NSString stringWithUTF8String:label ? label : "?"];
		if (ctx->render) {
			[ctx->render pushDebugGroup:name];
		} else {
			[command_buffer(ctx) pushDebugGroup:name];
		}
	}
}

extern "C" void mcm_pop_debug_group(void *handle) {
	McmContext *ctx = (McmContext *)handle;
	if (ctx->render) {
		[ctx->render popDebugGroup];
	} else if (ctx->commandBuffer) {
		[ctx->commandBuffer popDebugGroup];
	}
}
