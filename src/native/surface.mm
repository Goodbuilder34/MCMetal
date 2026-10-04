// Presentation surface, pacing and the low-latency frame limiter.

#include "internal.h"

static double host_to_seconds(uint64_t host) {
	static const mach_timebase_info_data_t timebase = [] {
		mach_timebase_info_data_t t;
		mach_timebase_info(&t);
		return t;
	}();
	return (double)host * timebase.numer / timebase.denom / 1e9;
}

static uint64_t seconds_to_host(double seconds) {
	static const mach_timebase_info_data_t timebase = [] {
		mach_timebase_info_data_t t;
		mach_timebase_info(&t);
		return t;
	}();
	return (uint64_t)(seconds * 1e9 * timebase.denom / timebase.numer);
}

#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"  // CVDisplayLink: still the simplest refresh clock off the main run loop
static CVReturn display_link_callback(CVDisplayLinkRef, const CVTimeStamp *, const CVTimeStamp *output, CVOptionFlags, CVOptionFlags *, void *context) {
	McmSurface::Clock *clock = (McmSurface::Clock *)context;
	if (output->videoTimeScale > 0 && output->videoRefreshPeriod > 0) {
		clock->period.store((double)output->videoRefreshPeriod / (double)output->videoTimeScale);
	}
	clock->vsync.store(host_to_seconds(output->hostTime));
	return kCVReturnSuccess;
}

// Starts the display link, or moves it to the display the window is on now. Render (main) thread only.
static void bind_display_link(McmSurface *surface) {
	CGDirectDisplayID display = CGMainDisplayID();
	id delegate = surface->layer.delegate;
	if ([delegate isKindOfClass:[NSView class]]) {
		NSNumber *number = ((NSView *)delegate).window.screen.deviceDescription[@"NSScreenNumber"];
		if (number) display = number.unsignedIntValue;
	}
	if (!surface->displayLink) {
		if (CVDisplayLinkCreateWithCGDisplay(display, &surface->displayLink) != kCVReturnSuccess) {
			surface->displayLink = nullptr;
			mcm_log("frame limiter: no display link for display %u", display);
			return;
		}
		CVDisplayLinkSetOutputCallback(surface->displayLink, display_link_callback, surface->clock.get());
		CVDisplayLinkStart(surface->displayLink);
	} else if (display != surface->linkDisplay) {
		CVDisplayLinkSetCurrentCGDisplay(surface->displayLink, display);
	}
	surface->linkDisplay = display;
}

static void stop_display_link(McmSurface *surface) {
	if (surface->displayLink) {
		CVDisplayLinkStop(surface->displayLink);
		CVDisplayLinkRelease(surface->displayLink);
		surface->displayLink = nullptr;
	}
}
#pragma clang diagnostic pop

static void apply_sync_locked(McmSurface *surface);

extern "C" void mcm_surface_set_limiter(void *handle, int32_t enabled) {
	McmSurface *surface = (McmSurface *)handle;
	bool on = enabled != 0;
	if (on != surface->limiter) mcm_log("frame limiter %s", on ? "on" : "off");
	if (on) bind_display_link(surface);
	std::lock_guard<std::mutex> guard(surface->lock);
	surface->limiter = on;
	apply_sync_locked(surface);
}

// Called at the very start of a frame, before input is read. With the limiter on, sleeps until the latest moment
// from which the frame can still reach the screen at the next refresh.
extern "C" void mcm_frame_begin(void *surface_handle, void *ctx_handle) {
	McmSurface *surface = (McmSurface *)surface_handle;
	McmContext *ctx = (McmContext *)ctx_handle;
	ctx->frameTarget = 0.0;
	if (surface && surface->limiter) {
		double vsync = surface->clock->vsync.load(), period = surface->clock->period.load();
		double now = CACurrentMediaTime();
		if (vsync > 0 && period > 0.001 && period < 0.06 && std::fabs(now - vsync) < 1.0) {
			McmFrameStats &stats = *ctx->frameStats;
			double work = stats.typicalWork();
			double lead = work < 0 ? period : std::min(work + stats.margin.load(), period * 2.0);
			double target = vsync + std::ceil((now + lead - vsync) / period) * period;
			if (target < surface->lastTarget + period * 0.5) target = surface->lastTarget + period;  // one frame per refresh
			double start = target - lead;
			if (start > now) mach_wait_until(seconds_to_host(start));
			surface->lastTarget = target;
			ctx->frameTarget = target;
		}
	}
	ctx->frameStart = CACurrentMediaTime();
}

