// Shared internals of the native half of the MCMetal backend. Built with ARC; every exported function is a thin
// C entry point that the Java side calls through the FFM API (see com.mcmetal.metal.Native).
//
// Threading: resource/pipeline creation may be called from worker threads (MTLDevice is thread-safe).
// Everything that records commands is only ever called from the render thread.
#pragma once

#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <QuartzCore/CABase.h>
#import <CoreVideo/CoreVideo.h>
#import <AppKit/AppKit.h>
#include <mach/mach_time.h>
#import <Foundation/Foundation.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <atomic>
#include <memory>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <unordered_set>

#include "mcmetal.h"

void mcm_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void copy_error(NSError *error, char *out, int32_t len);

// Format tables (formats.mm)
MTLPixelFormat pixel_format(int32_t code);
bool is_depth_format(MTLPixelFormat format);
bool has_stencil(MTLPixelFormat format);
MTLVertexFormat vertex_format(int32_t code);
MTLBlendFactor blend_factor(int32_t code);
MTLBlendOperation blend_op(int32_t code);
MTLCompareFunction compare_function(int32_t code);
MTLPrimitiveType primitive_type(int32_t code);

// ---------------------------------------------------------------------------------------------
// Objects
// ---------------------------------------------------------------------------------------------

struct McmPipeline {
	id<MTLRenderPipelineState> withDepth;
	id<MTLRenderPipelineState> withoutDepth;
	id<MTLDepthStencilState> depthState;
	MTLCullMode cull;
	MTLTriangleFillMode fill;
	float biasConstant;
	float biasSlope;
	MTLPrimitiveType primitive;
	bool fan;
	bool skip = false;  // profiling only: MCMETAL_EXP_SKIP=<substr>,<substr> drops draws of matching pipelines
};

// Per-frame timing shared with completion/presentation handlers (any thread). Feeds the low-latency frame
// limiter and the latency numbers the benchmark reports.
struct McmFrameStats {
	std::mutex lock;
	double work[128] = {};  // frame start -> GPU finished, seconds
	int workCount = 0;
	int workNext = 0;
	std::atomic<double> margin{0.003};   // safety added to the frame's lead time; grows on a missed refresh
	// Where frames land relative to the refresh they were aimed at, in whole refreshes, for the last 64 frames.
	// The most common value is "on time" (normally 0; a display that adds a refresh of latency shifts it).
	int8_t landing[64] = {};
	int landingCount = 0;
	int landingNext = 0;

	// Records a landing and returns it relative to the usual one: 0 on time, >0 late, <0 early.
	int land(int refreshes) {
		std::lock_guard<std::mutex> guard(lock);
		landing[landingNext] = (int8_t)std::clamp(refreshes, -4, 4);
		landingNext = (landingNext + 1) % 64;
		landingCount = std::min(landingCount + 1, 64);
		int votes[9] = {};
		for (int i = 0; i < landingCount; i++) votes[landing[i] + 4]++;
		int usual = 0;
		for (int k = 0; k < 9; k++) {
			if (votes[k] > votes[usual]) usual = k;
		}
		return std::clamp(refreshes, -4, 4) - (usual - 4);
	}
	std::atomic<uint64_t> latencyNs{0};  // sum of frame start -> on screen, for presented frames
	std::atomic<uint64_t> latencyCount{0};
	std::atomic<uint64_t> misses{0};     // limited frames that reached the screen a refresh late

	void addWork(double seconds) {
		std::lock_guard<std::mutex> guard(lock);
		work[workNext] = seconds;
		workNext = (workNext + 1) % 128;
		workCount = std::min(workCount + 1, 128);
	}

	// 95th-percentile recent frame, or -1 until there is enough history to trust. Not the maximum: one GC pause or
	// chunk-loading spike would otherwise make every frame for the next second start far too early. The margin
	// controller covers the remaining slow frames.
	double typicalWork() {
		std::lock_guard<std::mutex> guard(lock);
		if (workCount < 16) return -1.0;
		double sorted[128];
		std::copy(work, work + workCount, sorted);
		int index = workCount * 95 / 100;
		std::nth_element(sorted, sorted + index, sorted + workCount);
		return sorted[index];
	}
};

struct McmContext {
	id<MTLDevice> device;
	id<MTLCommandQueue> queue;
	id<MTLSharedEvent> event;
	uint64_t submitted = 0;

	id<MTLCommandBuffer> commandBuffer;
	id<MTLBlitCommandEncoder> blit;
	id<MTLRenderCommandEncoder> render;

	// Render pass state (reset in mcm_begin_pass).
	bool passHasDepth = false;
	int32_t passWidth = 0;
	int32_t passHeight = 0;
	McmPipeline *pipeline = nullptr;
	bool pipelineUsable = false;
	id<MTLRenderPipelineState> boundState;
	id<MTLDepthStencilState> boundDepthState;
	int boundCull = -1;
	int boundFill = -1;
	float boundBiasConstant = NAN;
	float boundBiasSlope = NAN;
	id<MTLBuffer> indexBuffer;
	MTLIndexType indexType = MTLIndexTypeUInt16;
	int32_t indexSize = 2;

	id<MTLDepthStencilState> depthDisabled;

	// Upload hoisting: small buffer copies that nothing earlier in the frame touches go into a separate command
	// buffer that is enqueued ahead of the frame's. A copy in the frame's own command buffer would need a blit
	// encoder, which ends the open render pass and forces a full store + reload of its attachments.
	id<MTLCommandBuffer> uploadBuffer;
	id<MTLBlitCommandEncoder> uploadBlit;
	std::unordered_set<void *> frameBuffers;  // buffers referenced by work already encoded this frame
	uint64_t uploadsHoisted = 0;
	uint64_t clearsMerged = 0;

