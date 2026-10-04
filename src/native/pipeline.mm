// Shader libraries and render pipelines.

#include "internal.h"

extern "C" void *mcm_library_create(void *handle, const char *source, char *error_out, int32_t error_len) {
	McmContext *ctx = (McmContext *)handle;
	@autoreleasepool {
		MTLCompileOptions *options = [MTLCompileOptions new];
		options.languageVersion = MTLLanguageVersion3_0;
		// The game marks gl_Position invariant so multi-pass geometry depth-matches exactly.
		options.preserveInvariance = YES;
		NSError *error = nil;
		id<MTLLibrary> library = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:options error:&error];
		if (!library) {
			copy_error(error, error_out, error_len);
			return nullptr;
		}
		return (__bridge_retained void *)library;
	}
}

extern "C" void *mcm_function_create(void *library, const char *name) {
	@autoreleasepool {
		id<MTLFunction> function = [(__bridge id<MTLLibrary>)library newFunctionWithName:[NSString stringWithUTF8String:name]];
		return function ? (__bridge_retained void *)function : nullptr;
	}
}

// Descriptor layout (all int32):
//   [0] color target count   [1] has depth/stencil state   [2] depth compare   [3] depth write
//   [4] depth bias constant (float bits)   [5] depth bias slope (float bits)
//   [6] cull back faces   [7] wireframe   [8] primitive code   [9] vertex buffer count   [10] attribute count
//   then 9 ints per color target: format (-1 = unused), write mask (R1 G2 B4 A8), blend enabled,
//        color op, alpha op, src color, dst color, src alpha, dst alpha
//   then 3 ints per vertex buffer: slot, stride, step rate (0 = per vertex)
//   then 4 ints per attribute: location, slot, offset, format
extern "C" void *mcm_pipeline_create(void *handle, void *vertex_fn, void *fragment_fn, const int32_t *d, int32_t desc_len, const char *label, char *error_out, int32_t error_len) {
	McmContext *ctx = (McmContext *)handle;
	@autoreleasepool {
		int32_t colorCount = d[0];
		int32_t bufferCount = d[9];
		int32_t attributeCount = d[10];
		if (11 + colorCount * 9 + bufferCount * 3 + attributeCount * 4 > desc_len) {
			copy_error(nil, error_out, error_len);
			return nullptr;
		}

		MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
		desc.label = label ? [NSString stringWithUTF8String:label] : nil;
		desc.vertexFunction = (__bridge id<MTLFunction>)vertex_fn;
		desc.fragmentFunction = (__bridge id<MTLFunction>)fragment_fn;
		desc.rasterSampleCount = 1;

		const int32_t *color = d + 11;
		for (int32_t i = 0; i < colorCount; i++, color += 9) {
			MTLRenderPipelineColorAttachmentDescriptor *attachment = desc.colorAttachments[(NSUInteger)i];
			if (color[0] < 0) {
				attachment.pixelFormat = MTLPixelFormatInvalid;
				continue;
			}
			attachment.pixelFormat = pixel_format(color[0]);
			MTLColorWriteMask mask = MTLColorWriteMaskNone;
			if (color[1] & 1) mask |= MTLColorWriteMaskRed;
			if (color[1] & 2) mask |= MTLColorWriteMaskGreen;
			if (color[1] & 4) mask |= MTLColorWriteMaskBlue;
			if (color[1] & 8) mask |= MTLColorWriteMaskAlpha;
			attachment.writeMask = mask;
			attachment.blendingEnabled = color[2] != 0;
			if (color[2]) {
				attachment.rgbBlendOperation = blend_op(color[3]);
				attachment.alphaBlendOperation = blend_op(color[4]);
				attachment.sourceRGBBlendFactor = blend_factor(color[5]);
				attachment.destinationRGBBlendFactor = blend_factor(color[6]);
				attachment.sourceAlphaBlendFactor = blend_factor(color[7]);
				attachment.destinationAlphaBlendFactor = blend_factor(color[8]);
			}
		}

		MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
		const int32_t *buffers = color;
		for (int32_t i = 0; i < bufferCount; i++) {
			const int32_t *b = buffers + i * 3;
			MTLVertexBufferLayoutDescriptor *layout = vertexDescriptor.layouts[(NSUInteger)(MCM_VERTEX_BUFFER_BASE_INDEX + b[0])];
			layout.stride = (NSUInteger)b[1];
			if (b[2] > 0) {
				layout.stepFunction = MTLVertexStepFunctionPerInstance;
				layout.stepRate = (NSUInteger)b[2];
			} else {
				layout.stepFunction = MTLVertexStepFunctionPerVertex;
				layout.stepRate = 1;
			}
		}
		const int32_t *attributes = buffers + bufferCount * 3;
		for (int32_t i = 0; i < attributeCount; i++) {
			const int32_t *a = attributes + i * 4;
			MTLVertexAttributeDescriptor *attribute = vertexDescriptor.attributes[(NSUInteger)a[0]];
			attribute.bufferIndex = (NSUInteger)(MCM_VERTEX_BUFFER_BASE_INDEX + a[1]);
			attribute.offset = (NSUInteger)a[2];
			attribute.format = vertex_format(a[3]);
			if (attribute.format == MTLVertexFormatInvalid) {
				snprintf(error_out, (size_t)error_len, "unsupported vertex format code %d at location %d", a[3], a[0]);
				return nullptr;
			}
		}
		if (bufferCount > 0) desc.vertexDescriptor = vertexDescriptor;

		McmPipeline *pipeline = new McmPipeline();
		bool hasDepthState = d[1] != 0;

		NSError *error = nil;
		desc.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		pipeline->withDepth = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
		if (!pipeline->withDepth) {
			copy_error(error, error_out, error_len);
			delete pipeline;
			return nullptr;
		}
		if (!hasDepthState) {
			desc.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
			pipeline->withoutDepth = [ctx->device newRenderPipelineStateWithDescriptor:desc error:&error];
			if (!pipeline->withoutDepth) {
				copy_error(error, error_out, error_len);
				delete pipeline;
				return nullptr;
			}
			pipeline->depthState = ctx->depthDisabled;
		} else {
			MTLDepthStencilDescriptor *depth = [MTLDepthStencilDescriptor new];
			depth.depthCompareFunction = compare_function(d[2]);
			depth.depthWriteEnabled = d[3] != 0;
			pipeline->depthState = [ctx->device newDepthStencilStateWithDescriptor:depth];
		}

		float biasConstant, biasSlope;
		memcpy(&biasConstant, &d[4], sizeof(float));
		memcpy(&biasSlope, &d[5], sizeof(float));
		pipeline->biasConstant = biasConstant;
		pipeline->biasSlope = biasSlope;
		pipeline->cull = d[6] ? MTLCullModeBack : MTLCullModeNone;
		pipeline->fill = d[7] ? MTLTriangleFillModeLines : MTLTriangleFillModeFill;
		pipeline->primitive = primitive_type(d[8]);
		pipeline->fan = d[8] == MCM_PRIM_TRIANGLE_FAN;
		if (const char *skip = getenv("MCMETAL_EXP_SKIP"); skip && label) {
			for (NSString *part in [[NSString stringWithUTF8String:skip] componentsSeparatedByString:@","]) {
				if (part.length && strstr(label, part.UTF8String)) pipeline->skip = true;
			}
			if (pipeline->skip) mcm_log("[exp] skipping draws of %s", label);
		}
		return pipeline;
	}
}

extern "C" void mcm_pipeline_destroy(void *pipeline) {
	delete (McmPipeline *)pipeline;
}
