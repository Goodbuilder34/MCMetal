// Per-encoder GPU timing (MCMETAL_PASS_TIMING=1): timestamps at the start and end of every render pass and blit
// pass, summed per label and logged every few seconds. Encoders on a tile-based GPU overlap a little, so the
// numbers are spans, not exclusive costs, but they show where a frame's GPU time goes.

#include "internal.h"
#include <map>
#include <string>
#include <vector>

namespace {

constexpr NSUInteger SAMPLES = 4096;
constexpr NSUInteger STRIDE = 4;  // start/end of vertex, start/end of fragment (blit/compute: the last two)
constexpr int REPORT_FRAMES = 240;

struct Frame {
	id<MTLCounterSampleBuffer> buffer;
	std::vector<std::string> labels;
};

struct Stat {
	double ns = 0.0;
	uint64_t encoders = 0;
};

struct Timing {
	bool enabled = false;
	bool stageBoundary = false;
	id<MTLCounterSet> timestamps;
	std::mutex lock;
	std::vector<id<MTLCounterSampleBuffer>> free;
	std::unique_ptr<Frame> current;
	std::map<std::string, Stat> stats;
	double frameNs = 0.0;
	int frames = 0;
	// GPU ticks → ns, calibrated from paired CPU/GPU timestamps.
	MTLTimestamp cpu0 = 0, gpu0 = 0;
	double ratio = 1.0;
};

Timing &timing() {
	static Timing t;
	return t;
}

bool ready(McmContext *ctx) {
	static const bool requested = getenv("MCMETAL_PASS_TIMING") != nullptr;
	if (!requested) return false;
	Timing &t = timing();
	static bool initialized = false;
	if (!initialized) {
		initialized = true;
		for (id<MTLCounterSet> set in ctx->device.counterSets) {
			if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) t.timestamps = set;
		}
		t.stageBoundary = [ctx->device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary];
		t.enabled = t.timestamps != nil && t.stageBoundary;
		[ctx->device sampleTimestamps:&t.cpu0 gpuTimestamp:&t.gpu0];
		mcm_log("[timing] pass timing %s", t.enabled ? "on" : "unavailable (no stage-boundary timestamps)");
	}
	return t.enabled;
}

Frame *frame(McmContext *ctx) {
	Timing &t = timing();
	if (!t.current) {
		id<MTLCounterSampleBuffer> buffer = nil;
		{
			std::lock_guard<std::mutex> guard(t.lock);
			if (!t.free.empty()) {
				buffer = t.free.back();
				t.free.pop_back();
			}
		}
		if (!buffer) {
			MTLCounterSampleBufferDescriptor *desc = [MTLCounterSampleBufferDescriptor new];
			desc.counterSet = t.timestamps;
			desc.storageMode = MTLStorageModeShared;
			desc.sampleCount = SAMPLES;
			NSError *error = nil;
			buffer = [ctx->device newCounterSampleBufferWithDescriptor:desc error:&error];
			if (!buffer) {
				mcm_log("[timing] sample buffer failed: %s", error.localizedDescription.UTF8String);
				t.enabled = false;
				return nullptr;
			}
		}
		t.current = std::make_unique<Frame>();
		t.current->buffer = buffer;
	}
	return (t.current->labels.size() + 1) * STRIDE <= SAMPLES ? t.current.get() : nullptr;
}

std::string describe(const char *label, id<MTLTexture> target) {
	std::string key = label && *label ? label : "?";
	if (target) {
		key += " -> ";
		key += target.label ? target.label.UTF8String : "tex";
		key += " " + std::to_string(target.width) + "x" + std::to_string(target.height);
	}
	return key;
}

}  // namespace

void timing_render(McmContext *ctx, MTLRenderPassDescriptor *pass, const char *label) {
	if (!ready(ctx)) return;
	Frame *f = frame(ctx);
	if (!f) return;
	id<MTLTexture> target = pass.colorAttachments[0].texture ?: pass.depthAttachment.texture;
	NSUInteger index = f->labels.size() * STRIDE;
	f->labels.push_back(describe(label, target));
	MTLRenderPassSampleBufferAttachmentDescriptor *a = pass.sampleBufferAttachments[0];
	a.sampleBuffer = f->buffer;
	a.startOfVertexSampleIndex = index;
	a.endOfVertexSampleIndex = index + 1;
	a.startOfFragmentSampleIndex = index + 2;
	a.endOfFragmentSampleIndex = index + 3;
}

id<MTLBlitCommandEncoder> timing_blit_encoder(McmContext *ctx, id<MTLCommandBuffer> buffer) {
	if (ready(ctx)) {
		Frame *f = frame(ctx);
		if (f) {
			MTLBlitPassDescriptor *pass = [MTLBlitPassDescriptor blitPassDescriptor];
			NSUInteger index = f->labels.size() * STRIDE + 2;
			f->labels.push_back("blit");
			pass.sampleBufferAttachments[0].sampleBuffer = f->buffer;
			pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = index;
			pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = index + 1;
			return [buffer blitCommandEncoderWithDescriptor:pass];
		}
	}
	return [buffer blitCommandEncoder];
}

