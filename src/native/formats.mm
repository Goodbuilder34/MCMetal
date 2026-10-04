// Logging helpers and format/enum translation tables.

#include "internal.h"

void mcm_log(const char *fmt, ...) {
	va_list args;
	va_start(args, fmt);
	fprintf(stderr, "[mcmetal/native] ");
	vfprintf(stderr, fmt, args);
	fprintf(stderr, "\n");
	va_end(args);
}

void copy_error(NSError *error, char *out, int32_t len) {
	if (!out || len <= 0) return;
	const char *msg = error ? error.localizedDescription.UTF8String : "unknown error";
	strncpy(out, msg ? msg : "unknown error", (size_t)len - 1);
	out[len - 1] = 0;
}

MTLPixelFormat pixel_format(int32_t code) {
	switch (code) {
		case MCM_FMT_R8_UNORM: return MTLPixelFormatR8Unorm;
		case MCM_FMT_R8_SNORM: return MTLPixelFormatR8Snorm;
		case MCM_FMT_RG8_UNORM: return MTLPixelFormatRG8Unorm;
		case MCM_FMT_RG8_SNORM: return MTLPixelFormatRG8Snorm;
		case MCM_FMT_RGBA8_UNORM: return MTLPixelFormatRGBA8Unorm;
		case MCM_FMT_RGBA8_SNORM: return MTLPixelFormatRGBA8Snorm;
		case MCM_FMT_R16_UNORM: return MTLPixelFormatR16Unorm;
		case MCM_FMT_R16_SNORM: return MTLPixelFormatR16Snorm;
		case MCM_FMT_RG16_UNORM: return MTLPixelFormatRG16Unorm;
		case MCM_FMT_RG16_SNORM: return MTLPixelFormatRG16Snorm;
		case MCM_FMT_RGBA16_UNORM: return MTLPixelFormatRGBA16Unorm;
		case MCM_FMT_RGBA16_SNORM: return MTLPixelFormatRGBA16Snorm;
		case MCM_FMT_R8_UINT: return MTLPixelFormatR8Uint;
		case MCM_FMT_R8_SINT: return MTLPixelFormatR8Sint;
		case MCM_FMT_RG8_UINT: return MTLPixelFormatRG8Uint;
		case MCM_FMT_RG8_SINT: return MTLPixelFormatRG8Sint;
		case MCM_FMT_RGBA8_UINT: return MTLPixelFormatRGBA8Uint;
		case MCM_FMT_RGBA8_SINT: return MTLPixelFormatRGBA8Sint;
		case MCM_FMT_R16_UINT: return MTLPixelFormatR16Uint;
		case MCM_FMT_R16_SINT: return MTLPixelFormatR16Sint;
		case MCM_FMT_RG16_UINT: return MTLPixelFormatRG16Uint;
		case MCM_FMT_RG16_SINT: return MTLPixelFormatRG16Sint;
		case MCM_FMT_RGBA16_UINT: return MTLPixelFormatRGBA16Uint;
		case MCM_FMT_RGBA16_SINT: return MTLPixelFormatRGBA16Sint;
		case MCM_FMT_R32_UINT: return MTLPixelFormatR32Uint;
		case MCM_FMT_R32_SINT: return MTLPixelFormatR32Sint;
		case MCM_FMT_RG32_UINT: return MTLPixelFormatRG32Uint;
		case MCM_FMT_RG32_SINT: return MTLPixelFormatRG32Sint;
		case MCM_FMT_RGBA32_UINT: return MTLPixelFormatRGBA32Uint;
		case MCM_FMT_RGBA32_SINT: return MTLPixelFormatRGBA32Sint;
		case MCM_FMT_R16_FLOAT: return MTLPixelFormatR16Float;
		case MCM_FMT_RG16_FLOAT: return MTLPixelFormatRG16Float;
		case MCM_FMT_RGBA16_FLOAT: return MTLPixelFormatRGBA16Float;
		case MCM_FMT_R32_FLOAT: return MTLPixelFormatR32Float;
		case MCM_FMT_RG32_FLOAT: return MTLPixelFormatRG32Float;
		case MCM_FMT_RGBA32_FLOAT: return MTLPixelFormatRGBA32Float;
		case MCM_FMT_RGB10A2_UNORM: return MTLPixelFormatRGB10A2Unorm;
		case MCM_FMT_RGB10A2_UINT: return MTLPixelFormatRGB10A2Uint;
		case MCM_FMT_RG11B10_FLOAT: return MTLPixelFormatRG11B10Float;
		case MCM_FMT_D32_FLOAT: return MTLPixelFormatDepth32Float;
		case MCM_FMT_D32_FLOAT_S8_UINT: return MTLPixelFormatDepth32Float_Stencil8;
		// Apple GPUs have no D24S8; D32S8 is a strict superset.
		case MCM_FMT_D24_UNORM_S8_UINT: return MTLPixelFormatDepth32Float_Stencil8;
		case MCM_FMT_D16_UNORM: return MTLPixelFormatDepth16Unorm;
		case MCM_FMT_S8_UINT: return MTLPixelFormatStencil8;
		default: return MTLPixelFormatInvalid; // 3-component formats have no texture equivalent
	}
}

