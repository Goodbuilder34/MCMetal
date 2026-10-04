// Shader layer: screen-space lighting and post-processing written directly against Metal.
//
// Two entry points per frame, both called from the render thread between the game's own passes:
//   mcm_fx_world  after the level is drawn, before the hand/HUD: ambient occlusion, sun shading with screen-space
//                 contact shadows, aerial haze and god rays, applied to the main color target in place.
//   mcm_fx_final  after the game's post effects, before the GUI: bloom, tone mapping, color grading, vignette.
//
// Cost model (Apple TBDR): every depth-derived effect runs at half resolution in one serial compute encoder, from a
// half-res copy of the depth buffer that stays in cache; full-resolution work is limited to one fragment pass per
// stage, which reads and writes the color target through framebuffer fetch (no copy, no extra full-res target).

#include "internal.h"

enum {
	FX_AO = 1 << 0,
	FX_SHADOWS = 1 << 1,
	FX_RAYS = 1 << 2,
	FX_HAZE = 1 << 3,
	FX_BLOOM = 1 << 4,
	FX_TONEMAP = 1 << 5,
};

// Must match FxParams in the shader and ShaderLayer.java (which writes everything except `size`).
struct FxParams {
	float invProj[16];
	float proj[16];
	float light[4];       // xyz: view-space direction to the sun/moon, w: sun shading strength (0 = off)
	float lightColor[4];  // rgb: linear light color, w: god ray strength
	float fogColor[4];    // rgb: linear fog color, w: haze density per block
	float ao[4];          // x: AO strength, y: radius (blocks), z: sample count, w: contact shadow length (blocks)
	float grade[4];       // x: bloom strength, y: exposure, z: saturation, w: vignette
	float misc[4];        // x: frame index, y: tone map strength, z: shadow tint strength, w: unused
	float size[4];        // full-res width, height, 1/width, 1/height (filled here)
};
static_assert(sizeof(FxParams) == 240, "FxParams layout");
#define FX_PARAMS_JAVA_BYTES 224

static const int BLOOM_LEVELS = 6;

static const char *FX_SHADERS = R"METAL(
#include <metal_stdlib>
using namespace metal;

struct FxParams {
	float4x4 invProj;
	float4x4 proj;
	float4 light;
	float4 lightColor;
	float4 fogColor;
	float4 ao;
	float4 grade;
	float4 misc;
	float4 size;
};

// The game renders with Vulkan's conventions: texel row 0 is NDC y = -1, depth is reversed (1 = near, 0 = far/sky).
static float3 view_pos(constant FxParams &P, float2 uv, float z) {
	float4 v = P.invProj * float4(uv * 2.0 - 1.0, z, 1.0);
	return v.xyz / v.w;
}

// Positive distance along the view axis; only the z and w rows of the inverse projection are needed.
static float view_depth(constant FxParams &P, float2 uv, float z) {
	float4 c = float4(uv * 2.0 - 1.0, z, 1.0);
	float vz = dot(float4(P.invProj[0].z, P.invProj[1].z, P.invProj[2].z, P.invProj[3].z), c);
	float vw = dot(float4(P.invProj[0].w, P.invProj[1].w, P.invProj[2].w, P.invProj[3].w), c);
	return -vz / vw;
}

static float ign(float2 p) {
	return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
}

static float luminance(float3 c) {
	return dot(c, float3(0.2126, 0.7152, 0.0722));
}

// ---------------------------------------------------------------------------------------------
// Half-resolution depth. Alternating nearest/farthest of each 2x2 block keeps both thin foreground
// detail and background in the downsampled buffer.
// ---------------------------------------------------------------------------------------------
kernel void fx_prepare(depth2d<float, access::read> depth [[texture(0)]],
                       texture2d<float, access::write> out [[texture(1)]],
                       uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
	uint2 maxp = uint2(depth.get_width() - 1, depth.get_height() - 1);
	uint2 base = gid * 2;
	float a = depth.read(min(base, maxp));
	float b = depth.read(min(base + uint2(1, 0), maxp));
	float c = depth.read(min(base + uint2(0, 1), maxp));
	float d = depth.read(min(base + uint2(1, 1), maxp));
	bool nearest = ((gid.x ^ gid.y) & 1) == 0;
	float z = nearest ? max(max(a, b), max(c, d)) : min(min(a, b), min(c, d));
	out.write(float4(z), gid);
}

