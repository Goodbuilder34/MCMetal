// C ABI between the Java backend (com.mcmetal.metal.Native) and the Metal implementation.
// Every handle is an opaque pointer to a retained Objective-C object or a C++ struct.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MCM_EXPORT __attribute__((visibility("default")))

// Binding layout shared with the shader translator (MetalShaderCompiler.java).
#define MCM_PUSH_CONSTANT_BUFFER_INDEX 16
#define MCM_VERTEX_BUFFER_BASE_INDEX 24

// Format codes: one per com.mojang.renderpearl.api.GpuFormat constant (see MetalConst.java).
enum {
	MCM_FMT_R8_UNORM, MCM_FMT_R8_SNORM, MCM_FMT_RG8_UNORM, MCM_FMT_RG8_SNORM, MCM_FMT_RGB8_UNORM, MCM_FMT_RGB8_SNORM,
	MCM_FMT_RGBA8_UNORM, MCM_FMT_RGBA8_SNORM, MCM_FMT_R16_UNORM, MCM_FMT_R16_SNORM, MCM_FMT_RG16_UNORM, MCM_FMT_RG16_SNORM,
	MCM_FMT_RGB16_UNORM, MCM_FMT_RGB16_SNORM, MCM_FMT_RGBA16_UNORM, MCM_FMT_RGBA16_SNORM, MCM_FMT_R8_UINT, MCM_FMT_R8_SINT,
	MCM_FMT_RG8_UINT, MCM_FMT_RG8_SINT, MCM_FMT_RGB8_UINT, MCM_FMT_RGB8_SINT, MCM_FMT_RGBA8_UINT, MCM_FMT_RGBA8_SINT,
	MCM_FMT_R16_UINT, MCM_FMT_R16_SINT, MCM_FMT_RG16_UINT, MCM_FMT_RG16_SINT, MCM_FMT_RGB16_UINT, MCM_FMT_RGB16_SINT,
	MCM_FMT_RGBA16_UINT, MCM_FMT_RGBA16_SINT, MCM_FMT_R32_UINT, MCM_FMT_R32_SINT, MCM_FMT_RG32_UINT, MCM_FMT_RG32_SINT,
	MCM_FMT_RGB32_UINT, MCM_FMT_RGB32_SINT, MCM_FMT_RGBA32_UINT, MCM_FMT_RGBA32_SINT, MCM_FMT_R16_FLOAT, MCM_FMT_RG16_FLOAT,
	MCM_FMT_RGB16_FLOAT, MCM_FMT_RGBA16_FLOAT, MCM_FMT_R32_FLOAT, MCM_FMT_RG32_FLOAT, MCM_FMT_RGB32_FLOAT, MCM_FMT_RGBA32_FLOAT,
	MCM_FMT_RGB10A2_UNORM, MCM_FMT_RGB10A2_UINT, MCM_FMT_RG11B10_FLOAT, MCM_FMT_D32_FLOAT, MCM_FMT_D32_FLOAT_S8_UINT,
	MCM_FMT_D24_UNORM_S8_UINT, MCM_FMT_D16_UNORM, MCM_FMT_S8_UINT,
	MCM_FMT_COUNT
};

// Primitive codes (MetalConst.java).
enum { MCM_PRIM_POINT, MCM_PRIM_LINE, MCM_PRIM_LINE_STRIP, MCM_PRIM_TRIANGLE, MCM_PRIM_TRIANGLE_STRIP, MCM_PRIM_TRIANGLE_FAN };

// Uniform binding entry kinds for mcm_pass_bind.
enum { MCM_BIND_BUFFER = 1, MCM_BIND_TEXTURE_SAMPLER = 2, MCM_BIND_TEXTURE = 3 };

// Device info slots filled by mcm_device_create.
enum {
	MCM_INFO_MAX_BUFFER_LENGTH,
	MCM_INFO_WORKING_SET_SIZE,
	MCM_INFO_GPU_FAMILY,
	MCM_INFO_UNIFIED_MEMORY,
	MCM_INFO_COUNT = 8
};

MCM_EXPORT int32_t mcm_abi_version(void);
MCM_EXPORT void mcm_release(void *object);
MCM_EXPORT void mcm_set_label(void *resource, const char *label);

MCM_EXPORT void *mcm_device_create(char *name_out, int32_t name_len, int64_t *info_out);
MCM_EXPORT void mcm_device_destroy(void *ctx);

MCM_EXPORT void *mcm_buffer_create(void *ctx, int64_t length);
MCM_EXPORT void *mcm_buffer_contents(void *buffer);

MCM_EXPORT void *mcm_texture_create(void *ctx, int32_t format, int32_t width, int32_t height, int32_t layers, int32_t mips, int32_t cubemap);
MCM_EXPORT void *mcm_texture_view_create(void *texture, int32_t format, int32_t base_mip, int32_t mips, int32_t cubemap);
MCM_EXPORT void *mcm_texture_buffer_create(void *ctx, void *buffer, int32_t format, int32_t width, int32_t bytes_per_row);
MCM_EXPORT void *mcm_sampler_create(void *ctx, int32_t repeat_u, int32_t repeat_v, int32_t linear_min, int32_t linear_mag, int32_t mip_mode, int32_t max_anisotropy, float max_lod);