bool is_depth_format(MTLPixelFormat format) {
	return format == MTLPixelFormatDepth32Float || format == MTLPixelFormatDepth16Unorm || format == MTLPixelFormatDepth32Float_Stencil8;
}

bool has_stencil(MTLPixelFormat format) {
	return format == MTLPixelFormatDepth32Float_Stencil8 || format == MTLPixelFormatStencil8;
}

MTLVertexFormat vertex_format(int32_t code) {
	switch (code) {
		case MCM_FMT_R8_UNORM: return MTLVertexFormatUCharNormalized;
		case MCM_FMT_RG8_UNORM: return MTLVertexFormatUChar2Normalized;
		case MCM_FMT_RGB8_UNORM: return MTLVertexFormatUChar3Normalized;
		case MCM_FMT_RGBA8_UNORM: return MTLVertexFormatUChar4Normalized;
		case MCM_FMT_R8_SNORM: return MTLVertexFormatCharNormalized;
		case MCM_FMT_RG8_SNORM: return MTLVertexFormatChar2Normalized;
		case MCM_FMT_RGB8_SNORM: return MTLVertexFormatChar3Normalized;
		case MCM_FMT_RGBA8_SNORM: return MTLVertexFormatChar4Normalized;
		case MCM_FMT_R8_UINT: return MTLVertexFormatUChar;
		case MCM_FMT_RG8_UINT: return MTLVertexFormatUChar2;
		case MCM_FMT_RGB8_UINT: return MTLVertexFormatUChar3;
		case MCM_FMT_RGBA8_UINT: return MTLVertexFormatUChar4;
		case MCM_FMT_R8_SINT: return MTLVertexFormatChar;
		case MCM_FMT_RG8_SINT: return MTLVertexFormatChar2;
		case MCM_FMT_RGB8_SINT: return MTLVertexFormatChar3;
		case MCM_FMT_RGBA8_SINT: return MTLVertexFormatChar4;
		case MCM_FMT_R16_UNORM: return MTLVertexFormatUShortNormalized;
		case MCM_FMT_RG16_UNORM: return MTLVertexFormatUShort2Normalized;
		case MCM_FMT_RGB16_UNORM: return MTLVertexFormatUShort3Normalized;
		case MCM_FMT_RGBA16_UNORM: return MTLVertexFormatUShort4Normalized;
		case MCM_FMT_R16_SNORM: return MTLVertexFormatShortNormalized;
		case MCM_FMT_RG16_SNORM: return MTLVertexFormatShort2Normalized;
		case MCM_FMT_RGB16_SNORM: return MTLVertexFormatShort3Normalized;
		case MCM_FMT_RGBA16_SNORM: return MTLVertexFormatShort4Normalized;
		case MCM_FMT_R16_UINT: return MTLVertexFormatUShort;
		case MCM_FMT_RG16_UINT: return MTLVertexFormatUShort2;
		case MCM_FMT_RGB16_UINT: return MTLVertexFormatUShort3;
		case MCM_FMT_RGBA16_UINT: return MTLVertexFormatUShort4;
		case MCM_FMT_R16_SINT: return MTLVertexFormatShort;
		case MCM_FMT_RG16_SINT: return MTLVertexFormatShort2;
		case MCM_FMT_RGB16_SINT: return MTLVertexFormatShort3;
		case MCM_FMT_RGBA16_SINT: return MTLVertexFormatShort4;
		case MCM_FMT_R16_FLOAT: return MTLVertexFormatHalf;
		case MCM_FMT_RG16_FLOAT: return MTLVertexFormatHalf2;
		case MCM_FMT_RGB16_FLOAT: return MTLVertexFormatHalf3;
		case MCM_FMT_RGBA16_FLOAT: return MTLVertexFormatHalf4;
		case MCM_FMT_R32_FLOAT: return MTLVertexFormatFloat;
		case MCM_FMT_RG32_FLOAT: return MTLVertexFormatFloat2;
		case MCM_FMT_RGB32_FLOAT: return MTLVertexFormatFloat3;
		case MCM_FMT_RGBA32_FLOAT: return MTLVertexFormatFloat4;
		case MCM_FMT_R32_UINT: return MTLVertexFormatUInt;
		case MCM_FMT_RG32_UINT: return MTLVertexFormatUInt2;
		case MCM_FMT_RGB32_UINT: return MTLVertexFormatUInt3;
		case MCM_FMT_RGBA32_UINT: return MTLVertexFormatUInt4;
		case MCM_FMT_R32_SINT: return MTLVertexFormatInt;
		case MCM_FMT_RG32_SINT: return MTLVertexFormatInt2;
		case MCM_FMT_RGB32_SINT: return MTLVertexFormatInt3;
		case MCM_FMT_RGBA32_SINT: return MTLVertexFormatInt4;
		case MCM_FMT_RGB10A2_UNORM: return MTLVertexFormatUInt1010102Normalized;
		case MCM_FMT_RG11B10_FLOAT: return MTLVertexFormatFloatRG11B10;
		default: return MTLVertexFormatInvalid;
	}
}