// ---------------------------------------------------------------------------------------------
// Ambient occlusion (scalable AO over a spiral of view-space samples) and sun visibility
// (N.L times a short screen-space shadow ray), both at half resolution. Output: r = AO, g = sunlight.
// ---------------------------------------------------------------------------------------------
kernel void fx_ao(texture2d<float, access::read> zt [[texture(0)]],
                  texture2d<half, access::write> out [[texture(1)]],
                  constant FxParams &P [[buffer(0)]],
                  constant uint &flags [[buffer(1)]],
                  uint2 gid [[thread_position_in_grid]]) {
	int2 sz = int2(zt.get_width(), zt.get_height());
	if (int(gid.x) >= sz.x || int(gid.y) >= sz.y) return;
	float2 inv = 1.0 / float2(sz);
	float z = zt.read(gid).r;
	if (z <= 0.0) {
		out.write(half4(1.0, 1.0, 0.0, 0.0), gid);
		return;
	}
	float2 uv = (float2(gid) + 0.5) * inv;
	float3 pos = view_pos(P, uv, z);
	float dist = -pos.z;

	// Normal from the neighbors on whichever side is continuous with this pixel.
	int2 g = int2(gid);
	int2 pl = int2(max(g.x - 1, 0), g.y), pr = int2(min(g.x + 1, sz.x - 1), g.y);
	int2 pd = int2(g.x, max(g.y - 1, 0)), pu = int2(g.x, min(g.y + 1, sz.y - 1));
	float3 vl = view_pos(P, (float2(pl) + 0.5) * inv, zt.read(uint2(pl)).r);
	float3 vr = view_pos(P, (float2(pr) + 0.5) * inv, zt.read(uint2(pr)).r);
	float3 vd = view_pos(P, (float2(pd) + 0.5) * inv, zt.read(uint2(pd)).r);
	float3 vu = view_pos(P, (float2(pu) + 0.5) * inv, zt.read(uint2(pu)).r);
	float3 dx = abs(pos.z - vl.z) < abs(vr.z - pos.z) ? pos - vl : vr - pos;
	float3 dy = abs(pos.z - vd.z) < abs(vu.z - pos.z) ? pos - vd : vu - pos;
	float3 n = normalize(cross(dx, dy));
	if (dot(n, pos) > 0.0) n = -n;

	float noise = ign(float2(gid));
	float ao = 1.0;
	if (flags & 1u) {
		float radius = P.ao.y;
		float pixelsPerUnit = P.proj[1][1] * 0.5 * float(sz.y);
		float screenRadius = min(radius * pixelsPerUnit / dist, 40.0);
		if (screenRadius > 1.0) {
			int count = int(P.ao.z);
			float invR2 = 1.0 / (radius * radius);
			float angle = noise * 6.2831853;
			float sum = 0.0;
			for (int i = 0; i < count; i++) {
				float t = (float(i) + 0.5) / float(count);
				float a = angle + t * 43.9822971;  // 7 turns
				float2 offset = float2(cos(a), sin(a)) * (screenRadius * t);
				int2 sp = clamp(g + int2(round(offset)), int2(0), sz - 1);
				float sz2 = zt.read(uint2(sp)).r;
				float3 v = view_pos(P, (float2(sp) + 0.5) * inv, sz2) - pos;
				float vv = dot(v, v);
				float falloff = saturate(1.0 - vv * invR2);
				sum += falloff * max(dot(v, n) * rsqrt(vv + 1e-4) - 0.1, 0.0);
			}
			ao = saturate(1.0 - 2.0 * sum / float(count));
		}
	}

	float sun = 1.0;
	if (flags & 2u) {
		float3 L = P.light.xyz;
		float ndl = dot(n, L);
		sun = saturate(ndl * 3.0);  // wrapped: faces turn dark only well past the terminator
		if (sun > 0.0) {
			const int STEPS = 12;
			float stepLength = P.ao.w / float(STEPS);
			float3 origin = pos + n * (0.02 + dist * 0.002);
			float visibility = 1.0;
			for (int s = 0; s < STEPS; s++) {
				float3 rp = origin + L * ((float(s) + noise) * stepLength);
				float4 clip = P.proj * float4(rp, 1.0);
				if (clip.w <= 0.0) break;
				float2 suv = clip.xy / clip.w * 0.5 + 0.5;
				if (any(suv <= 0.0) || any(suv >= 1.0)) break;
				int2 sp = int2(suv * float2(sz));
				float sceneZ = zt.read(uint2(sp)).r;
				if (sceneZ <= 0.0) continue;
				float behind = -rp.z - view_depth(P, (float2(sp) + 0.5) * inv, sceneZ);
				float thickness = 0.35 + 0.01 * dist;
				if (behind > 0.03 && behind < thickness) {
					// Fade hits near the end of the ray so the shadow has no hard cut-off.
					visibility = float(s) / float(STEPS) * 0.4;
					break;
				}
			}
			sun *= visibility;
		}
	}
	out.write(half4(half(ao), half(sun), 0.0, 0.0), gid);
}

