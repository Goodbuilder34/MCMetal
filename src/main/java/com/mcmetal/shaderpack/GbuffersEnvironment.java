package com.mcmetal.shaderpack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Environment for gbuffers/shadow programs that replace one of the game's world pipelines. The pack's compatibility
 * built-ins are expressed in terms of that pipeline's vertex attributes and uniform blocks, which the game keeps
 * binding exactly as it would for its own shader:
 * <ul>
 *   <li>{@code gl_Vertex}: camera-relative position (terrain adds the chunk offset like the vanilla shader)</li>
 *   <li>{@code gl_ModelViewMatrix}: the pipeline's model-view matrix; {@code gl_ProjectionMatrix}: its projection,
 *   converted from the game's reversed [0, 1] depth to OpenGL's [-1, 1]</li>
 *   <li>{@code gl_Color}: vertex color times the color modulator; {@code gl_MultiTexCoord0}: UV0;
 *   {@code gl_MultiTexCoord1/2}: lightmap coordinates (0-240) with OptiFine's lightmap texture matrix</li>
 * </ul>
 */
public final class GbuffersEnvironment implements GlslTransformer.Environment {
	/** A vertex input of the game's pipeline. */
	public record Attribute(String name, int location, String glslType) {
	}

	/** The game pipeline's layout: vertex inputs by name and uniform/sampler indices by name. */
	public record VanillaLayout(Map<String, Attribute> attributes, Map<String, Integer> uniforms) {
	}

	/** Declarations of the game's uniform blocks (from assets/minecraft/shaders/include), with instance names. */
	private static final Map<String, String> BLOCKS = Map.of(
		"Projection", "mat4 ProjMat;",
		"DynamicTransforms", "mat4 ModelViewMat; mat4 TextureMat; vec4 ColorModulator; vec3 ModelOffset;",
		"TerrainUniform", "mat4 ModelViewMat; ivec2 TextureSize;",
		"ChunkSection", "ivec3 ChunkPosition; float ChunkVisibility;",
		"Globals", "ivec3 CameraBlockPos; float GlintAlpha; vec3 CameraOffset; float GameTime; vec2 ScreenSize; int MenuBlurRadius; int UseRgss;",
		"Fog", "vec4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd; float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd;"
	);

	private static final Set<String> PULLED = Set.of("mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock");

	/** Reads the quad's four vertices from the vertex buffer (BLOCK format: 7 words per vertex). */
	private static final String PULLING = """
		layout(std430) readonly buffer _mcm_VertexData { uint _mcm_vdata[]; };
		vec3 _mcm_vpos(int v) { int b = v * 7; return vec3(uintBitsToFloat(_mcm_vdata[b]), uintBitsToFloat(_mcm_vdata[b + 1]), uintBitsToFloat(_mcm_vdata[b + 2])); }
		vec2 _mcm_vuv(int v) { int b = v * 7 + 4; return vec2(uintBitsToFloat(_mcm_vdata[b]), uintBitsToFloat(_mcm_vdata[b + 1])); }
		int _mcm_qbase() { return gl_VertexIndex & ~3; }
		vec3 _mcm_quadNormal() {
			int b = _mcm_qbase();
			vec3 n = cross(_mcm_vpos(b + 2) - _mcm_vpos(b), _mcm_vpos(b + 3) - _mcm_vpos(b + 1));
			float l = length(n);
			return l > 1e-6 ? n / l : vec3(0.0, 1.0, 0.0);
		}
		vec4 _mcm_pulled_at_tangent() {
			int b = _mcm_qbase();
			vec3 p0 = _mcm_vpos(b), p1 = _mcm_vpos(b + 1), p2 = _mcm_vpos(b + 2);
			vec2 t0 = _mcm_vuv(b), t1 = _mcm_vuv(b + 1), t2 = _mcm_vuv(b + 2);
			vec3 e1 = p1 - p0, e2 = p2 - p0;
			vec2 d1 = t1 - t0, d2 = t2 - t0;
			float r = d1.x * d2.y - d2.x * d1.y;
			float s = r < 0.0 ? -1.0 : 1.0;
			vec3 n = _mcm_quadNormal();
			vec3 t = (e1 * d2.y - e2 * d1.y) * s;
			vec3 bt = (e2 * d1.x - e1 * d2.x) * s;
			t = t - n * dot(n, t);
			float l = length(t);
			t = l > 1e-6 ? t / l : vec3(1.0, 0.0, 0.0);
			return vec4(t, dot(cross(n, t), bt) < 0.0 ? -1.0 : 1.0);
		}
		vec4 _mcm_pulled_mc_midTexCoord() {
			int b = _mcm_qbase();
			return vec4((_mcm_vuv(b) + _mcm_vuv(b + 1) + _mcm_vuv(b + 2) + _mcm_vuv(b + 3)) * 0.25, 0.0, 1.0);
		}
		vec3 _mcm_pulled_at_midBlock() {
			int b = _mcm_qbase();
			vec3 c = (_mcm_vpos(b) + _mcm_vpos(b + 1) + _mcm_vpos(b + 2) + _mcm_vpos(b + 3)) * 0.25;
			vec3 center = floor(c - _mcm_quadNormal() * 0.01) + 0.5;
			return (center - _mcm_vpos(gl_VertexIndex)) * 64.0;
		}
		vec4 _mcm_pulled_mc_Entity() {
			int id = ((_mcm_in_UV2.x >> 8) & 0xFF) | (((_mcm_in_UV2.y >> 8) & 0x7F) << 8);
			return vec4(float(id - 1), -1.0, 0.0, 0.0);
		}
		""";

