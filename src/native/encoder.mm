// Command buffer and encoder management.

#include "internal.h"

id<MTLCommandBuffer> command_buffer(McmContext *ctx) {
	if (!ctx->commandBuffer) {
		@autoreleasepool {
			// Unretained references: resource lifetime is managed by the Java destruction queues,
			// which keep everything alive until the submit that used it has completed.
			ctx->commandBuffer = [ctx->queue commandBufferWithUnretainedReferences];
			McmContext *c = ctx;
			[ctx->commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
				if (buffer.status == MTLCommandBufferStatusCompleted) {
					c->gpuTimeNs.fetch_add((uint64_t)((buffer.GPUEndTime - buffer.GPUStartTime) * 1e9), std::memory_order_relaxed);
					c->gpuBuffers.fetch_add(1, std::memory_order_relaxed);
				}
				if (buffer.error && !c->errorLogged) {
					c->errorLogged = true;
					mcm_log("command buffer failed: %s", buffer.error.localizedDescription.UTF8String);
				}
			}];
		}
	}
	return ctx->commandBuffer;
}

void end_blit(McmContext *ctx) {
	if (ctx->blit) {
		[ctx->blit endEncoding];
		ctx->blit = nil;
	}
}

void end_render(McmContext *ctx) {
	if (ctx->render) {
		[ctx->render endEncoding];
		ctx->render = nil;
	}
	ctx->lingering = false;
	ctx->pipeline = nullptr;
	ctx->pipelineUsable = false;
	ctx->boundState = nil;
	ctx->boundDepthState = nil;
	ctx->indexBuffer = nil;
}

// MCMETAL_PASS_LOG=<frame>: logs every pass, clear and blit of that frame (and the next), to study the frame graph.
bool pass_log(McmContext *ctx) {
	static const long frame = getenv("MCMETAL_PASS_LOG") ? atol(getenv("MCMETAL_PASS_LOG")) : -1;
	return frame >= 0 && ctx->submitted >= (uint64_t)frame && ctx->submitted <= (uint64_t)frame + 1;
}

const char *tex_desc(id<MTLTexture> t) {
	static thread_local char buf[96];
	if (!t) return "-";
	id<MTLTexture> base = t.parentTexture ?: t;
	snprintf(buf, sizeof buf, "%p(%lux%lu f%lu)", (__bridge void *)base, (unsigned long)t.width, (unsigned long)t.height, (unsigned long)t.pixelFormat);
	return buf;
}

id<MTLBlitCommandEncoder> upload_encoder(McmContext *ctx) {
	if (!ctx->uploadBlit) {
		@autoreleasepool {
			if (!ctx->uploadBuffer) {
				ctx->uploadBuffer = [ctx->queue commandBufferWithUnretainedReferences];
				ctx->uploadBuffer.label = @"Uploads";
				// Reserves its place in the queue ahead of the frame's command buffer, which is enqueued at commit.
				[ctx->uploadBuffer enqueue];
			}
			ctx->uploadBlit = [ctx->uploadBuffer blitCommandEncoder];
		}
	}
	return ctx->uploadBlit;
}

void commit_uploads(McmContext *ctx) {
	if (ctx->uploadBlit) {
		[ctx->uploadBlit endEncoding];
		ctx->uploadBlit = nil;
	}
	if (ctx->uploadBuffer) {
		[ctx->uploadBuffer commit];
		ctx->uploadBuffer = nil;
	}
}

id<MTLBlitCommandEncoder> blit_encoder(McmContext *ctx) {
	if (ctx->render) {
		// A lingering (already ended) pass is closed silently; only a blit in the middle of a pass is unexpected.
		if (!ctx->lingering) mcm_log("blit requested inside a render pass; closing the pass");
		end_render(ctx);
	}
	if (!ctx->blit) {
		flush_clears(ctx);
		@autoreleasepool {
			ctx->blit = [command_buffer(ctx) blitCommandEncoder];
		}
	}
	return ctx->blit;
}