// Depth-aware separable blur of the AO/sun buffer (7 taps at half resolution).
kernel void fx_blur(texture2d<half, access::read> src [[texture(0)]],
                    texture2d<half, access::write> dst [[texture(1)]],
                    texture2d<float, access::read> zt [[texture(2)]],
                    constant FxParams &P [[buffer(0)]],
                    constant int2 &dir [[buffer(1)]],
                    uint2 gid [[thread_position_in_grid]]) {
	int2 sz = int2(zt.get_width(), zt.get_height());
	if (int(gid.x) >= sz.x || int(gid.y) >= sz.y) return;
	float2 inv = 1.0 / float2(sz);
	float z0 = zt.read(gid).r;
	if (z0 <= 0.0) {
		dst.write(half4(1.0, 1.0, 0.0, 0.0), gid);
		return;
	}
	float d0 = view_depth(P, (float2(gid) + 0.5) * inv, z0);
	float sharp = 1.0 / (d0 * 0.03 + 0.05);
	const float weights[4] = {0.24, 0.20, 0.12, 0.06};
	half2 sum = 0.0;
	float total = 0.0;
	for (int k = -3; k <= 3; k++) {
		int2 p = clamp(int2(gid) + dir * k, int2(0), sz - 1);
		float z = zt.read(uint2(p)).r;
		float d = z > 0.0 ? view_depth(P, (float2(p) + 0.5) * inv, z) : 1e6;
		float w = weights[abs(k)] * exp2(-abs(d - d0) * sharp);
		sum += src.read(uint2(p)).rg * half(w);
		total += w;
	}
	dst.write(half4(sum / half(total), 0.0, 0.0), gid);
}

// God rays at quarter resolution: the fraction of open sky on the way from each pixel to the sun.
kernel void fx_rays(texture2d<float, access::read> zt [[texture(0)]],
                    texture2d<half, access::write> out [[texture(1)]],
                    constant FxParams &P [[buffer(0)]],
                    uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
	float4 sunClip = P.proj * float4(P.light.xyz, 0.0);
	if (sunClip.w <= 0.0) {
		out.write(half4(0.0), gid);
		return;
	}
	float2 sunUV = sunClip.xy / sunClip.w * 0.5 + 0.5;
	float2 uv = (float2(gid) + 0.5) / float2(out.get_width(), out.get_height());
	float2 delta = sunUV - uv;
	float len = length(delta);
	const int STEPS = 32;
	float2 stepv = delta * (min(len, 0.6) / max(len, 1e-5)) / float(STEPS);
	float2 zsize = float2(zt.get_width(), zt.get_height());
	float2 p = uv + stepv * ign(float2(gid));
	float acc = 0.0, total = 0.0, decay = 1.0;
	for (int i = 0; i < STEPS; i++) {
		float sky = 0.0;
		if (all(p > 0.0) && all(p < 1.0)) sky = zt.read(uint2(p * zsize)).r <= 0.0 ? 1.0 : 0.0;
		acc += sky * decay;
		total += decay;
		decay *= 0.95;
		p += stepv;
	}
	out.write(half4(half(acc / total)), gid);
}

// ---------------------------------------------------------------------------------------------
// Bloom: soft-threshold prefilter to half resolution, 13-tap downsample chain, tent upsample chain.
// ---------------------------------------------------------------------------------------------
kernel void fx_bloom_prefilter(texture2d<half, access::sample> color [[texture(0)]],
                               texture2d<half, access::write> out [[texture(1)]],
                               sampler s [[sampler(0)]],
                               uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
	float2 texel = 1.0 / float2(color.get_width(), color.get_height());
	float2 uv = (float2(gid) * 2.0 + 1.0) * texel;
	float3 result = 0.0;
	float total = 0.0;
	// Four bilinear taps cover the 4x4 block; Karis weighting keeps single bright pixels from flickering.
	for (int i = 0; i < 4; i++) {
		float2 o = float2((i & 1) ? 1.0 : -1.0, (i & 2) ? 1.0 : -1.0) * texel;
		float3 c = float3(color.sample(s, uv + o).rgb);
		c *= c;
		float l = luminance(c);
		float knee = saturate((l - 0.45) / 0.4);
		float w = 1.0 / (1.0 + l);
		result += c * (knee * knee) * w;
		total += w;
	}
	out.write(half4(half3(result / total), 1.0), gid);
}

kernel void fx_bloom_down(texture2d<half, access::sample> src [[texture(0)]],
                          texture2d<half, access::write> dst [[texture(1)]],
                          sampler s [[sampler(0)]],
                          uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
	float2 t = 1.0 / float2(src.get_width(), src.get_height());
	float2 uv = (float2(gid) + 0.5) / float2(dst.get_width(), dst.get_height());
	half3 a = src.sample(s, uv + t * float2(-2, -2)).rgb, b = src.sample(s, uv + t * float2(0, -2)).rgb, c = src.sample(s, uv + t * float2(2, -2)).rgb;
	half3 d = src.sample(s, uv + t * float2(-2, 0)).rgb, e = src.sample(s, uv).rgb, f = src.sample(s, uv + t * float2(2, 0)).rgb;
	half3 g = src.sample(s, uv + t * float2(-2, 2)).rgb, h = src.sample(s, uv + t * float2(0, 2)).rgb, i = src.sample(s, uv + t * float2(2, 2)).rgb;
	half3 j = src.sample(s, uv + t * float2(-1, -1)).rgb, k = src.sample(s, uv + t * float2(1, -1)).rgb;
	half3 l = src.sample(s, uv + t * float2(-1, 1)).rgb, m = src.sample(s, uv + t * float2(1, 1)).rgb;
	half3 r = e * 0.125h + (a + c + g + i) * 0.03125h + (b + d + f + h) * 0.0625h + (j + k + l + m) * 0.125h;
	dst.write(half4(r, 1.0), gid);
}