id<MTLComputeCommandEncoder> timing_compute_encoder(McmContext *ctx, id<MTLCommandBuffer> buffer, const char *label) {
	if (ready(ctx)) {
		Frame *f = frame(ctx);
		if (f) {
			MTLComputePassDescriptor *pass = [MTLComputePassDescriptor computePassDescriptor];
			NSUInteger index = f->labels.size() * STRIDE + 2;
			f->labels.push_back(label);
			pass.sampleBufferAttachments[0].sampleBuffer = f->buffer;
			pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = index;
			pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = index + 1;
			return [buffer computeCommandEncoderWithDescriptor:pass];
		}
	}
	return [buffer computeCommandEncoder];
}

void timing_submit(McmContext *ctx, id<MTLCommandBuffer> buffer) {
	Timing &t = timing();
	if (!t.enabled || !t.current) return;
	std::shared_ptr<Frame> f(t.current.release());
	id<MTLDevice> device = ctx->device;
	[buffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
		NSUInteger n = f->labels.size() * STRIDE;
		NSData *data = n > 0 ? [f->buffer resolveCounterRange:NSMakeRange(0, n)] : nil;
		std::lock_guard<std::mutex> guard(t.lock);
		if (data && done.status == MTLCommandBufferStatusCompleted) {
			MTLTimestamp cpu = 0, gpu = 0;
			[device sampleTimestamps:&cpu gpuTimestamp:&gpu];
			if (gpu > t.gpu0 + 1000000 && cpu > t.cpu0) t.ratio = (double)(cpu - t.cpu0) / (double)(gpu - t.gpu0);
			const MTLCounterResultTimestamp *ts = (const MTLCounterResultTimestamp *)data.bytes;
			// Each encoder is charged only for time no earlier-finishing encoder already covered.
			struct Span { uint64_t start, end; size_t label; };
			std::vector<Span> spans;
			auto valid = [](uint64_t v) { return v != MTLCounterErrorValue && v != 0; };
			for (size_t i = 0; i < f->labels.size(); i++) {
				uint64_t start = UINT64_MAX, end = 0;
				for (NSUInteger k = 0; k < STRIDE; k++) {
					uint64_t v = ts[i * STRIDE + k].timestamp;
					if (!valid(v)) continue;
					start = std::min(start, v);
					end = std::max(end, v);
				}
				if (end > start && start != UINT64_MAX) spans.push_back({start, end, i});
			}
			static int dumped = 0;
			if (getenv("MCMETAL_PASS_TIMING_DUMP") && spans.size() > 20 && ++dumped >= atoi(getenv("MCMETAL_PASS_TIMING_DUMP")) && dumped < atoi(getenv("MCMETAL_PASS_TIMING_DUMP")) + 3) {
				std::vector<Span> byStart = spans;
				std::sort(byStart.begin(), byStart.end(), [](const Span &x, const Span &y) { return x.start < y.start; });
				static uint64_t origin = byStart[0].start;
				mcm_log("[timing] dump ==== command buffer %d (%zu encoders) cpu-gpu %.3f ms", dumped, byStart.size(), (done.GPUEndTime - done.GPUStartTime) * 1e3);
				for (const Span &span : byStart) {
					const MTLCounterResultTimestamp *r = ts + span.label * STRIDE;
					auto rel = [&](uint64_t v) { return valid(v) ? ((double)v - (double)origin) * t.ratio / 1e6 : -1.0; };
					mcm_log("[timing] dump %8.3f..%8.3f  v %8.3f..%8.3f f %8.3f..%8.3f  %s", rel(span.start), rel(span.end), rel(r[0].timestamp),
					        rel(r[1].timestamp), rel(r[2].timestamp), rel(r[3].timestamp), f->labels[span.label].c_str());
				}
			}
			std::sort(spans.begin(), spans.end(), [](const Span &x, const Span &y) { return x.end < y.end; });
			uint64_t covered = 0;
			for (const Span &span : spans) {
				uint64_t from = std::max(span.start, covered);
				Stat &st = t.stats[f->labels[span.label]];
				if (span.end > from) st.ns += (double)(span.end - from) * t.ratio;
				st.encoders++;
				covered = std::max(covered, span.end);
			}
			t.frameNs += (done.GPUEndTime - done.GPUStartTime) * 1e9;
			if (++t.frames >= REPORT_FRAMES) {
				std::vector<std::pair<std::string, Stat>> sorted(t.stats.begin(), t.stats.end());
				std::sort(sorted.begin(), sorted.end(), [](auto &x, auto &y) { return x.second.ns > y.second.ns; });
				double total = 0.0;
				for (auto &e : sorted) total += e.second.ns;
				mcm_log("[timing] ---- %d frames: command buffer %.2f ms/frame, busy %.2f ms/frame", t.frames, t.frameNs / t.frames / 1e6,
				        total / t.frames / 1e6);
				int shown = 0;
				for (auto &e : sorted) {
					if (shown++ >= 40) break;
					mcm_log("[timing] %7.3f ms  x%5.1f  %s", e.second.ns / t.frames / 1e6, (double)e.second.encoders / t.frames, e.first.c_str());
				}
				t.stats.clear();
				t.frameNs = 0.0;
				t.frames = 0;
			}
		}
		// Not reused: encoders that sample nothing (e.g. a clear with no draws) would leave stale timestamps.
	}];
}
