package com.mcmetal.shaderpack;

import java.util.Set;

/** The {@link GlslTransformer.Environment}s for the different kinds of pack programs. */
public final class Environments {
	private Environments() {
	}

	/** Pack vertex attributes (mc_Entity etc.) are globals with defaults unless the environment feeds them. */
	static final String DEFAULT_ATTRIBUTES = """
		""";

	static void matrices(final StringBuilder sb, final Set<String> used) {
		if (used.contains("_mcm_ModelViewProjectionMatrix")) {
			sb.append("#define _mcm_ModelViewProjectionMatrix (_mcm_ProjectionMatrix * _mcm_ModelViewMatrix)\n");
		}
		if (used.contains("_mcm_NormalMatrix")) {
			sb.append("#define _mcm_NormalMatrix transpose(inverse(mat3(_mcm_ModelViewMatrix)))\n");
		}
		if (used.contains("_mcm_ModelViewMatrixInverse")) {
			sb.append("#define _mcm_ModelViewMatrixInverse inverse(_mcm_ModelViewMatrix)\n");
		}
		if (used.contains("_mcm_ProjectionMatrixInverse")) {
			sb.append("#define _mcm_ProjectionMatrixInverse inverse(_mcm_ProjectionMatrix)\n");
		}
		if (used.contains("_mcm_ModelViewProjectionMatrixInverse")) {
			sb.append("#define _mcm_ModelViewProjectionMatrixInverse inverse(_mcm_ProjectionMatrix * _mcm_ModelViewMatrix)\n");
		}
		if (used.contains("_mcm_ModelViewMatrixTranspose")) {
			sb.append("#define _mcm_ModelViewMatrixTranspose transpose(_mcm_ModelViewMatrix)\n");
		}
		if (used.contains("_mcm_ProjectionMatrixTranspose")) {
			sb.append("#define _mcm_ProjectionMatrixTranspose transpose(_mcm_ProjectionMatrix)\n");
		}
		if (used.contains("_mcm_Fog")) {
			sb.append("struct _mcm_FogParameters { vec4 color; float density; float start; float end; float scale; };\n");
			sb.append("uniform _mcm_FogParameters _mcm_Fog;\n");
		}
		if (used.contains("_mcm_ClipVertex")) {
			sb.append("vec4 _mcm_ClipVertex;\n");
		}
		if (used.contains("_mcm_BackColor")) {
			sb.append("vec4 _mcm_BackColor;\n");
		}
		if (used.contains("_mcm_BackSecondaryColor")) {
			sb.append("vec4 _mcm_BackSecondaryColor;\n");
		}
		if (used.contains("_mcm_FogCoord")) {
			sb.append("#define _mcm_FogCoord 0.0\n");
		}
		if (used.contains("_mcm_SecondaryColor")) {
			sb.append("#define _mcm_SecondaryColor vec4(0.0)\n");
		}
	}

	/** Iris core-profile built-ins (for packs written against #version 150+ core). */
	public static final Set<String> CORE_UNIFORMS = Set.of(
		"modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse", "normalMatrix", "textureMatrix", "chunkOffset",
		"alphaTestRef"
	);
	public static final Set<String> CORE_ATTRIBUTES = Set.of("vaPosition", "vaColor", "vaUV0", "vaUV1", "vaUV2", "vaNormal");

	static void coreBuiltins(final StringBuilder sb, final Set<String> used, final boolean vertex, final float alphaRef) {
		sb.append("#define modelViewMatrix _mcm_ModelViewMatrix\n");
		sb.append("#define projectionMatrix _mcm_ProjectionMatrix\n");
		sb.append("#define modelViewMatrixInverse inverse(_mcm_ModelViewMatrix)\n");
		sb.append("#define projectionMatrixInverse inverse(_mcm_ProjectionMatrix)\n");
		sb.append("#define normalMatrix transpose(inverse(mat3(_mcm_ModelViewMatrix)))\n");
		sb.append("#define textureMatrix mat4(1.0)\n");
		sb.append("#define chunkOffset vec3(0.0)\n");
		sb.append("#define alphaTestRef ").append(alphaRef).append('\n');
		if (vertex) {
			sb.append("#define vaPosition (_mcm_Vertex.xyz)\n");
			sb.append("#define vaColor _mcm_Color\n");
			sb.append("#define vaUV0 (_mcm_MultiTexCoord0.xy)\n");
			sb.append("#define vaUV1 ivec2(0, 10)\n");
			sb.append("#define vaUV2 ivec2(_mcm_MultiTexCoord1.xy)\n");
			sb.append("#define vaNormal _mcm_Normal\n");
		}
	}

	/** OpenGL clip space (z in [-w, w]) to Metal's ([0, w]); all pack depth targets use OpenGL's depth convention. */
	static final String DEPTH_FIXUP = "\tgl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n";

	/**
	 * Full-screen passes (deferred, composite, final): a single triangle covering the screen. Positions and texture
	 * coordinates span [0, 2] so the visible part is [0, 1], as in OptiFine's unit quad under an orthographic projection.
	 */
	public static final GlslTransformer.Environment COMPOSITE = new GlslTransformer.Environment() {
		@Override
		public String prelude(final GlslTransformer.Stage stage, final Set<String> used) {
			StringBuilder sb = new StringBuilder();
			sb.append("#define _mcm_ModelViewMatrix mat4(1.0)\n");
			sb.append("#define _mcm_ProjectionMatrix mat4(2.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 0.0, -2.0, 0.0, -1.0, -1.0, -1.0, 1.0)\n");
			if (stage == GlslTransformer.Stage.VERTEX) {
				sb.append("#define _mcm_quad vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2))\n");
				sb.append("#define _mcm_Vertex vec4(_mcm_quad, 0.0, 1.0)\n");
				sb.append("#define _mcm_Color vec4(1.0)\n");
				sb.append("#define _mcm_Normal vec3(0.0, 0.0, 1.0)\n");
				sb.append("#define _mcm_MultiTexCoord0 vec4(_mcm_quad, 0.0, 1.0)\n");
				for (int i = 1; i < 8; i++) {
					sb.append("#define _mcm_MultiTexCoord").append(i).append(" vec4(0.0, 0.0, 0.0, 1.0)\n");
				}
				if (used.contains("_mcm_TextureMatrix")) {
					sb.append("const mat4 _mcm_TextureMatrix[8] = mat4[8](mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0));\n");
				}
				matrices(sb, used);
				sb.append("vec4 ftransform() { return _mcm_ProjectionMatrix * (_mcm_ModelViewMatrix * _mcm_Vertex); }\n");
			} else {
				matrices(sb, used);
			}
			coreBuiltins(sb, used, stage == GlslTransformer.Stage.VERTEX, 0.0F);
			return sb.toString();
		}

		@Override
		public String epilogue(final GlslTransformer.Stage stage, final Set<String> used) {
			return stage == GlslTransformer.Stage.VERTEX ? DEPTH_FIXUP : "";
		}

		@Override
		public Set<String> providedUniforms() {
			return CORE_UNIFORMS;
		}

		@Override
		public Set<String> providedAttributes() {
			return CORE_ATTRIBUTES;
		}
	};
}