kernel void fx_bloom_up(texture2d<half, access::sample> low [[texture(0)]],
                        texture2d<half, access::read> high [[texture(1)]],
                        texture2d<half, access::write> dst [[texture(2)]],
                        sampler s [[sampler(0)]],
                        uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
	float2 t = 1.0 / float2(low.get_width(), low.get_height());
	float2 uv = (float2(gid) + 0.5) / float2(dst.get_width(), dst.get_height());
	half3 r = low.sample(s, uv).rgb * 4.0h;
	r += (low.sample(s, uv + t * float2(-1, 0)).rgb + low.sample(s, uv + t * float2(1, 0)).rgb +
	      low.sample(s, uv + t * float2(0, -1)).rgb + low.sample(s, uv + t * float2(0, 1)).rgb) * 2.0h;
	r += low.sample(s, uv + t * float2(-1, -1)).rgb + low.sample(s, uv + t * float2(1, -1)).rgb +
	     low.sample(s, uv + t * float2(-1, 1)).rgb + low.sample(s, uv + t * float2(1, 1)).rgb;
	dst.write(half4(high.read(gid).rgb + r * (1.0h / 16.0h), 1.0), gid);
}

// ---------------------------------------------------------------------------------------------
// Full-screen composites. Both read the color target through framebuffer fetch and write it back in place.
// ---------------------------------------------------------------------------------------------
struct FullscreenOut { float4 position [[position]]; };

vertex FullscreenOut fx_fullscreen_vs(uint vid [[vertex_id]]) {
	float2 uv = float2((vid << 1) & 2, vid & 2);
	FullscreenOut out;
	out.position = float4(uv * 2.0 - 1.0, 0.0, 1.0);
	return out;
}

fragment half4 fx_world_fs(FullscreenOut in [[stage_in]],
                           half4 dst [[color(0)]],
                           depth2d<float, access::read> depth [[texture(0)]],
                           texture2d<half, access::sample> shade [[texture(1)]],
                           texture2d<float, access::read> zt [[texture(2)]],
                           texture2d<half, access::sample> rays [[texture(3)]],
                           sampler s [[sampler(0)]],
                           constant FxParams &P [[buffer(0)]],
                           constant uint &flags [[buffer(1)]]) {
	uint2 p = uint2(in.position.xy);
	float2 uv = in.position.xy * P.size.zw;
	float3 c = float3(dst.rgb);
	float3 lin = c * c;
	float z = depth.read(p);
	float3 L = P.light.xyz;

	if (z > 0.0) {
		float3 pos = view_pos(P, uv, z);
		float dist = -pos.z;

		if (flags & 3u) {
			// Joint bilateral upsample: bilinear weights, rejecting half-res texels at a different depth.
			int2 hs = int2(zt.get_width(), zt.get_height());
			float2 hinv = 1.0 / float2(hs);
			float2 hp = uv * float2(hs) - 0.5;
			int2 b = int2(floor(hp));
			float2 f = hp - float2(b);
			float sharp = 1.0 / (dist * 0.02 + 0.03);
			float2 sum = 0.0;
			float total = 0.0;
			for (int i = 0; i < 4; i++) {
				int2 o = int2(i & 1, i >> 1);
				int2 q = clamp(b + o, int2(0), hs - 1);
				float qz = zt.read(uint2(q)).r;
				float qd = qz > 0.0 ? view_depth(P, (float2(q) + 0.5) * hinv, qz) : 1e6;
				float w = (o.x ? f.x : 1.0 - f.x) * (o.y ? f.y : 1.0 - f.y);
				w = w * exp2(-abs(qd - dist) * sharp) + 1e-5;
				sum += float2(shade.sample(s, (float2(q) + 0.5) * hinv).rg) * w;
				total += w;
			}
			float2 st = sum / total;
			if (flags & 1u) lin *= pow(st.x, P.ao.x);
			if (flags & 2u) {
				// Sunlit surfaces warm slightly, the rest falls into a cooler, darker ambient.
				float s = P.light.w;
				float t = P.misc.z * s;
				float3 lc = P.lightColor.rgb / max(max(P.lightColor.r, P.lightColor.g), max(P.lightColor.b, 1e-4));
				float3 warm = mix(float3(1.0), lc * 1.1, 0.3 * t);
				float3 cool = mix(float3(1.0), float3(0.86, 0.93, 1.08), t);
				lin *= mix(cool * (1.0 - s), warm, st.y);
			}
		}

		if (flags & 8u) {
			// Aerial perspective: distant terrain picks up fog color, brightened toward the sun (Mie-like lobe).
			float amount = (1.0 - exp(-dist * P.fogColor.w)) * 0.6;
			float cosTheta = dot(normalize(pos), L);
			float lobe = pow(saturate(cosTheta), 8.0);
			float3 inscatter = P.fogColor.rgb + P.lightColor.rgb * lobe * 0.35 * P.light.w;
			lin = mix(lin, inscatter, amount);
		}
	}

	if (flags & 4u) {
		float3 dir = normalize(view_pos(P, uv, 0.5));
		float facing = saturate(dot(dir, L));
		float r = float(rays.sample(s, uv).r);
		lin += P.lightColor.rgb * (r * r * pow(facing, 6.0) * P.lightColor.w);
	}

	return half4(half3(sqrt(max(lin, 0.0))), dst.a);
}