	/** Pack vertex attributes beyond OptiFine's built-ins; supplied by extended vertex data when available. */
	public static final Set<String> PACK_ATTRIBUTES = Set.of("mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock", "at_velocity", "mc_chunkFade");

	private final VanillaLayout layout;
	private final @Nullable String alphaTest;
	private final boolean terrain;
	private final Set<String> extended;
	private final boolean hand;
	private final boolean shadow;
	private final boolean pulling;

	/** Name of the storage buffer the vertex stage reads its quad's vertices from (bound to {@link #VERTEX_DATA_SLOT}). */
	public static final String VERTEX_DATA = "_mcm_VertexData";
	public static final int VERTEX_DATA_SLOT = 19;

	/**
	 * @param alphaTest a GLSL condition on {@code _mcm_alpha} that keeps a fragment (e.g. "_mcm_alpha > 0.1"), or null
	 * @param extended  pack attributes that the vertex data actually carries (as vertex inputs of the same name)
	 */
	public GbuffersEnvironment(final VanillaLayout layout, final @Nullable String alphaTest, final Set<String> extended) {
		this(layout, alphaTest, extended, false);
	}

	/** @param hand compresses depth into the nearest eighth (OptiFine's MC_HAND_DEPTH) so the hand stays in front */
	public GbuffersEnvironment(final VanillaLayout layout, final @Nullable String alphaTest, final Set<String> extended, final boolean hand) {
		this(layout, alphaTest, extended, hand, false);
	}

	/**
	 * @param shadow renders from the shadow light: the game's model-view (view rotation, camera-relative positions) is
	 *               re-aimed by {@code _mcm_shadowFix} (shadowModelView * gbufferModelViewInverse) and the projection is
	 *               the shadow projection, both written into the default uniform block each draw
	 */
	public GbuffersEnvironment(final VanillaLayout layout, final @Nullable String alphaTest, final Set<String> extended, final boolean hand,
		final boolean shadow) {
		this.shadow = shadow;
		this.hand = hand;
		this.layout = layout;
		this.alphaTest = alphaTest;
		this.terrain = layout.uniforms().containsKey("TerrainUniform");
		// The game's BLOCK vertex format (Position f32x3, Color u8x4, UV0 f32x2, UV2 s16x2; 28 bytes) lets the shader read
		// the other vertices of its quad: normal, tangent, midTexCoord and midBlock are derived from them.
		this.pulling = this.terrain && this.attribute("Position") != null && this.attribute("UV0") != null && this.attribute("UV2") != null
			&& this.attribute("Normal") == null;
		this.extended = extended;
	}

	public VanillaLayout layout() {
		return this.layout;
	}

	/** Uniform block binding name for a vanilla block (see {@link PackCompiler.BindingPlan#block}). */
	public static String blockName(final String vanilla) {
		return "_mcmV_" + vanilla;
	}

	@Override
	public Set<String> providedAttributes() {
		Set<String> provided = new HashSet<>(PACK_ATTRIBUTES);
		provided.addAll(this.extended);
		provided.addAll(Environments.CORE_ATTRIBUTES);
		return provided;
	}

	@Override
	public Set<String> providedUniforms() {
		return Environments.CORE_UNIFORMS;
	}

	private boolean has(final String uniform) {
		return this.layout.uniforms().containsKey(uniform);
	}

	private @Nullable Attribute attribute(final String name) {
		return this.layout.attributes().get(name);
	}