extern "C" void *mcm_surface_create(void *handle, void *metal_layer) {
	McmContext *ctx = (McmContext *)handle;
	CAMetalLayer *layer = (__bridge CAMetalLayer *)metal_layer;
	layer.device = ctx->device;
	layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
	layer.framebufferOnly = YES;
	layer.opaque = YES;
	layer.maximumDrawableCount = 3;
	layer.displaySyncEnabled = YES;
	layer.allowsNextDrawableTimeout = YES;
	McmSurface *surface = new McmSurface();
	surface->layer = layer;
	surface->ctx = ctx;
	surface->presentEvent = [ctx->device newSharedEvent];
	return surface;
}

static const bool DEFERRED_PRESENT = getenv("MCMETAL_NO_DEFERRED_PRESENT") == nullptr;

// Caller holds surface->lock, or is the render thread (the only writer of vsync and limiter).
static bool deferred_mode(McmSurface *surface) {
	return DEFERRED_PRESENT && !surface->vsync && !surface->limiter;
}

// Fetcher thread, deferred mode: waits for a free drawable and presents the newest finished image into it.
static void present_latest(McmSurface *surface) {
	McmContext *ctx = surface->ctx;
	@autoreleasepool {
		id<CAMetalDrawable> drawable = [surface->layer nextDrawable];
		id<MTLTexture> image = nil;
		uint64_t frame = 0, value = 0;
		double frameStart = 0.0;
		int32_t width = 0, height = 0;
		{
			std::lock_guard<std::mutex> guard(surface->lock);
			if (surface->stopping || surface->pendingSlot < 0) return;  // the drawable just goes back unused
			int slot = surface->pendingSlot;
			surface->pendingSlot = -1;
			image = surface->ring[slot].texture;
			frame = surface->pendingFrame;
			frameStart = surface->pendingFrameStart;
			width = surface->pendingWidth;
			height = surface->pendingHeight;
			value = ++surface->presentCount;
			surface->ring[slot].readUntil = value;
		}
		id<MTLCommandBuffer> buffer = [ctx->presentQueue commandBuffer];
		buffer.label = @"Present";
		[buffer encodeWaitForEvent:ctx->event value:frame];
		bool fits = drawable && image && width > 0 && height > 0;
		if (fits) {
			bool covers = (NSUInteger)width >= drawable.texture.width && (NSUInteger)height >= drawable.texture.height;
			MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
			pass.colorAttachments[0].texture = drawable.texture;
			pass.colorAttachments[0].loadAction = covers ? MTLLoadActionDontCare : MTLLoadActionClear;
			pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
			pass.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> encoder = [buffer renderCommandEncoderWithDescriptor:pass];
			encoder.label = @"Present";
			uint32_t size[2] = {(uint32_t)width, (uint32_t)height};
			[encoder setRenderPipelineState:ctx->presentPipeline];
			[encoder setViewport:(MTLViewport){0.0, 0.0, (double)width, (double)height, 0.0, 1.0}];
			[encoder setFragmentTexture:image atIndex:0];
			[encoder setFragmentBytes:size length:sizeof(size) atIndex:0];
			[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[encoder endEncoding];
			std::shared_ptr<std::atomic<uint64_t>> shown = ctx->presentsShown;
			std::shared_ptr<McmFrameStats> stats = ctx->frameStats;
			[drawable addPresentedHandler:^(id<MTLDrawable> presented) {
				double when = presented.presentedTime;
				if (when <= 0) return;
				shown->fetch_add(1, std::memory_order_relaxed);
				if (frameStart > 0 && when > frameStart) {
					stats->latencyNs.fetch_add((uint64_t)((when - frameStart) * 1e9), std::memory_order_relaxed);
					stats->latencyCount.fetch_add(1, std::memory_order_relaxed);
				}
			}];
		}
		static const bool debug = getenv("MCMETAL_PACE_DEBUG") != nullptr;
		if (debug) mcm_log("deferred present frame=%llu value=%llu fits=%d drawable=%dx%d image=%dx%d", (unsigned long long)frame, (unsigned long long)value, fits,
			drawable ? (int)drawable.texture.width : -1, drawable ? (int)drawable.texture.height : -1, width, height);
		// Always signalled, so a frame waiting to reuse the slot is released even when nothing was drawn.
		[buffer encodeSignalEvent:surface->presentEvent value:value];
		if (fits) [buffer presentDrawable:drawable];
		[buffer commit];
	}
}

// Render thread, deferred mode: copies the frame's image into the next ring slot; present_pending publishes it.
static int32_t stage_image(McmSurface *surface, McmContext *ctx, id<MTLTexture> source, int32_t mip, int32_t width, int32_t height) {
	int slot;
	uint64_t waitValue;
	id<MTLTexture> target;
	{
		std::lock_guard<std::mutex> guard(surface->lock);
		slot = surface->ringNext;
		surface->ringNext = (slot + 1) % 3;
		if (surface->pendingSlot == slot) surface->pendingSlot = -1;
		McmSurface::Image &image = surface->ring[slot];
		waitValue = image.readUntil;
		image.readUntil = 0;
		if (!image.texture || (int32_t)image.texture.width != width || (int32_t)image.texture.height != height
		    || image.texture.pixelFormat != source.pixelFormat) {
			MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:source.pixelFormat width:(NSUInteger)width
			                                                                              height:(NSUInteger)height mipmapped:NO];
			desc.storageMode = MTLStorageModePrivate;
			desc.usage = MTLTextureUsageShaderRead;
			image.texture = [ctx->device newTextureWithDescriptor:desc];
			image.texture.label = @"Present image";
		}
		target = image.texture;
	}
	if (!target) return 0;
	// A present may still be reading this slot (from three frames ago); the copy waits for it on the GPU.
	if (waitValue > 0) [command_buffer(ctx) encodeWaitForEvent:surface->presentEvent value:waitValue];
	id<MTLBlitCommandEncoder> blit = blit_encoder(ctx);
	[blit copyFromTexture:source sourceSlice:0 sourceLevel:(NSUInteger)mip sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
	            toTexture:target destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
	end_blit(ctx);
	ctx->stagedSurface = surface;
	ctx->stagedSlot = slot;
	return 1;
}

static void fetcher_loop(McmSurface *surface) {
	while (true) {
		bool deferred;
		{
			std::unique_lock<std::mutex> guard(surface->lock);
			surface->wake.wait(guard, [surface] {
				return surface->stopping || (deferred_mode(surface) ? surface->pendingSlot >= 0 : (!surface->parked && !surface->vsync));
			});
			if (surface->stopping) return;
			deferred = deferred_mode(surface);
		}
		if (deferred) {
			present_latest(surface);
			continue;
		}
		@autoreleasepool {
			id<CAMetalDrawable> drawable = [surface->layer nextDrawable];
			std::lock_guard<std::mutex> guard(surface->lock);
			if (surface->stopping) return;
			surface->parked = drawable;
		}
	}
}

// Applies the game's vsync choice. With vsync off, macOS flips each image to the display as soon as it is ready;
// the limiter keeps that (latching at refresh boundaries costs an extra refresh of latency here, and under the
// limiter's low clocks a frame takes long enough that it adds up) and instead times frames to finish just before
// each refresh, which pins any tear line to the bottom edge. Caller holds surface->lock.
static void apply_sync_locked(McmSurface *surface) {
	bool sync = surface->vsyncRequested;
	surface->layer.displaySyncEnabled = sync;
	if (sync && !surface->vsync) surface->parked = nil;  // the synced path acquires drawables itself
	surface->vsync = sync;
	if (deferred_mode(surface)) surface->parked = nil;  // deferred presents take drawables only when an image is ready
	if (!surface->vsync && !surface->fetcherRunning) {
		surface->fetcherRunning = true;
		surface->fetcher = std::thread(fetcher_loop, surface);
	}
	surface->wake.notify_all();
}

extern "C" void mcm_surface_configure(void *handle, int32_t width, int32_t height, int32_t vsync, float refresh_hz) {
	McmSurface *surface = (McmSurface *)handle;
	std::lock_guard<std::mutex> guard(surface->lock);
	surface->layer.drawableSize = CGSizeMake(width, height);
	surface->vsyncRequested = vsync != 0;
	surface->width = width;
	surface->height = height;
	// A parked drawable may have the old size; drop it and let the fetcher grab a new one.
	surface->parked = nil;
	// The window may have moved to another display: learn its refresh rate again.
	surface->pacing = std::make_shared<McmSurface::Pacing>();
	surface->refreshInterval = refresh_hz >= 20.0f && refresh_hz <= 1000.0f ? 1.0 / refresh_hz : 0.0;
	if (surface->limiter) bind_display_link(surface);
	surface->presentedSlot = 0.0;
	surface->pacingLogged = false;
	apply_sync_locked(surface);
}

static id<CAMetalDrawable> take_drawable(McmSurface *surface) {
	if (surface->vsync) {
		// Vsync: blocking here is the frame pacing.
		return [surface->layer nextDrawable];
	}
	std::lock_guard<std::mutex> guard(surface->lock);
	id<CAMetalDrawable> drawable = surface->parked;
	surface->parked = nil;
	surface->wake.notify_all();
	if (drawable && ((int32_t)drawable.texture.width != surface->width || (int32_t)drawable.texture.height != surface->height)) {
		return nil;
	}
	return drawable;
}

// Decides whether this frame should be presented. Returns false when a later frame will still be ready in time
// for the next refresh (or this refresh already has its frame), so presenting this one would never be seen.
static const bool PACE_DEBUG = getenv("MCMETAL_PACE_DEBUG") != nullptr;

static bool pace_frame(McmSurface *surface) {
	double now = CACurrentMediaTime();
	if (surface->lastBlit > 0) {
		double delta = std::min(now - surface->lastBlit, 0.1);
		surface->frameTime = surface->frameTime == 0 ? delta : surface->frameTime * 0.9 + delta * 0.1;
	}
	surface->lastBlit = now;
	double interval = surface->refreshInterval;
	// Without a known refresh rate, present everything.
	if (interval <= 0) return true;
	if (!surface->pacingLogged) {
		surface->pacingLogged = true;
		mcm_log("present pacing: display refresh %.1f Hz", 1.0 / interval);
	}
	// Presents follow a fixed cadence of one per refresh interval. Timing them from when the previous image
	// appeared instead would add the present latency to every interval and fall short of the refresh rate.
	surface->presentedSlot += surface->pacing->phaseShift.exchange(0.0);
	double next = surface->presentedSlot + interval;
	if (surface->presentedSlot == 0 || now > next + interval) next = now;  // first frame, or fell behind: rebase
	// Present the last frame before the slot: the one after it would arrive late.
	if (now + surface->frameTime < next) {
		if (PACE_DEBUG) mcm_log("pace skip now=%.4f next=%.4f ft=%.2fms", now, next, surface->frameTime * 1000);
		return false;
	}
	surface->presentedSlot = next;
	if (PACE_DEBUG) mcm_log("pace present now=%.4f next=%.4f ft=%.2fms", now, next, surface->frameTime * 1000);
	return true;
}

// Draws the game's final image (flipped, swizzled to BGRA) straight into the drawable at the end of the frame's
// command buffer; mcm_submit then presents it from the present queue once the frame's event fires, so the render
// queue never waits on the display. Returns 0 when the frame was skipped (no drawable free).
extern "C" int32_t mcm_surface_blit(void *handle, void *ctx_handle, void *texture, int32_t mip, int32_t width, int32_t height) {
	(void)mip;
	McmSurface *surface = (McmSurface *)handle;
	McmContext *ctx = (McmContext *)ctx_handle;
	end_blit(ctx);
	end_render(ctx);
	flush_clears(ctx);
	if (!ctx->presentPipeline || width <= 0 || height <= 0) return 0;
	if (deferred_mode(surface)) return stage_image(surface, ctx, (__bridge id<MTLTexture>)texture, mip, width, height);
	bool paced = !surface->vsync && surface->refreshInterval > 0;
	// The limiter already renders exactly one frame per refresh; every one of them is presented.
	if (!surface->vsync && !surface->limiter && !pace_frame(surface)) return 0;
	@autoreleasepool {
		id<CAMetalDrawable> drawable = take_drawable(surface);
		if (!drawable) {
			if (PACE_DEBUG) mcm_log("pace no drawable");
			return 0;
		}
		std::shared_ptr<std::atomic<uint64_t>> shown = ctx->presentsShown;
		std::shared_ptr<McmSurface::Pacing> pacing = surface->pacing;
		double interval = surface->refreshInterval;
		double issued = CACurrentMediaTime();
		std::shared_ptr<McmFrameStats> stats = ctx->frameStats;
		double frameStart = ctx->frameStart, frameTarget = ctx->frameTarget;
		double refresh = surface->clock->period.load();
		bool synced = surface->vsync;
		[drawable addPresentedHandler:^(id<MTLDrawable> presented) {
			double when = presented.presentedTime;
			if (when <= 0) return;
			shown->fetch_add(1, std::memory_order_relaxed);
			if (frameStart > 0 && when > frameStart) {
				stats->latencyNs.fetch_add((uint64_t)((when - frameStart) * 1e9), std::memory_order_relaxed);
				stats->latencyCount.fetch_add(1, std::memory_order_relaxed);
			}
			if (frameTarget > 0 && refresh > 0) {
				double margin = stats->margin.load();
				double offset = when - frameTarget;
				int late = 0;
				if (synced) {
					// Latched at refresh boundaries: count whole refreshes relative to the usual landing.
					late = stats->land((int)std::lround(offset / refresh));
					if (late == 1) {
						// Just missed its refresh: the previous image showed twice. Aim earlier.
						stats->misses.fetch_add(1, std::memory_order_relaxed);
						margin = std::min(margin + 0.001, refresh);
					} else if (late > 1) {
						// A hitch (loading, GC, window changes) that no margin would have saved: count it, don't adapt.
						stats->misses.fetch_add(1, std::memory_order_relaxed);
					} else if (late < 0) {
						margin = std::max(margin - 0.0005, 0.0005);
					} else {
						margin = std::max(margin - 0.000001 - (margin - 0.0005) * 0.002, 0.0005);
					}
				} else if (std::fabs(offset) < refresh * 1.5) {
					// Flipped immediately: aim to flip just before the refresh so a tear stays at the bottom edge.
					// Late flips nudge the margin up; on-time ones let it creep down (settles at ~3% late).
					late = offset > 0 ? 1 : 0;
					if (late) {
						stats->misses.fetch_add(1, std::memory_order_relaxed);
						margin = std::min(margin + 0.0003, refresh);
					} else {
						margin = std::max(margin - 0.00001 - (margin - 0.0003) * 0.002, 0.0003);
					}
				} else if (offset > 0) {
					late = 2;  // hitch
				}
				static const bool debug = getenv("MCMETAL_LIMIT_DEBUG") != nullptr;
				if (debug) mcm_log("limit shown offset=%.2fms late=%d margin=%.2fms start->target=%.2fms", (when - frameTarget) * 1000, late, margin * 1000, (frameTarget - frameStart) * 1000);
				stats->margin.store(margin);
			}
			if (!paced) return;
			// Phase alignment: an image that waited for the display's next refresh took longer than the fastest
			// present seen; moving the cadence later by that wait makes presents land just before each refresh.
			// (On a display that flips immediately the latency is constant and nothing moves.)
			double latency = when - issued;
			double fastest = std::min(pacing->minLatency.load() + 0.00002, latency);
			pacing->minLatency.store(fastest);
			double wait = latency - fastest;
			double shift = 0.0;
			if (wait > interval * 0.75) shift = -0.001;               // just missed a refresh: back off
			else if (wait > 0.0015) shift = (wait - 0.001) * 0.25;  // waited: approach the refresh gradually
			if (shift != 0.0) pacing->phaseShift.store(pacing->phaseShift.load() + shift);
			if (PACE_DEBUG) mcm_log("pace shown latency=%.2fms wait=%.2fms", latency * 1000, wait * 1000);
		}];

		id<MTLTexture> target = drawable.texture;
		uint32_t size[2] = {(uint32_t)width, (uint32_t)height};
		bool covers = (NSUInteger)width >= target.width && (NSUInteger)height >= target.height;
		MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
		pass.colorAttachments[0].texture = target;
		// Every pixel is overwritten when the image covers the drawable, so nothing needs loading or clearing.
		pass.colorAttachments[0].loadAction = covers ? MTLLoadActionDontCare : MTLLoadActionClear;
		pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
		pass.colorAttachments[0].storeAction = MTLStoreActionStore;
		timing_render(ctx, pass, "present");
		id<MTLRenderCommandEncoder> encoder = [command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
		encoder.label = @"Present";
		[encoder setRenderPipelineState:ctx->presentPipeline];
		[encoder setViewport:(MTLViewport){0.0, 0.0, (double)width, (double)height, 0.0, 1.0}];
		[encoder setFragmentTexture:(__bridge id<MTLTexture>)texture atIndex:0];
		[encoder setFragmentBytes:size length:sizeof(size) atIndex:0];
		[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[encoder endEncoding];
		ctx->pendingDrawable = drawable;
	}
	return 1;
}

// Presents the drawable written by the frame that was just committed as `frame`.
void present_pending(McmContext *ctx, uint64_t frame) {
	if (McmSurface *surface = ctx->stagedSurface) {
		ctx->stagedSurface = nullptr;
		{
			std::lock_guard<std::mutex> guard(surface->lock);
			surface->pendingSlot = ctx->stagedSlot;
			surface->pendingFrame = frame;
			surface->pendingFrameStart = ctx->frameStart;
			McmSurface::Image &image = surface->ring[ctx->stagedSlot];
			surface->pendingWidth = image.texture ? (int32_t)image.texture.width : 0;
			surface->pendingHeight = image.texture ? (int32_t)image.texture.height : 0;
		}
		surface->wake.notify_all();
		return;
	}
	if (!ctx->pendingDrawable) return;
	@autoreleasepool {
		id<CAMetalDrawable> drawable = ctx->pendingDrawable;
		ctx->pendingDrawable = nil;
		id<MTLCommandBuffer> buffer = [ctx->presentQueue commandBufferWithUnretainedReferences];
		[buffer encodeWaitForEvent:ctx->event value:frame];
		[buffer presentDrawable:drawable];
		[buffer commit];
	}
}

extern "C" void mcm_surface_destroy(void *handle) {
	McmSurface *surface = (McmSurface *)handle;
	{
		std::lock_guard<std::mutex> guard(surface->lock);
		surface->stopping = true;
		surface->parked = nil;
		surface->wake.notify_all();
	}
	// nextDrawable times out after at most a second (allowsNextDrawableTimeout), so this join is bounded.
	if (surface->fetcher.joinable()) surface->fetcher.join();
	stop_display_link(surface);
	delete surface;
}