// ACES fit (Narkowicz), applied to an LDR image after expanding its highlights back toward HDR.
static float3 aces(float3 x) {
	return saturate((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14));
}

fragment half4 fx_final_fs(FullscreenOut in [[stage_in]],
                           half4 dst [[color(0)]],
                           texture2d<half, access::sample> bloom [[texture(0)]],
                           sampler s [[sampler(0)]],
                           constant FxParams &P [[buffer(0)]],
                           constant uint &flags [[buffer(1)]]) {
	float2 uv = in.position.xy * P.size.zw;
	float3 c = float3(dst.rgb);
	float3 lin = c * c;
	if (flags & 16u) lin += float3(bloom.sample(s, uv).rgb) * (P.grade.x / 6.0);
	if (flags & 32u) {
		float3 hdr = lin / max(1.0 - 0.75 * min(lin, 1.0), 0.25);
		float3 mapped = aces(hdr * (0.7 * P.grade.y));
		lin = mix(lin, mapped, P.misc.y);
	}
	float l = luminance(lin);
	lin = max(mix(float3(l), lin, P.grade.z), 0.0);
	float2 v = uv * 2.0 - 1.0;
	lin *= 1.0 - P.grade.w * smoothstep(0.4, 1.6, dot(v, v));
	float3 outc = sqrt(lin);
	// Triangular dither breaks up banding in the 8-bit target.
	float n = ign(in.position.xy) + ign(in.position.xy + 17.0) - 1.0;
	outc += n / 255.0;
	return half4(half3(outc), dst.a);
}
)METAL";

struct McmFx {
	std::atomic<bool> ready{false};
	std::atomic<bool> failed{false};
	id<MTLLibrary> library;
	id<MTLComputePipelineState> prepare, ao, blur, rays, bloomPrefilter, bloomDown, bloomUp;
	NSMutableDictionary<NSString *, id<MTLRenderPipelineState>> *composites;
	id<MTLSamplerState> linear;

	int32_t width = 0, height = 0;
	id<MTLTexture> zHalf, shadeA, shadeB, raysTex;
	id<MTLTexture> bloomDownTex[BLOOM_LEVELS];
	id<MTLTexture> bloomUpTex[BLOOM_LEVELS];
	bool loggedReady = false;
};

static id<MTLComputePipelineState> compute_pipeline(id<MTLDevice> device, id<MTLLibrary> library, NSString *name) {
	NSError *error = nil;
	id<MTLFunction> fn = [library newFunctionWithName:name];
	id<MTLComputePipelineState> state = fn ? [device newComputePipelineStateWithFunction:fn error:&error] : nil;
	if (!state) mcm_log("shader layer: compute pipeline %s failed: %s", name.UTF8String, error ? error.localizedDescription.UTF8String : "missing function");
	return state;
}

static id<MTLRenderPipelineState> composite_pipeline(McmContext *ctx, McmFx *fx, NSString *fragment, MTLPixelFormat format) {
	NSString *key = [NSString stringWithFormat:@"%@-%lu", fragment, (unsigned long)format];
	@synchronized(fx->composites) {
		id<MTLRenderPipelineState> state = fx->composites[key];
		if (state) return state;
	}
	MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
	desc.label = fragment;
	desc.vertexFunction = [fx->library newFunctionWithName:@"fx_fullscreen_vs"];
	desc.fragmentFunction = [fx->library newFunctionWithName:fragment];
	desc.colorAttachments[0].pixelFormat = format;
	NSError *error = nil;
	id<MTLRenderPipelineState> state = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
	if (!state) {
		mcm_log("shader layer: composite %s failed: %s", fragment.UTF8String, error.localizedDescription.UTF8String);
		return nil;
	}
	@synchronized(fx->composites) {
		fx->composites[key] = state;
	}
	return state;
}