	// Internal pipelines for clearing inside an open render pass, keyed by attachment formats and write mask.
	NSMutableDictionary<NSString *, id<MTLRenderPipelineState>> *inPassClearPipelines;

	// Pass merging: a finished pass keeps its encoder open ("lingering") so that an immediately following pass
	// on the same attachments continues in tile memory instead of storing and reloading the whole framebuffer.
	bool lingering = false;
	id<MTLTexture> passColors[8];
	NSUInteger passColorLevels[8];
	int32_t passColorCount = 0;
	id<MTLTexture> passDepth;
	NSUInteger passDepthLevel = 0;
	uint64_t passesBegun = 0;
	uint64_t passesMerged = 0;

	// Triangle fans: Metal has no fan topology, so fans are drawn as lists through a shared
	// (0, k, k + 1) index pattern. Replaced buffers stay alive until shutdown (they only ever grow).
	id<MTLBuffer> fanIndices;
	uint32_t fanTriangles = 0;
	NSMutableArray *retired;

	// Internal pipelines.
	id<MTLLibrary> internalLibrary;
	NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *clearPipelines;
	id<MTLDepthStencilState> clearDepthState;
	id<MTLRenderPipelineState> presentPipeline;

	bool errorLogged = false;

	// GPU execution time of completed command buffers (written from Metal's completion thread).
	std::atomic<uint64_t> gpuTimeNs{0};
	std::atomic<uint64_t> gpuBuffers{0};
	std::shared_ptr<std::atomic<uint64_t>> presentsShown = std::make_shared<std::atomic<uint64_t>>(0);
	std::shared_ptr<McmFrameStats> frameStats = std::make_shared<McmFrameStats>();
	double frameStart = 0.0;   // when the current frame began (mcm_frame_begin), CACurrentMediaTime base
	double frameTarget = 0.0;  // refresh the frame limiter aimed this frame at, or 0

	// Presentation runs on its own queue so a drawable the compositor hasn't released yet only delays the
	// present, never the next frame.
	id<MTLCommandQueue> presentQueue;
	id<CAMetalDrawable> pendingDrawable;

	// Deferred clears: folded into the next render pass's load action when it targets the same image,
	// which saves a full store + reload of the attachment on a tile-based GPU.
	struct PendingClear {
		id<MTLTexture> texture;
		NSUInteger mip;
		NSUInteger slice;
		MTLClearColor color;
		double depth;
	};
	PendingClear pending[16];
	int pendingCount = 0;
};

struct McmSurface {
	CAMetalLayer *layer;
	McmContext *ctx;
	bool vsync = true;
	int32_t width = 0;
	int32_t height = 0;

	// Without vsync a background thread waits in nextDrawable and parks the result here, so the render thread
	// can present whenever the compositor has a drawable free and simply skip the frame when it doesn't.
	std::mutex lock;
	std::condition_variable wake;
	id<CAMetalDrawable> parked;
	bool fetcherRunning = false;
	bool stopping = false;
	std::thread fetcher;

	// Present pacing without vsync. The display shows at most one image per refresh, so presenting every rendered
	// frame only burns GPU time on present passes and compositing that are never seen (and makes frame times
	// alternate). Instead each refresh gets exactly one frame: the last one that can still be ready before it.
	struct Pacing {
		std::atomic<double> minLatency{1.0};  // shortest present -> on-screen time seen recently
		std::atomic<double> phaseShift{0.0};  // correction to the present cadence, consumed by pace_frame
	};
	std::shared_ptr<Pacing> pacing = std::make_shared<Pacing>();
	double lastBlit = 0.0;
	double frameTime = 0.0;   // smoothed time between frames
	double presentedSlot = 0.0;
	bool pacingLogged = false;
	double refreshInterval = 0.0;  // from the display mode; 0 = unknown (no pacing)

	// Low-latency frame limiter: a display link reports the display's refresh times; each frame starts just early
	// enough to be on screen at the next refresh, so it renders once per refresh with input read as late as possible.
	struct Clock {
		std::atomic<double> vsync{0.0};   // an upcoming refresh, CACurrentMediaTime base
		std::atomic<double> period{0.0};
	};
	std::shared_ptr<Clock> clock = std::make_shared<Clock>();
	CVDisplayLinkRef displayLink = nullptr;
	CGDirectDisplayID linkDisplay = 0;
	bool limiter = false;
	bool vsyncRequested = true;  // what the game asked for
	double lastTarget = 0.0;
};

inline void use_buffer(McmContext *ctx, void *buffer) {
	if (buffer) ctx->frameBuffers.insert(buffer);
}

// device.mm
id<MTLRenderPipelineState> make_internal_pipeline(McmContext *ctx, NSString *vs, NSString *fs, MTLPixelFormat color, MTLPixelFormat depth);

// encoder.mm
id<MTLCommandBuffer> command_buffer(McmContext *ctx);
void end_blit(McmContext *ctx);
void end_render(McmContext *ctx);
id<MTLBlitCommandEncoder> upload_encoder(McmContext *ctx);
void commit_uploads(McmContext *ctx);
id<MTLBlitCommandEncoder> blit_encoder(McmContext *ctx);
bool pass_log(McmContext *ctx);
const char *tex_desc(id<MTLTexture> t);

// transfer.mm
void clear_texture_now(McmContext *ctx, id<MTLTexture> tex, NSUInteger mip, NSUInteger slice, MTLClearColor color, double depth);
void flush_clears(McmContext *ctx);
bool take_clear(McmContext *ctx, id<MTLTexture> attachment, McmContext::PendingClear *out);

// surface.mm
void present_pending(McmContext *ctx, uint64_t frame);