// Codes follow com.mojang.renderpearl.api.pipeline.BlendFactor (mapped explicitly in MetalConst.java).
MTLBlendFactor blend_factor(int32_t code) {
	switch (code) {
		case 0: return MTLBlendFactorBlendAlpha;
		case 1: return MTLBlendFactorBlendColor;
		case 2: return MTLBlendFactorDestinationAlpha;
		case 3: return MTLBlendFactorDestinationColor;
		case 4: return MTLBlendFactorOne;
		case 5: return MTLBlendFactorOneMinusBlendAlpha;
		case 6: return MTLBlendFactorOneMinusBlendColor;
		case 7: return MTLBlendFactorOneMinusDestinationAlpha;
		case 8: return MTLBlendFactorOneMinusDestinationColor;
		case 9: return MTLBlendFactorOneMinusSourceAlpha;
		case 10: return MTLBlendFactorOneMinusSourceColor;
		case 11: return MTLBlendFactorSourceAlpha;
		case 12: return MTLBlendFactorSourceAlphaSaturated;
		case 13: return MTLBlendFactorSourceColor;
		default: return MTLBlendFactorZero;
	}
}

MTLBlendOperation blend_op(int32_t code) {
	switch (code) {
		case 1: return MTLBlendOperationSubtract;
		case 2: return MTLBlendOperationReverseSubtract;
		case 3: return MTLBlendOperationMin;
		case 4: return MTLBlendOperationMax;
		default: return MTLBlendOperationAdd;
	}
}

// Codes follow com.mojang.renderpearl.api.pipeline.CompareOp.
MTLCompareFunction compare_function(int32_t code) {
	switch (code) {
		case 0: return MTLCompareFunctionAlways;
		case 1: return MTLCompareFunctionLess;
		case 2: return MTLCompareFunctionLessEqual;
		case 3: return MTLCompareFunctionEqual;
		case 4: return MTLCompareFunctionNotEqual;
		case 5: return MTLCompareFunctionGreaterEqual;
		case 6: return MTLCompareFunctionGreater;
		default: return MTLCompareFunctionNever;
	}
}

MTLPrimitiveType primitive_type(int32_t code) {
	switch (code) {
		case MCM_PRIM_POINT: return MTLPrimitiveTypePoint;
		case MCM_PRIM_LINE: return MTLPrimitiveTypeLine;
		case MCM_PRIM_LINE_STRIP: return MTLPrimitiveTypeLineStrip;
		case MCM_PRIM_TRIANGLE_STRIP: return MTLPrimitiveTypeTriangleStrip;
		default: return MTLPrimitiveTypeTriangle; // fans are rewritten into triangle lists at draw time
	}
}