// Compiles the shader library off the render thread; the effects simply stay off until it is ready.
static void fx_start(McmContext *ctx) {
	McmFx *fx = new McmFx();
	fx->composites = [NSMutableDictionary new];
	MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
	sd.minFilter = MTLSamplerMinMagFilterLinear;
	sd.magFilter = MTLSamplerMinMagFilterLinear;
	sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
	sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
	fx->linear = [ctx->device newSamplerStateWithDescriptor:sd];
	ctx->fx = fx;

	MTLCompileOptions *options = [MTLCompileOptions new];
	options.mathMode = MTLMathModeFast;
	id<MTLDevice> device = ctx->device;
	McmContext *c = ctx;
	[device newLibraryWithSource:[NSString stringWithUTF8String:FX_SHADERS] options:options completionHandler:^(id<MTLLibrary> library, NSError *error) {
		if (!library) {
			mcm_log("shader layer: library failed to compile: %s", error.localizedDescription.UTF8String);
			fx->failed = true;
			return;
		}
		fx->library = library;
		fx->prepare = compute_pipeline(device, library, @"fx_prepare");
		fx->ao = compute_pipeline(device, library, @"fx_ao");
		fx->blur = compute_pipeline(device, library, @"fx_blur");
		fx->rays = compute_pipeline(device, library, @"fx_rays");
		fx->bloomPrefilter = compute_pipeline(device, library, @"fx_bloom_prefilter");
		fx->bloomDown = compute_pipeline(device, library, @"fx_bloom_down");
		fx->bloomUp = compute_pipeline(device, library, @"fx_bloom_up");
		// The main target is RGBA8; anything else is built on first use.
		bool composites = composite_pipeline(c, fx, @"fx_world_fs", MTLPixelFormatRGBA8Unorm) && composite_pipeline(c, fx, @"fx_final_fs", MTLPixelFormatRGBA8Unorm);
		bool ok = fx->prepare && fx->ao && fx->blur && fx->rays && fx->bloomPrefilter && fx->bloomDown && fx->bloomUp && composites;
		if (ok) fx->ready = true;
		else fx->failed = true;
	}];
}

void fx_destroy(McmContext *ctx) {
	if (!ctx->fx) return;
	// The compile callback writes into the McmFx; never free it underneath a compile that is still running.
	for (int i = 0; i < 400 && !ctx->fx->ready && !ctx->fx->failed; i++) std::this_thread::sleep_for(std::chrono::milliseconds(5));
	delete ctx->fx;
	ctx->fx = nullptr;
}

static id<MTLTexture> fx_texture(McmContext *ctx, MTLPixelFormat format, int32_t width, int32_t height, NSString *label) {
	MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
	                                                                                width:(NSUInteger)std::max(width, 1)
	                                                                               height:(NSUInteger)std::max(height, 1)
	                                                                            mipmapped:NO];
	desc.storageMode = MTLStorageModePrivate;
	desc.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	id<MTLTexture> texture = [ctx->device newTextureWithDescriptor:desc];
	texture.label = label;
	return texture;
}

// (Re)creates the intermediate targets for a new screen size. The old ones may still be in use by frames in flight;
// the current command buffer's completion handler keeps them alive until every earlier frame has finished too.
static void fx_resize(McmContext *ctx, McmFx *fx, int32_t width, int32_t height) {
	if (fx->width == width && fx->height == height && fx->zHalf) return;
	NSMutableArray *old = [NSMutableArray new];
	if (fx->zHalf) [old addObjectsFromArray:@[fx->zHalf, fx->shadeA, fx->shadeB, fx->raysTex]];
	for (int i = 0; i < BLOOM_LEVELS; i++) {
		if (fx->bloomDownTex[i]) [old addObject:fx->bloomDownTex[i]];
		if (fx->bloomUpTex[i]) [old addObject:fx->bloomUpTex[i]];
	}
	if (old.count > 0) {
		[command_buffer(ctx) addCompletedHandler:^(id<MTLCommandBuffer>) {
			(void)old;
		}];
	}
	fx->width = width;
	fx->height = height;
	int32_t hw = (width + 1) / 2, hh = (height + 1) / 2;
	fx->zHalf = fx_texture(ctx, MTLPixelFormatR32Float, hw, hh, @"FX half depth");
	fx->shadeA = fx_texture(ctx, MTLPixelFormatRG16Float, hw, hh, @"FX AO/sun");
	fx->shadeB = fx_texture(ctx, MTLPixelFormatRG16Float, hw, hh, @"FX AO/sun blurred");
	fx->raysTex = fx_texture(ctx, MTLPixelFormatR16Float, (hw + 1) / 2, (hh + 1) / 2, @"FX god rays");
	int32_t bw = hw, bh = hh;
	for (int i = 0; i < BLOOM_LEVELS; i++) {
		fx->bloomDownTex[i] = fx_texture(ctx, MTLPixelFormatRGBA16Float, bw, bh, @"FX bloom down");
		fx->bloomUpTex[i] = i < BLOOM_LEVELS - 1 ? fx_texture(ctx, MTLPixelFormatRGBA16Float, bw, bh, @"FX bloom up") : nil;
		bw = std::max(1, (bw + 1) / 2);
		bh = std::max(1, (bh + 1) / 2);
	}
}