	/** An attribute converted to a float vector of {@code n} components (padding with 0, w = 1). */
	private String attr(final String name, final int n) {
		Attribute a = this.attribute(name);
		if (a == null) {
			return null;
		}
		String type = a.glslType();
		int have = type.endsWith("vec2") ? 2 : type.endsWith("vec3") ? 3 : type.endsWith("vec4") ? 4 : 1;
		String value = "_mcm_in_" + name;
		String f = type.startsWith("i") || type.startsWith("u") ? "vec" + (have == 1 ? "" : have) : null;
		if (f != null) {
			value = (have == 1 ? "float" : f) + "(" + value + ")";
		}
		if (have == n) {
			return value;
		}
		if (have > n) {
			return "(" + value + ")." + "xyzw".substring(0, n);
		}
		StringBuilder sb = new StringBuilder("vec").append(n).append('(').append(value);
		for (int i = have; i < n; i++) {
			sb.append(i == 3 ? ", 1.0" : ", 0.0");
		}
		return sb.append(')').toString();
	}

	@Override
	public String prelude(final GlslTransformer.Stage stage, final Set<String> used) {
		StringBuilder sb = new StringBuilder();
		Set<String> blocks = new HashSet<>();
		boolean vertex = stage == GlslTransformer.Stage.VERTEX;

		// Model-view and projection are needed by both stages (old GLSL allows gl_ModelViewMatrix in fragment shaders).
		String modelView = "mat4(1.0)";
		if (this.terrain) {
			blocks.add("TerrainUniform");
			modelView = "_mcmV_TerrainUniform_i.ModelViewMat";
		} else if (this.has("DynamicTransforms")) {
			blocks.add("DynamicTransforms");
			modelView = "_mcmV_DynamicTransforms_i.ModelViewMat";
		}
		String projection = "mat4(1.0)";
		if (this.has("Projection")) {
			blocks.add("Projection");
			projection = "(mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, -2.0, 0.0, 0.0, 0.0, 1.0, 1.0) * _mcmV_Projection_i.ProjMat)";
		}
		if (this.shadow) {
			sb.append("uniform mat4 _mcm_shadowFix;\nuniform mat4 _mcm_shadowProj;\n");
			modelView = "(_mcm_shadowFix * " + modelView + ")";
			projection = "_mcm_shadowProj";
		}
		sb.append("#define _mcm_ModelViewMatrix ").append(modelView).append('\n');
		sb.append("#define _mcm_ProjectionMatrix ").append(projection).append('\n');
		if (used.contains("_mcm_TextureMatrix")) {
			String textureMat = this.has("DynamicTransforms") ? "_mcmV_DynamicTransforms_i.TextureMat" : "mat4(1.0)";
			if (this.has("DynamicTransforms")) {
				blocks.add("DynamicTransforms");
			}
			sb.append("#define _mcm_TextureMatrix _mcm_textureMatrix()\n");
			sb.append("const mat4 _mcm_lightmapMatrix = mat4(0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.0, 0.0, 0.0, 0.00390625, 0.0, 0.03125, 0.03125, 0.03125, 1.0);\n");
			// Declared after the blocks below; a function keeps the macro usable as "gl_TextureMatrix[i]".
			sb.append("mat4[8] _mcm_textureMatrix();\n");
			this.deferred = "mat4[8] _mcm_textureMatrix() { return mat4[8](" + textureMat
				+ ", _mcm_lightmapMatrix, _mcm_lightmapMatrix, mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0), mat4(1.0)); }\n";
		} else {
			this.deferred = "";
		}

		if (vertex) {
			// Vertex inputs of the game's format.
			for (Attribute attribute : this.layout.attributes().values()) {
				sb.append("layout(location = ").append(attribute.location()).append(") in ").append(attribute.glslType()).append(" _mcm_in_").append(attribute.name()).append(";\n");
			}
			String position = this.attribute("Position") != null ? this.attr("Position", 3) : "vec3(0.0)";
			if (this.terrain) {
				blocks.add("Globals");
				String chunk;
				if (this.attribute("ChunkPosition") != null) {
					chunk = "_mcm_in_ChunkPosition";
				} else {
					blocks.add("ChunkSection");
					chunk = "_mcmV_ChunkSection_i.ChunkPosition";
				}
				position = "(" + position + " + vec3(" + chunk + " - _mcmV_Globals_i.CameraBlockPos) + _mcmV_Globals_i.CameraOffset)";
			}
			sb.append("#define _mcm_Vertex vec4(").append(position).append(", 1.0)\n");
			String color = this.attribute("Color") != null ? this.attr("Color", 4) : "vec4(1.0)";
			if (this.has("DynamicTransforms")) {
				blocks.add("DynamicTransforms");
				color = "(" + color + " * _mcmV_DynamicTransforms_i.ColorModulator)";
			}
			sb.append("#define _mcm_Color ").append(color).append('\n');
			sb.append("#define _mcm_MultiTexCoord0 ").append(this.attribute("UV0") != null ? "vec4(" + this.attr("UV0", 2) + ", 0.0, 1.0)" : "vec4(0.0, 0.0, 0.0, 1.0)").append('\n');
			// Terrain light coordinates carry the block ID in their high bytes (see BlockIds); the light is the low byte.
			String lightmap = this.attribute("UV2") != null ? (this.pulling ? "vec4(vec2(_mcm_in_UV2 & 0xFF), 0.0, 1.0)" : "vec4(" + this.attr("UV2", 2) + ", 0.0, 1.0)")
				: "vec4(240.0, 240.0, 0.0, 1.0)";
			sb.append("#define _mcm_MultiTexCoord1 ").append(lightmap).append('\n');
			sb.append("#define _mcm_MultiTexCoord2 ").append(lightmap).append('\n');
			for (int i = 3; i < 8; i++) {
				sb.append("#define _mcm_MultiTexCoord").append(i).append(" vec4(0.0, 0.0, 0.0, 1.0)\n");
			}
			String normal = this.attribute("Normal") != null ? this.attr("Normal", 3) : this.pulling ? "_mcm_quadNormal()" : "vec3(0.0, 1.0, 0.0)";
			sb.append("#define _mcm_Normal ").append(normal).append('\n');
			if (this.pulling) {
				sb.append(PULLING);
			}
			// Pack attributes the vertex data doesn't carry get OptiFine's defaults.
			for (String name : PACK_ATTRIBUTES) {
				if (this.pulling && PULLED.contains(name) && used.contains(name)) {
					sb.append("#define ").append(name).append(" _mcm_pulled_").append(name).append("()\n");
					continue;
				}
				if (!this.extended.contains(name) && used.contains(name)) {
					String fallback = switch (name) {
						case "mc_Entity" -> "vec4(-1.0, -1.0, 0.0, 0.0)";
						case "mc_midTexCoord" -> "vec4(0.0, 0.0, 0.0, 1.0)";
						case "at_tangent" -> "vec4(1.0, 0.0, 0.0, 1.0)";
						case "at_midBlock" -> "vec3(0.0)";
						case "at_velocity" -> "vec3(0.0)";
						default -> "1.0";
					};
					String type = switch (name) {
						case "mc_Entity", "mc_midTexCoord", "at_tangent" -> "vec4";
						case "at_midBlock", "at_velocity" -> "vec3";
						default -> "float";
					};
					sb.append("#define ").append(name).append(' ').append(type.equals("float") ? fallback : type + "(" + fallback + ")").append('\n');
				}
			}
		}

		for (String block : blocks) {
			sb.append("layout(std140) uniform ").append(blockName(block)).append(" { ").append(BLOCKS.get(block)).append(" } ").append(blockName(block)).append("_i;\n");
		}
		sb.append(this.deferred);
		if (vertex) {
			sb.append("vec4 ftransform() { return _mcm_ProjectionMatrix * (_mcm_ModelViewMatrix * _mcm_Vertex); }\n");
		}
		Environments.matrices(sb, used);
		Environments.coreBuiltins(sb, used, vertex, this.alphaRef());
		return sb.toString();
	}

