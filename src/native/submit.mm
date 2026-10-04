// Submission and GPU timing.

#include "internal.h"

extern "C" uint64_t mcm_submit(void *handle) {
	if (pass_log((McmContext *)handle)) mcm_log("[pass] ===== submit %llu", (unsigned long long)((McmContext *)handle)->submitted);
	McmContext *ctx = (McmContext *)handle;
	end_blit(ctx);
	end_render(ctx);
	flush_clears(ctx);
	@autoreleasepool {
		commit_uploads(ctx);
		ctx->frameBuffers.clear();
		id<MTLCommandBuffer> buffer = command_buffer(ctx);
		if (ctx->frameStart > 0) {
			double start = ctx->frameStart;
			std::shared_ptr<McmFrameStats> stats = ctx->frameStats;
			[buffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
				if (done.status == MTLCommandBufferStatusCompleted && done.GPUEndTime > start) stats->addWork(done.GPUEndTime - start);
				static const bool debug = getenv("MCMETAL_LIMIT_DEBUG") != nullptr;
				if (debug) mcm_log("limit work=%.2fms (cpu+gpu)", (done.GPUEndTime - start) * 1000);
			}];
		}
		ctx->submitted++;
		[buffer encodeSignalEvent:ctx->event value:ctx->submitted];
		[buffer commit];
		ctx->commandBuffer = nil;
	}
	present_pending(ctx, ctx->submitted);
	return ctx->submitted;
}

extern "C" int32_t mcm_wait(void *handle, uint64_t value, int64_t timeout_ns) {
	McmContext *ctx = (McmContext *)handle;
	if (ctx->event.signaledValue >= value) return 1;
	if (timeout_ns == 0) return 0;
	uint64_t timeoutMs = timeout_ns < 0 ? UINT64_MAX : (uint64_t)((timeout_ns + 999999) / 1000000);
	return [ctx->event waitUntilSignaledValue:value timeoutMS:timeoutMs] ? 1 : 0;
}

extern "C" void mcm_gpu_time(void *handle, uint64_t *out) {
	McmContext *ctx = (McmContext *)handle;
	out[0] = ctx->gpuTimeNs.load(std::memory_order_relaxed);
	out[1] = ctx->gpuBuffers.load(std::memory_order_relaxed);
	out[2] = ctx->presentsShown->load(std::memory_order_relaxed);
	out[3] = ctx->passesBegun;
	out[4] = ctx->passesMerged;
	out[5] = ctx->uploadsHoisted;
	out[6] = ctx->clearsMerged;
	out[7] = ctx->frameStats->latencyNs.load(std::memory_order_relaxed);
	out[8] = ctx->frameStats->latencyCount.load(std::memory_order_relaxed);
	out[9] = ctx->frameStats->misses.load(std::memory_order_relaxed);
}

extern "C" uint64_t mcm_completed(void *handle) {
	return ((McmContext *)handle)->event.signaledValue;
}