static McmFx *fx_get(McmContext *ctx) {
	if (!ctx->fx) fx_start(ctx);
	McmFx *fx = ctx->fx;
	if (!fx->ready.load(std::memory_order_acquire)) return nullptr;
	if (!fx->loggedReady) {
		fx->loggedReady = true;
		mcm_log("shader layer ready");
	}
	return fx;
}

static void dispatch(id<MTLComputeCommandEncoder> encoder, id<MTLComputePipelineState> state, NSUInteger width, NSUInteger height) {
	[encoder setComputePipelineState:state];
	[encoder dispatchThreads:MTLSizeMake(width, height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
}

// Encodes a composite as its own render pass on the color target, then leaves the encoder open the way a finished game
// pass would be, so that a following pass on the same target continues in tile memory instead of reloading it.
static void composite(McmContext *ctx, McmFx *fx, id<MTLTexture> color, NSString *fragment, void (^bind)(id<MTLRenderCommandEncoder>)) {
	id<MTLRenderPipelineState> state = composite_pipeline(ctx, fx, fragment, color.pixelFormat);
	if (!state) return;
	MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
	pass.colorAttachments[0].texture = color;
	pass.colorAttachments[0].loadAction = MTLLoadActionLoad;
	pass.colorAttachments[0].storeAction = MTLStoreActionStore;
	timing_render(ctx, pass, fragment.UTF8String);
	id<MTLRenderCommandEncoder> encoder = [command_buffer(ctx) renderCommandEncoderWithDescriptor:pass];
	encoder.label = fragment;
	[encoder setViewport:(MTLViewport){0.0, 0.0, (double)color.width, (double)color.height, 0.0, 1.0}];
	[encoder setFrontFacingWinding:MTLWindingClockwise];
	[encoder setRenderPipelineState:state];
	bind(encoder);
	[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];

	ctx->render = encoder;
	ctx->lingering = true;
	ctx->passHasDepth = false;
	ctx->passWidth = (int32_t)color.width;
	ctx->passHeight = (int32_t)color.height;
	ctx->passColorCount = 1;
	ctx->passColors[0] = color;
	ctx->passColorLevels[0] = 0;
	ctx->passDepth = nil;
	ctx->passDepthLevel = 0;
	ctx->pipeline = nullptr;
	ctx->pipelineUsable = false;
	ctx->boundState = nil;
	ctx->boundDepthState = nil;
	ctx->boundCull = -1;
	ctx->boundFill = -1;
	ctx->boundBiasConstant = NAN;
	ctx->boundBiasSlope = NAN;
	ctx->indexBuffer = nil;
}

static void begin_fx(McmContext *ctx) {
	end_blit(ctx);
	end_render(ctx);
	flush_clears(ctx);
}

static FxParams load_params(const float *data, id<MTLTexture> color) {
	FxParams params;
	memcpy(&params, data, FX_PARAMS_JAVA_BYTES);
	params.size[0] = (float)color.width;
	params.size[1] = (float)color.height;
	params.size[2] = 1.0f / (float)color.width;
	params.size[3] = 1.0f / (float)color.height;
	return params;
}

extern "C" int32_t mcm_fx_world(void *handle, void *color_handle, void *depth_handle, const float *data, int32_t flags) {
	McmContext *ctx = (McmContext *)handle;
	McmFx *fx = fx_get(ctx);
	uint32_t f = (uint32_t)flags & (FX_AO | FX_SHADOWS | FX_RAYS | FX_HAZE);
	if (!fx || !color_handle || !depth_handle || f == 0) return 0;
	id<MTLTexture> color = (__bridge id<MTLTexture>)color_handle;
	id<MTLTexture> depth = (__bridge id<MTLTexture>)depth_handle;
	if (color.parentTexture) color = color.parentTexture;
	if (depth.parentTexture) depth = depth.parentTexture;
	if (depth.width != color.width || depth.height != color.height || depth.pixelFormat != MTLPixelFormatDepth32Float) return 0;

	begin_fx(ctx);
	@autoreleasepool {
		fx_resize(ctx, fx, (int32_t)color.width, (int32_t)color.height);
		FxParams params = load_params(data, color);
		NSUInteger hw = fx->zHalf.width, hh = fx->zHalf.height;

		id<MTLComputeCommandEncoder> compute = timing_compute_encoder(ctx, command_buffer(ctx), "fx compute");
		compute.label = @"Shader layer: world";
		[compute setBytes:&params length:sizeof(params) atIndex:0];
		[compute setBytes:&f length:sizeof(f) atIndex:1];
		[compute setTexture:depth atIndex:0];
		[compute setTexture:fx->zHalf atIndex:1];
		dispatch(compute, fx->prepare, hw, hh);

		if (f & (FX_AO | FX_SHADOWS)) {
			[compute setTexture:fx->zHalf atIndex:0];
			[compute setTexture:fx->shadeA atIndex:1];
			dispatch(compute, fx->ao, hw, hh);
			int32_t horizontal[2] = {1, 0}, vertical[2] = {0, 1};
			[compute setTexture:fx->shadeA atIndex:0];
			[compute setTexture:fx->shadeB atIndex:1];
			[compute setTexture:fx->zHalf atIndex:2];
			[compute setBytes:horizontal length:sizeof(horizontal) atIndex:1];
			dispatch(compute, fx->blur, hw, hh);
			[compute setTexture:fx->shadeB atIndex:0];
			[compute setTexture:fx->shadeA atIndex:1];
			[compute setBytes:vertical length:sizeof(vertical) atIndex:1];
			dispatch(compute, fx->blur, hw, hh);
		}
		if (f & FX_RAYS) {
			[compute setTexture:fx->zHalf atIndex:0];
			[compute setTexture:fx->raysTex atIndex:1];
			dispatch(compute, fx->rays, fx->raysTex.width, fx->raysTex.height);
		}
		[compute endEncoding];

		composite(ctx, fx, color, @"fx_world_fs", ^(id<MTLRenderCommandEncoder> encoder) {
			[encoder setFragmentBytes:&params length:sizeof(params) atIndex:0];
			[encoder setFragmentBytes:&f length:sizeof(f) atIndex:1];
			[encoder setFragmentTexture:depth atIndex:0];
			[encoder setFragmentTexture:fx->shadeA atIndex:1];
			[encoder setFragmentTexture:fx->zHalf atIndex:2];
			[encoder setFragmentTexture:fx->raysTex atIndex:3];
			[encoder setFragmentSamplerState:fx->linear atIndex:0];
		});
	}
	return 1;
}

extern "C" int32_t mcm_fx_final(void *handle, void *color_handle, const float *data, int32_t flags) {
	McmContext *ctx = (McmContext *)handle;
	McmFx *fx = fx_get(ctx);
	uint32_t f = (uint32_t)flags & (FX_BLOOM | FX_TONEMAP);
	if (!fx || !color_handle) return 0;
	id<MTLTexture> color = (__bridge id<MTLTexture>)color_handle;
	if (color.parentTexture) color = color.parentTexture;

	begin_fx(ctx);
	@autoreleasepool {
		fx_resize(ctx, fx, (int32_t)color.width, (int32_t)color.height);
		FxParams params = load_params(data, color);
		if (f & FX_BLOOM) {
			id<MTLComputeCommandEncoder> compute = timing_compute_encoder(ctx, command_buffer(ctx), "fx compute");
			compute.label = @"Shader layer: bloom";
			[compute setSamplerState:fx->linear atIndex:0];
			[compute setTexture:color atIndex:0];
			[compute setTexture:fx->bloomDownTex[0] atIndex:1];
			dispatch(compute, fx->bloomPrefilter, fx->bloomDownTex[0].width, fx->bloomDownTex[0].height);
			for (int i = 1; i < BLOOM_LEVELS; i++) {
				[compute setTexture:fx->bloomDownTex[i - 1] atIndex:0];
				[compute setTexture:fx->bloomDownTex[i] atIndex:1];
				dispatch(compute, fx->bloomDown, fx->bloomDownTex[i].width, fx->bloomDownTex[i].height);
			}
			for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
				id<MTLTexture> low = i == BLOOM_LEVELS - 2 ? fx->bloomDownTex[i + 1] : fx->bloomUpTex[i + 1];
				[compute setTexture:low atIndex:0];
				[compute setTexture:fx->bloomDownTex[i] atIndex:1];
				[compute setTexture:fx->bloomUpTex[i] atIndex:2];
				dispatch(compute, fx->bloomUp, fx->bloomUpTex[i].width, fx->bloomUpTex[i].height);
			}
			[compute endEncoding];
		}
		composite(ctx, fx, color, @"fx_final_fs", ^(id<MTLRenderCommandEncoder> encoder) {
			[encoder setFragmentBytes:&params length:sizeof(params) atIndex:0];
			[encoder setFragmentBytes:&f length:sizeof(f) atIndex:1];
			[encoder setFragmentTexture:fx->bloomUpTex[0] atIndex:0];
			[encoder setFragmentSamplerState:fx->linear atIndex:0];
		});
	}
	return 1;
}

// Starts compiling the effect shaders in the background so they are ready by the time a world is loaded.
void fx_warm_up(McmContext *ctx) {
	if (!ctx->fx) fx_start(ctx);
}