	private String deferred = "";

	private float alphaRef() {
		if (this.alphaTest == null) {
			return 0.0F;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9.]+)\\s*$").matcher(this.alphaTest);
		return m.find() ? Float.parseFloat(m.group(1)) : 0.1F;
	}

	@Override
	public String epilogue(final GlslTransformer.Stage stage, final Set<String> used) {
		if (stage == GlslTransformer.Stage.VERTEX) {
			return Environments.DEPTH_FIXUP + (this.hand && !this.shadow ? "\tgl_Position.z *= 0.125;\n" : "");
		}
		if (this.alphaTest != null && used.contains("_mcm_FragData0")) {
			return "\tif (!(" + this.alphaTest.replace("_mcm_alpha", "_mcm_FragData0.a") + ")) discard;\n";
		}
		return "";
	}

	/** Translates an OptiFine alpha test ("GREATER 0.1", "off") to a condition on {@code _mcm_alpha}, or null. */
	public static @Nullable String alphaCondition(final @Nullable String property) {
		if (property == null) {
			return null;
		}
		String[] parts = property.trim().split("\\s+");
		if (parts.length == 0 || parts[0].equalsIgnoreCase("off") || parts[0].equalsIgnoreCase("false")) {
			return null;
		}
		String ref = parts.length > 1 ? parts[1] : "0.1";
		String op = switch (parts[0].toUpperCase()) {
			case "NEVER" -> "false";
			case "LESS" -> "_mcm_alpha < " + ref;
			case "EQUAL" -> "_mcm_alpha == " + ref;
			case "LEQUAL" -> "_mcm_alpha <= " + ref;
			case "NOTEQUAL" -> "_mcm_alpha != " + ref;
			case "GEQUAL" -> "_mcm_alpha >= " + ref;
			case "ALWAYS" -> "true";
			default -> "_mcm_alpha > " + ref;
		};
		return op;
	}
}