MCM_EXPORT void *mcm_library_create(void *ctx, const char *source, char *error_out, int32_t error_len);
MCM_EXPORT void *mcm_function_create(void *library, const char *name);
// desc layout is documented in MetalRenderPipeline.java (encodeDescriptor).
MCM_EXPORT void *mcm_pipeline_create(void *ctx, void *vertex_fn, void *fragment_fn, const int32_t *desc, int32_t desc_len, const char *label, char *error_out, int32_t error_len);
MCM_EXPORT void mcm_pipeline_destroy(void *pipeline);

MCM_EXPORT void mcm_blit_copy_buffer(void *ctx, void *src, int64_t src_offset, void *dst, int64_t dst_offset, int64_t length);
MCM_EXPORT void mcm_blit_buffer_to_texture(void *ctx, void *buffer, int64_t offset, int32_t bytes_per_row, int32_t bytes_per_image, void *texture, int32_t slice, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height);
MCM_EXPORT void mcm_blit_texture_to_buffer(void *ctx, void *texture, int32_t slice, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height, void *buffer, int64_t offset, int32_t bytes_per_row, int32_t bytes_per_image);
MCM_EXPORT void mcm_blit_texture_to_texture(void *ctx, void *src, void *dst, int32_t mip, int32_t src_x, int32_t src_y, int32_t dst_x, int32_t dst_y, int32_t width, int32_t height);
MCM_EXPORT void mcm_clear_texture(void *ctx, void *texture, int32_t mip, int32_t slice, float r, float g, float b, float a, double depth);
MCM_EXPORT void mcm_clear_region(void *ctx, void *color_texture, void *depth_texture, int32_t mip, int32_t x, int32_t y, int32_t width, int32_t height, float r, float g, float b, float a, double depth);

MCM_EXPORT void mcm_begin_pass(void *ctx, void *const *color_textures, const int32_t *clear_flags, const float *clear_colors, int32_t color_count, void *depth_texture, int32_t clear_depth, double depth, int32_t x, int32_t y, int32_t width, int32_t height, const char *label);
MCM_EXPORT void mcm_end_pass(void *ctx);
MCM_EXPORT void mcm_pass_set_pipeline(void *ctx, void *pipeline);
// entries: count * 5 int64s of {kind | stage_mask << 8, index, handle, offset_or_sampler, reserved}
MCM_EXPORT void mcm_pass_bind(void *ctx, const int64_t *entries, int32_t count);
MCM_EXPORT void mcm_pass_set_bytes(void *ctx, int32_t index, const void *data, int32_t length);
MCM_EXPORT void mcm_pass_set_vertex_buffer(void *ctx, int32_t slot, void *buffer, int64_t offset);
MCM_EXPORT void mcm_pass_set_index_buffer(void *ctx, void *buffer, int32_t index_size);
MCM_EXPORT void mcm_pass_set_scissor(void *ctx, int32_t x, int32_t y, int32_t width, int32_t height);
MCM_EXPORT void mcm_pass_draw(void *ctx, int32_t vertex_count, int32_t instance_count, int32_t first_vertex, int32_t first_instance);
MCM_EXPORT void mcm_pass_draw_indexed(void *ctx, int32_t index_count, int32_t instance_count, int32_t first_index, int32_t vertex_offset, int32_t first_instance);
MCM_EXPORT void mcm_pass_multi_draw_indexed(void *ctx, const int32_t *params, int32_t draw_count, int32_t instance_count, int32_t first_instance);
MCM_EXPORT void mcm_pass_multi_draw(void *ctx, const int32_t *params, int32_t draw_count, int32_t instance_count, int32_t first_instance);
MCM_EXPORT void mcm_pass_draw_indexed_indirect(void *ctx, void *buffer, int64_t offset, int32_t draw_count);
MCM_EXPORT void mcm_pass_draw_indirect(void *ctx, void *buffer, int64_t offset, int32_t draw_count);
MCM_EXPORT void mcm_push_debug_group(void *ctx, const char *label);
MCM_EXPORT void mcm_pop_debug_group(void *ctx);

MCM_EXPORT uint64_t mcm_submit(void *ctx);
MCM_EXPORT int32_t mcm_wait(void *ctx, uint64_t value, int64_t timeout_ns);
MCM_EXPORT uint64_t mcm_completed(void *ctx);
// out[0] = total GPU nanoseconds of completed command buffers, out[1] = number of command buffers,
// out[2] = drawables that actually reached the screen, out[3] = render passes begun, out[4] = passes merged,
// out[5] = buffer uploads hoisted out of the frame, out[6] = clears drawn inside a merged pass,
// out[7] = summed frame-start-to-screen ns, out[8] = frames in that sum, out[9] = limited frames that missed their refresh.
MCM_EXPORT void mcm_gpu_time(void *ctx, uint64_t *out);

MCM_EXPORT void *mcm_surface_create(void *ctx, void *metal_layer);
// refresh_hz: the display's refresh rate, used to present at most one frame per refresh when vsync is off (0 = unknown).
MCM_EXPORT void mcm_surface_configure(void *surface, int32_t width, int32_t height, int32_t vsync, float refresh_hz);
MCM_EXPORT int32_t mcm_surface_blit(void *surface, void *ctx, void *texture, int32_t mip, int32_t width, int32_t height);
MCM_EXPORT void mcm_surface_destroy(void *surface);
// Low-latency frame limiter: one frame per display refresh, started as late as possible (see mcm_frame_begin).
MCM_EXPORT void mcm_surface_set_limiter(void *surface, int32_t enabled);
// Marks the start of a frame (before input is read); sleeps here when the limiter is on. surface may be null.
MCM_EXPORT void mcm_frame_begin(void *surface, void *ctx);

#ifdef __cplusplus
}
#endif
