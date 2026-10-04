package com.mcmetal.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/** Stable codes shared with src/native/mcmetal.h. Mapped by name so enum reordering upstream can't skew them. */
final class MetalConst {
	static final int PRIM_POINT = 0;
	static final int PRIM_LINE = 1;
	static final int PRIM_LINE_STRIP = 2;
	static final int PRIM_TRIANGLE = 3;
	static final int PRIM_TRIANGLE_STRIP = 4;
	static final int PRIM_TRIANGLE_FAN = 5;

	private MetalConst() {
	}

	static int format(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 0;
			case R8_SNORM -> 1;
			case RG8_UNORM -> 2;
			case RG8_SNORM -> 3;
			case RGB8_UNORM -> 4;
			case RGB8_SNORM -> 5;
			case RGBA8_UNORM -> 6;
			case RGBA8_SNORM -> 7;
			case R16_UNORM -> 8;
			case R16_SNORM -> 9;
			case RG16_UNORM -> 10;
			case RG16_SNORM -> 11;
			case RGB16_UNORM -> 12;
			case RGB16_SNORM -> 13;
			case RGBA16_UNORM -> 14;
			case RGBA16_SNORM -> 15;
			case R8_UINT -> 16;
			case R8_SINT -> 17;
			case RG8_UINT -> 18;
			case RG8_SINT -> 19;
			case RGB8_UINT -> 20;
			case RGB8_SINT -> 21;
			case RGBA8_UINT -> 22;
			case RGBA8_SINT -> 23;
			case R16_UINT -> 24;
			case R16_SINT -> 25;
			case RG16_UINT -> 26;
			case RG16_SINT -> 27;
			case RGB16_UINT -> 28;
			case RGB16_SINT -> 29;
			case RGBA16_UINT -> 30;
			case RGBA16_SINT -> 31;
			case R32_UINT -> 32;
			case R32_SINT -> 33;
			case RG32_UINT -> 34;
			case RG32_SINT -> 35;
			case RGB32_UINT -> 36;
			case RGB32_SINT -> 37;
			case RGBA32_UINT -> 38;
			case RGBA32_SINT -> 39;
			case R16_FLOAT -> 40;
			case RG16_FLOAT -> 41;
			case RGB16_FLOAT -> 42;
			case RGBA16_FLOAT -> 43;
			case R32_FLOAT -> 44;
			case RG32_FLOAT -> 45;
			case RGB32_FLOAT -> 46;
			case RGBA32_FLOAT -> 47;
			case RGB10A2_UNORM -> 48;
			case RGB10A2_UINT -> 49;
			case RG11B10_FLOAT -> 50;
			case D32_FLOAT -> 51;
			case D32_FLOAT_S8_UINT -> 52;
			case D24_UNORM_S8_UINT -> 53;
			case D16_UNORM -> 54;
			case S8_UINT -> 55;
		};
	}

	static int blendFactor(BlendFactor factor) {
		return switch (factor) {
			case CONSTANT_ALPHA -> 0;
			case CONSTANT_COLOR -> 1;
			case DST_ALPHA -> 2;
			case DST_COLOR -> 3;
			case ONE -> 4;
			case ONE_MINUS_CONSTANT_ALPHA -> 5;
			case ONE_MINUS_CONSTANT_COLOR -> 6;
			case ONE_MINUS_DST_ALPHA -> 7;
			case ONE_MINUS_DST_COLOR -> 8;
			case ONE_MINUS_SRC_ALPHA -> 9;
			case ONE_MINUS_SRC_COLOR -> 10;
			case SRC_ALPHA -> 11;
			case SRC_ALPHA_SATURATE -> 12;
			case SRC_COLOR -> 13;
			case ZERO -> 14;
		};
	}

	static int blendOp(BlendOp op) {
		return switch (op) {
			case ADD -> 0;
			case SUBTRACT -> 1;
			case REVERSE_SUBTRACT -> 2;
			case MIN -> 3;
			case MAX -> 4;
		};
	}

	static int compareOp(CompareOp op) {
		return switch (op) {
			case ALWAYS_PASS -> 0;
			case LESS_THAN -> 1;
			case LESS_THAN_OR_EQUAL -> 2;
			case EQUAL -> 3;
			case NOT_EQUAL -> 4;
			case GREATER_THAN_OR_EQUAL -> 5;
			case GREATER_THAN -> 6;
			case NEVER_PASS -> 7;
		};
	}

	/** LINES and QUADS are expanded to triangles by the game's index buffers, like the Vulkan backend. */
	static int primitive(PrimitiveTopology topology) {
		return switch (topology) {
			case LINES, TRIANGLES, QUADS -> PRIM_TRIANGLE;
			case DEBUG_LINES -> PRIM_LINE;
			case DEBUG_LINE_STRIP -> PRIM_LINE_STRIP;
			case POINTS -> PRIM_POINT;
			case TRIANGLE_STRIP -> PRIM_TRIANGLE_STRIP;
			case TRIANGLE_FAN -> PRIM_TRIANGLE_FAN;
		};
	}
}
