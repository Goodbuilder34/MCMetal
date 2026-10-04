package com.mcmetal.shaderpack;

import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalRenderPipeline;
import com.mcmetal.metal.PackBackend;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Compiles a pack's programs into Metal pipelines: full-screen passes (prepare/deferred/composite/final) and
 * gbuffers/shadow variants of the game's world pipelines. Thread-safe: compilation runs on worker threads.
 */
public final class PackPrograms {
	/** Fixed Metal texture slots for pack textures (game-bound textures keep their own low slots). */
	public static final int SLOT_COLORTEX = 32;
	public static final int SLOT_DEPTHTEX = 48;
	public static final int SLOT_SHADOWTEX = 51;
	public static final int SLOT_SHADOWCOLOR = 53;
	public static final int SLOT_NOISE = 55;
	public static final int SLOT_NORMALS = 56;
	public static final int SLOT_SPECULAR = 57;
	public static final int SLOT_WHITE = 58;
	public static final int SLOT_LIGHTMAP_FALLBACK = 59;
	public static final int SLOT_CUSTOM = 60;
	public static final int SLOT_UNBOUND = 127;

	/** A texture a compiled program samples: its Metal slot, which stages use it, and what it reads. */
	public record TextureUse(int slot, int stages, String canonical) {
	}

	/** A compiled program: the Metal pipeline plus what to bind for it. */
	public static final class Compiled {
		public final String name;
		public final long pipeline;
		public final PackCompiler.@Nullable UniformBlock vertexBlock;
		public final PackCompiler.@Nullable UniformBlock fragmentBlock;
		public final List<TextureUse> textures;
		/** Full-screen passes: the colortex indices written (attachment order). Gbuffers: the program's draw buffers. */
		public final int[] drawBuffers;
		/** Colortex inputs with mipmaps enabled for this program. */
		public final int[] mipmapped;
		/** Full-screen passes: whether every pixel of every attachment is overwritten, so old contents needn't be loaded. */
		public boolean overwrites;
		public int renderStage;
		public @Nullable MetalRenderPipeline wrapped;

		Compiled(final String name, final long pipeline, final PackCompiler.Stage vs, final PackCompiler.Stage fs, final List<TextureUse> textures,
			final int[] drawBuffers, final int[] mipmapped) {
			this.name = name;
			this.pipeline = pipeline;
			this.vertexBlock = vs.defaults();
			this.fragmentBlock = fs.defaults();
			this.textures = textures;
			this.drawBuffers = drawBuffers;
			this.mipmapped = mipmapped;
		}
	}

	private final PackBackend backend;
	private final ProgramSet set;
	private final RenderTargets targets;
	private final Map<String, Integer> customTextures;
	private final List<String> errors;
	/** Colortex indices attached during gbuffers rendering (union of the gbuffers programs' draw buffers). */
	public final int[] worldBuffers;
	/** Whether the gbuffers write more buffers than a pass can attach: each program then attaches its own. */
	public final boolean perProgramAttachments;
	/** colortex buffers replaced by custom textures in a stage, as "stage:colortexN" (sampled with repeat). */
	public final Set<String> overrides = new java.util.HashSet<>();
	public final boolean shadowHardwareFiltering;

	public PackPrograms(final PackBackend backend, final ProgramSet set, final RenderTargets targets, final Map<String, Integer> customTextures,
		final List<String> errors) {
		this.backend = backend;
		this.set = set;
		this.targets = targets;
		this.customTextures = customTextures;
		this.errors = errors;
		TreeSet<Integer> union = new TreeSet<>();
		for (String name : ProgramSet.GBUFFERS) {
			ProgramSet.Program program = set.get(name);
			if (program == null) {
				continue;
			}
			List<Integer> buffers = program.drawBuffers();
			if (buffers == null) {
				union.add(0);
			} else {
				union.addAll(buffers);
			}
		}
		union.add(0);
		List<Integer> list = new ArrayList<>(union);
		for (int b : list) {
			if (b >= 0 && b < RenderTargets.COLORTEX) {
				targets.buffers[b].used = true;
			}
		}
		this.perProgramAttachments = list.size() > 8;
		this.worldBuffers = list.stream().filter(b -> b >= 0 && b < RenderTargets.COLORTEX).limit(8).mapToInt(Integer::intValue).toArray();
		String filtering = set.constants().get("shadowHardwareFiltering");
		this.shadowHardwareFiltering = filtering != null && Boolean.parseBoolean(filtering.trim());
	}

	public ProgramSet set() {
		return this.set;
	}

	// ---------------------------------------------------------------------------------------------
	// Binding plan shared by all programs
	// ---------------------------------------------------------------------------------------------

	private final class Plan implements PackCompiler.BindingPlan {
		private final GbuffersEnvironment.@Nullable VanillaLayout layout;
		private final boolean waterShadow;
		private final Set<Integer> mipmappedInputs;
		final Map<String, String> canonical = new HashMap<>();
		String stage = "gbuffers";

		Plan(final GbuffersEnvironment.@Nullable VanillaLayout layout, final boolean waterShadow, final Set<Integer> mipmappedInputs) {
			this.layout = layout;
			this.waterShadow = waterShadow;
			this.mipmappedInputs = mipmappedInputs;
		}

		private String canonical(final String name) {
			return this.canonical.computeIfAbsent(name, n -> GlslTransformer.canonicalSampler(n, this.waterShadow));
		}

		@Override
		public int texture(final String name) {
			String c = this.canonical(name);
			if (c.equals("gtexture")) {
				Integer index = this.layout != null ? this.layout.uniforms().get("Sampler0") : null;
				return index != null ? index : SLOT_WHITE;
			}
			if (c.equals("lightmap")) {
				Integer index = this.layout != null ? this.layout.uniforms().get("Sampler2") : null;
				return index != null ? index : SLOT_LIGHTMAP_FALLBACK;
			}
			return slotOf(c, PackPrograms.this.customTextures);
		}

		@Override
		public int sampler(final String name, final boolean depthCompare) {
			// Game-bound textures use the sampler the game binds at the same index.
			return this.texture(name);
		}

		private static final PackCompiler.SamplerSpec GTEXTURE = new PackCompiler.SamplerSpec(false, 2, true, false);

		@Override
		public PackCompiler.@Nullable SamplerSpec samplerState(final String name, final boolean depthCompare) {
			String c = this.canonical(name);
			if (c.equals("gtexture")) {
				// The game binds a linear sampler and filters in its shaders; packs expect GL_NEAREST (with mipmaps).
				return GTEXTURE;
			}
			if (c.equals("lightmap")) {
				return PackCompiler.SamplerSpec.LINEAR_CLAMP;
			}
			if (c.startsWith("shadowtex") || c.startsWith("depthtex")) {
				if (depthCompare) {
					return PackPrograms.this.shadowHardwareFiltering ? PackCompiler.SamplerSpec.COMPARE_LINEAR : PackCompiler.SamplerSpec.COMPARE_NEAREST;
				}
				return PackCompiler.SamplerSpec.NEAREST_CLAMP;
			}
			if (c.equals("noisetex") || c.equals("normals") || c.equals("specular") || PackPrograms.this.customTextures.containsKey(c)) {
				return PackCompiler.SamplerSpec.LINEAR_REPEAT;
			}
			if (PackPrograms.this.overrides.contains(this.stage + ":" + c)) {
				return PackCompiler.SamplerSpec.LINEAR_REPEAT;
			}
			if (c.startsWith("colortex")) {
				int index = parseIndex(c, "colortex");
				if (index >= 0 && this.mipmappedInputs.contains(index)) {
					return new PackCompiler.SamplerSpec(true, 2, false, false);
				}
			}
			return PackCompiler.SamplerSpec.LINEAR_CLAMP;
		}

		@Override
		public int block(final String name) {
			if (this.layout == null || !name.startsWith("_mcmV_")) {
				return -1;
			}
			Integer index = this.layout.uniforms().get(name.substring("_mcmV_".length()));
			return index != null ? index : -1;
		}
	}

	static int parseIndex(final String name, final String prefix) {
		if (!name.startsWith(prefix)) {
			return -1;
		}
		try {
			return Integer.parseInt(name.substring(prefix.length()));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	static int slotOf(final String canonical, final Map<String, Integer> custom) {
		int colortex = parseIndex(canonical, "colortex");
		if (colortex >= 0 && colortex < RenderTargets.COLORTEX) {
			return SLOT_COLORTEX + colortex;
		}
		int depthtex = parseIndex(canonical, "depthtex");
		if (depthtex >= 0 && depthtex < 3) {
			return SLOT_DEPTHTEX + depthtex;
		}
		int shadowtex = parseIndex(canonical, "shadowtex");
		if (shadowtex >= 0 && shadowtex < 2) {
			return SLOT_SHADOWTEX + shadowtex;
		}
		int shadowcolor = parseIndex(canonical, "shadowcolor");
		if (shadowcolor >= 0 && shadowcolor < 2) {
			return SLOT_SHADOWCOLOR + shadowcolor;
		}
		if (canonical.equals("shadowcolor")) {
			return SLOT_SHADOWCOLOR;
		}
		Integer customSlot = custom.get(canonical);
		if (customSlot != null) {
			return customSlot;
		}
		return switch (canonical) {
			case "noisetex" -> SLOT_NOISE;
			case "normals" -> SLOT_NORMALS;
			case "specular" -> SLOT_SPECULAR;
			default -> SLOT_UNBOUND;
		};
	}

	// ---------------------------------------------------------------------------------------------
	// Full-screen passes
	// ---------------------------------------------------------------------------------------------

	/** Compiles a full-screen pass. {@code finalPass} renders to the screen (one RGBA8 attachment). */
	public @Nullable Compiled compileFullscreen(final ProgramSet.Program program, final boolean finalPass, final GpuFormat screenFormat) {
		try {
			Set<Integer> mips = this.mipmapInputs(program);
			Preprocessor.Result vs = program.vertex();
			Preprocessor.Result fs = program.fragment();
			Map<String, GlslTransformer.Varying> varyings = GlslTransformer.vertexOutputs(vs.source(), vs.version());
			Map<String, Integer> locations = GlslTransformer.assignLocations(varyings);
			GlslTransformer.Result tvs = new GlslTransformer(GlslTransformer.Stage.VERTEX, Environments.COMPOSITE, vs.version()).transform(vs.source(), locations,
				varyings, new int[0]);
			int[] drawBuffers;
			GlslTransformer.Result tfs;
			if (finalPass) {
				drawBuffers = new int[]{0};
				tfs = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, Environments.COMPOSITE, fs.version()).transform(fs.source(), locations, varyings, new int[]{0});
			} else {
				List<Integer> declared = program.drawBuffers();
				if (declared == null) {
					// Without a directive, gl_FragData[i] writes colortex i.
					GlslTransformer.Result probe = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, Environments.COMPOSITE, fs.version()).transform(fs.source(),
						locations, varyings, new int[]{0, 1, 2, 3, 4, 5, 6, 7});
					declared = new ArrayList<>(new TreeSet<>(probe.outputLocations()));
					if (declared.isEmpty()) {
						declared = List.of(0);
					}
					int[] remap = new int[8];
					java.util.Arrays.fill(remap, -1);
					for (int i = 0; i < declared.size(); i++) {
						remap[declared.get(i)] = i;
					}
					tfs = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, Environments.COMPOSITE, fs.version()).transform(fs.source(), locations, varyings, remap);
				} else {
					int[] remap = new int[declared.size()];
					for (int i = 0; i < remap.length; i++) {
						remap[i] = i;
					}
					tfs = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, Environments.COMPOSITE, fs.version()).transform(fs.source(), locations, varyings, remap);
				}
				drawBuffers = declared.stream().filter(b -> b >= 0 && b < RenderTargets.COLORTEX).mapToInt(Integer::intValue).toArray();
			}
			Plan plan = new Plan(null, tfs.usedIdentifiers().contains("watershadow"), mips);
			String n = program.name();
			plan.stage = n.startsWith("deferred") ? "deferred" : n.startsWith("prepare") ? "prepare" : n.startsWith("composite") || n.equals("final") ? "composite" : "debug";
			String label = this.set.folder() + "/" + program.name();
			PackCompiler.Stage v = PackCompiler.compile(label + ".vsh", tvs.source(), PackCompiler.ShaderStage.VERTEX, plan);
			PackCompiler.Stage f = PackCompiler.compile(label + ".fsh", tfs.source(), PackCompiler.ShaderStage.FRAGMENT, plan);

			int[] descriptor = new int[11 + drawBuffers.length * 9];
			descriptor[0] = drawBuffers.length;
			descriptor[8] = 3;  // triangles
			boolean blends = false;
			for (int i = 0; i < drawBuffers.length; i++) {
				GpuFormat format = finalPass ? screenFormat : this.targets.buffers[drawBuffers[i]].format;
				int[] blend = this.blendFor(program.name(), finalPass ? -1 : drawBuffers[i], null);
				blends |= blend[0] != 0;
				int base = 11 + i * 9;
				descriptor[base] = PackBackend.formatCode(format);
				descriptor[base + 1] = 15;
				System.arraycopy(blend, 0, descriptor, base + 2, 7);
				if (!finalPass) {
					this.targets.buffers[drawBuffers[i]].used = true;
				}
			}
			dump(label, tvs.source(), tfs.source(), v, f);
			long pipeline = this.backend.createPipeline(v.msl(), v.entryPoint(), f.msl(), f.entryPoint(), descriptor, label);
			for (TextureUse use : textures(v, f, plan)) {
				int colortex = parseIndex(use.canonical(), "colortex");
				if (colortex >= 0 && colortex < RenderTargets.COLORTEX) {
					this.targets.buffers[colortex].used = true;
				}
			}
			Compiled compiled = new Compiled(program.name(), pipeline, v, f, textures(v, f, plan), drawBuffers, mips.stream().mapToInt(Integer::intValue).toArray());
			compiled.overwrites = !blends && drawBuffers.length > 0 && writesAll(tfs.outputLocations(), drawBuffers.length) && !DISCARD.matcher(fs.source()).find()
				&& fullscreenVertex(vs.source());
			return compiled;
		} catch (RuntimeException e) {
			this.error(program.name(), e);
			return null;
		}
	}

	private static final java.util.regex.Pattern DISCARD = java.util.regex.Pattern.compile("\\bdiscard\\b");
	private static final java.util.regex.Pattern DISCARD_STATEMENT = java.util.regex.Pattern.compile("\\bdiscard\\s*;");
	private static final boolean OPTIMIZE_GBUFFERS = Boolean.getBoolean("mcmetal.pack.optimizeGbuffers");
	private static final boolean KEEP_SOLID_DISCARD = Boolean.getBoolean("mcmetal.pack.keepSolidDiscard");

	/**
	 * Solid terrain without its discards. Packs share one gbuffers_terrain program between solid and cutout blocks,
	 * so its alpha test (and cosmetic dither fades) also land in solid terrain, where block textures are opaque and
	 * the test never passes. Any discard in a fragment function turns off the GPU's hidden-surface removal, so every
	 * overdrawn terrain pixel would be shaded in full; without one, only the visible surface is.
	 */
	private static GlslTransformer.Result withoutDiscard(final GlslTransformer.Result result) {
		if (gl_FragDepthWritten(result.source())) {
			return result;  // writes depth itself: hidden-surface removal is off anyway
		}
		String source = DISCARD_STATEMENT.matcher(result.source()).replaceAll(";");
		return new GlslTransformer.Result(source, result.usedIdentifiers(), result.outputLocations());
	}

	private static boolean gl_FragDepthWritten(final String source) {
		return java.util.regex.Pattern.compile("\\bgl_FragDepth\\s*=(?!=)").matcher(source).find();
	}
	private static final java.util.regex.Pattern POSITION_WRITE = java.util.regex.Pattern.compile("\\bgl_Position\\s*(\\.\\s*\\w+\\s*)?([-+*/]?=)(?!=)([^;]*);");
	private static final java.util.regex.Pattern STANDARD_POSITION = java.util.regex.Pattern.compile(
		"ftransform\\s*\\(\\s*\\)|gl_ModelViewProjectionMatrix\\s*\\*\\s*gl_Vertex|gl_ProjectionMatrix\\s*\\*\\s*\\(?\\s*gl_ModelViewMatrix\\s*\\*\\s*gl_Vertex\\s*\\)?"
			+ "|vec4\\s*\\(\\s*gl_Vertex\\.xy\\s*\\*\\s*2\\.0\\s*-\\s*1\\.0\\s*,\\s*0\\.0\\s*,\\s*1\\.0\\s*\\)");

	/** Whether the shader writes each of the first {@code count} attachments (unconditional writes can't be told apart). */
	private static boolean writesAll(final List<Integer> locations, final int count) {
		for (int i = 0; i < count; i++) {
			if (!locations.contains(i)) {
				return false;
			}
		}
		return true;
	}

	/** Whether a full-screen vertex shader keeps the standard full-screen triangle (packs may shrink it to draw part of a buffer). */
	private static boolean fullscreenVertex(final String source) {
		java.util.regex.Matcher m = POSITION_WRITE.matcher(source);
		boolean any = false;
		while (m.find()) {
			if (m.group(1) != null || !m.group(2).equals("=") || !STANDARD_POSITION.matcher(m.group(3).trim()).matches()) {
				return false;
			}
			any = true;
		}
		return any;
	}

	private Set<Integer> mipmapInputs(final ProgramSet.Program program) {
		Set<Integer> mips = new TreeSet<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("const\\s+bool\\s+(colortex\\d+|gaux\\d|gcolor|gdepth|gnormal|composite)MipmapEnabled\\s*=\\s*true")
			.matcher(program.fragment().source());
		while (m.find()) {
			String c = GlslTransformer.canonicalSampler(m.group(1), false);
			int index = parseIndex(c, "colortex");
			if (index >= 0 && this.targets.buffers[index].mipmapped) {
				mips.add(index);
			}
		}
		return mips;
	}

	private static List<TextureUse> textures(final PackCompiler.Stage v, final PackCompiler.Stage f, final Plan plan) {
		Map<Integer, TextureUse> uses = new LinkedHashMap<>();
		for (int stage = 0; stage < 2; stage++) {
			for (PackCompiler.TextureBinding binding : (stage == 0 ? v : f).textures()) {
				int slot = binding.texture();
				if (slot < 16) {
					continue;  // bound by the game
				}
				TextureUse existing = uses.get(slot);
				int mask = (existing != null ? existing.stages() : 0) | (stage == 0 ? 1 : 2);
				uses.put(slot, new TextureUse(slot, mask, plan.canonical(binding.name())));
			}
		}
		return new ArrayList<>(uses.values());
	}

	/** 7 ints: enabled, color op, alpha op, src color, dst color, src alpha, dst alpha. */
	private int[] blendFor(final String program, final int buffer, final int @Nullable [] fallback) {
		String value = null;
		if (buffer >= 0) {
			value = this.set.properties().get("blend." + program + ".colortex" + buffer);
			if (value == null && buffer < 8) {
				value = this.set.properties().get("blend." + program + "." + new String[]{"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"}[buffer]);
			}
		}
		if (value == null) {
			value = this.set.properties().get("blend." + program);
		}
		if (value == null) {
			return fallback != null ? fallback : new int[7];
		}
		String[] parts = value.trim().split("\\s+");
		if (parts.length < 4 || parts[0].equalsIgnoreCase("off")) {
			return new int[7];
		}
		return new int[]{1, 0, 0, factor(parts[0]), factor(parts[1]), factor(parts[2]), factor(parts[3])};
	}

	private static int factor(final String name) {
		try {
			return PackBackend.blendFactorCode(BlendFactor.valueOf(name.toUpperCase()));
		} catch (IllegalArgumentException e) {
			return PackBackend.blendFactorCode(BlendFactor.ONE);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Gbuffers variants of game pipelines
	// ---------------------------------------------------------------------------------------------

	/** Which pack program draws a game pipeline, and its render stage; null to skip the pipeline. */
	public record Mapping(String program, int renderStage, @Nullable String alphaTest) {
	}

	public static @Nullable Mapping map(final String pipelineName, final boolean hand, final boolean shadow) {
		String n = pipelineName.startsWith("minecraft:pipeline/") ? pipelineName.substring("minecraft:pipeline/".length()) : pipelineName;
		if (n.startsWith("gui") || n.startsWith("blit") || n.startsWith("oit") || n.equals("integrate_depth") || n.equals("lightmap") || n.startsWith("animate_sprite")
			|| n.equals("entity_outline_blit") || n.equals("tracy_blit") || n.equals("panorama") || n.equals("crosshair") || n.equals("vignette")
			|| n.endsWith("screen_effect") || n.equals("mojang_logo") || n.equals("gui_nausea_overlay") || n.startsWith("outline_")) {
			return null;
		}
		boolean translucent = n.contains("translucent");
		boolean glint = n.contains("glint");
		String cutout = "_mcm_alpha > 0.1";
		if (shadow) {
			if (n.equals("sky") || n.equals("end_sky") || n.equals("sunrise_sunset") || n.equals("stars") || n.equals("celestial") || n.contains("clouds")
				|| n.equals("weather") || n.startsWith("lines") || n.contains("particle") || glint || n.equals("world_border") || n.startsWith("debug")
				|| n.equals("entity_shadow") || n.contains("water_mask") || n.equals("crumbling")) {
				return null;
			}
			String program = n.contains("cutout_terrain") ? "shadow_cutout" : n.contains("solid_terrain") ? "shadow_solid" : "shadow";
			return new Mapping(program, 0, n.contains("cutout") || (n.contains("terrain") && !n.contains("solid")) || n.contains("entity") ? cutout : null);
		}
		if (hand) {
			if (glint) {
				return new Mapping("gbuffers_armor_glint", 23, null);
			}
			return new Mapping(translucent ? "gbuffers_hand_water" : "gbuffers_hand", translucent ? 23 : 16, translucent ? null : cutout);
		}
		if (glint) {
			return new Mapping("gbuffers_armor_glint", 11, null);
		}
		if (n.startsWith("solid_terrain") || n.startsWith("wireframe")) {
			return new Mapping("gbuffers_terrain_solid", 8, null);
		}
		if (n.startsWith("cutout_terrain")) {
			return new Mapping("gbuffers_terrain_cutout", 10, cutout);
		}
		if (n.startsWith("translucent_terrain")) {
			return new Mapping("gbuffers_water", 17, null);
		}
		if (n.equals("solid_block") || n.equals("cutout_block") || n.equals("end_portal") || n.equals("end_gateway")) {
			return new Mapping("gbuffers_block", 12, n.startsWith("cutout") ? cutout : null);
		}
		if (n.equals("translucent_block")) {
			return new Mapping("gbuffers_block_translucent", 12, null);
		}
		if (n.equals("eyes")) {
			return new Mapping("gbuffers_spidereyes", 11, null);
		}
		if (n.equals("entity_shadow") || n.equals("world_border")) {
			return new Mapping("gbuffers_textured", n.equals("world_border") ? 22 : 11, null);
		}
		if (n.startsWith("beacon_beam")) {
			return new Mapping("gbuffers_beaconbeam", 12, null);
		}
		if (n.equals("crumbling")) {
			return new Mapping("gbuffers_damagedblock", 13, null);
		}
		if (n.equals("lightning")) {
			return new Mapping("gbuffers_lightning", 11, null);
		}
		if (n.contains("clouds")) {
			return new Mapping("gbuffers_clouds", 20, null);
		}
		if (n.startsWith("lines") || n.equals("secondary_block_outline")) {
			return new Mapping("gbuffers_line", 14, null);
		}
		if (n.startsWith("debug") || n.contains("water_mask") || n.equals("leash")) {
			return new Mapping("gbuffers_basic", 15, null);
		}
		if (n.equals("opaque_particle")) {
			return new Mapping("gbuffers_particles", 19, cutout);
		}
		if (n.equals("translucent_particle")) {
			return new Mapping("gbuffers_particles_translucent", 19, null);
		}
		if (n.equals("weather")) {
			return new Mapping("gbuffers_weather", 21, null);
		}
		if (n.equals("sky") || n.equals("sunrise_sunset")) {
			return new Mapping("gbuffers_skybasic", n.equals("sky") ? 1 : 2, null);
		}
		if (n.equals("stars")) {
			return new Mapping("gbuffers_skybasic", 6, null);
		}
		if (n.equals("end_sky") || n.equals("celestial")) {
			return new Mapping("gbuffers_skytextured", n.equals("end_sky") ? 3 : 4, null);
		}
		// Entities, items, armor, text, banners, energy swirls...
		boolean cutoutEntity = n.contains("cutout") || n.startsWith("text") || n.startsWith("banner") || n.startsWith("item");
		return new Mapping(translucent ? "gbuffers_entities_translucent" : "gbuffers_entities", 11, translucent ? null : cutoutEntity ? cutout : null);
	}

	/** The program used for a name: the pack's own, its fallback chain, or our built-in default. */
	public ProgramSet.@Nullable Program resolve(final String name) {
		String current = name;
		while (current != null) {
			ProgramSet.Program program = this.set.get(current);
			if (program != null) {
				return program;
			}
			current = ProgramSet.FALLBACK.get(current);
		}
		return null;
	}

	/** The shadowcolor buffers the shadow programs write ([0] without a directive... Iris writes both). */
	public int[] shadowAttachments() {
		ProgramSet.Program program = this.resolve("shadow");
		List<Integer> buffers = program != null ? program.drawBuffers() : null;
		if (buffers == null) {
			return new int[]{0, 1};
		}
		return buffers.stream().filter(b -> b >= 0 && b < 2).distinct().mapToInt(Integer::intValue).toArray();
	}

	/** The buffers a gbuffers program renders to (its draw buffers in order, at most 8). */
	public int[] attachmentsFor(final String programName) {
		if (!this.perProgramAttachments) {
			return this.worldBuffers;
		}
		ProgramSet.Program program = this.resolve(programName);
		List<Integer> buffers = program != null ? program.drawBuffers() : null;
		if (buffers == null) {
			buffers = List.of(0, 1, 2, 3, 4, 5, 6, 7);
		}
		return buffers.stream().filter(b -> b >= 0 && b < RenderTargets.COLORTEX).distinct().limit(8).mapToInt(Integer::intValue).toArray();
	}

	/** Builds the pack variant of a game world pipeline. */
	public @Nullable Compiled compileVariant(final MetalRenderPipeline vanilla, final Mapping mapping, final boolean hand, final boolean shadow,
		final int[] attachments, final GpuFormat[] attachmentFormats) {
		BackendRenderPipeline.CreateInfo info = vanilla.createInfo();
		if (info == null) {
			return null;
		}
		ProgramSet.Program program = this.resolve(mapping.program());
		boolean builtin = program == null;
		if (builtin) {
			program = shadow ? null : this.defaultProgram(mapping.program());
			if (program == null) {
				return null;
			}
		}
		try {
			GbuffersEnvironment.VanillaLayout layout = layoutOf(info, vanilla.vertexSpirv());
			String alpha = mapping.alphaTest();
			String property = this.set.properties().get("alphaTest." + program.name());
			if (property != null) {
				alpha = GbuffersEnvironment.alphaCondition(property);
			}
			GbuffersEnvironment env = new GbuffersEnvironment(layout, alpha, Set.of(), hand, shadow);
			Preprocessor.Result vs = program.vertex();
			Preprocessor.Result fs = program.fragment();
			Map<String, GlslTransformer.Varying> varyings = GlslTransformer.vertexOutputs(vs.source(), vs.version());
			Map<String, Integer> locations = GlslTransformer.assignLocations(varyings);
			List<Integer> declared = program.drawBuffers();
			if (declared == null) {
				declared = List.of(0, 1, 2, 3, 4, 5, 6, 7);
			}
			int[] remap = new int[declared.size()];
			boolean[] written = new boolean[attachments.length];
			for (int i = 0; i < remap.length; i++) {
				remap[i] = -1;
				for (int a = 0; a < attachments.length; a++) {
					if (attachments[a] == declared.get(i)) {
						remap[i] = a;
						written[a] = true;
					}
				}
			}
			GlslTransformer.Result tvs = new GlslTransformer(GlslTransformer.Stage.VERTEX, env, vs.version()).transform(vs.source(), locations, varyings, new int[0]);
			GlslTransformer.Result tfs = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, env, fs.version()).transform(fs.source(), locations, varyings, remap);
			if (!hand && alpha == null && vanilla.name().contains("solid_terrain") && !KEEP_SOLID_DISCARD) {
				tfs = withoutDiscard(tfs);
			}
			Plan plan = new Plan(layout, tfs.usedIdentifiers().contains("watershadow"), Set.of());
			plan.stage = shadow ? "shadow" : "gbuffers";
			String label = this.set.folder() + "/" + program.name() + " (" + vanilla.name() + ")";
			PackCompiler.Stage v = PackCompiler.compile(label + ".vsh", tvs.source(), PackCompiler.ShaderStage.VERTEX, plan, OPTIMIZE_GBUFFERS);
			PackCompiler.Stage f = PackCompiler.compile(label + ".fsh", tfs.source(), PackCompiler.ShaderStage.FRAGMENT, plan, OPTIMIZE_GBUFFERS);

			// Descriptor: the game's vertex layout, rasterizer and blending; our attachments; OpenGL depth convention.
			int[] original = PackBackend.descriptorOf(vanilla);
			int originalColors = original[0];
			int[] vanillaColor = new int[9];
			if (originalColors > 0) {
				System.arraycopy(original, 11, vanillaColor, 0, 9);
			} else {
				vanillaColor[1] = 0;
			}
			int tail = original.length - (11 + originalColors * 9);
			int[] descriptor = new int[11 + attachments.length * 9 + tail];
			System.arraycopy(original, 0, descriptor, 0, 11);
			descriptor[0] = attachments.length;
			descriptor[2] = flipCompare(original[2]);
			if (shadow) {
				descriptor[6] = 0;  // no culling in the shadow pass (Iris)
			}
			descriptor[4] = Float.floatToRawIntBits(-Float.intBitsToFloat(original[4]));
			descriptor[5] = Float.floatToRawIntBits(-Float.intBitsToFloat(original[5]));
			for (int a = 0; a < attachments.length; a++) {
				int base = 11 + a * 9;
				descriptor[base] = PackBackend.formatCode(attachmentFormats[a]);
				int mask = originalColors > 0 && vanillaColor[0] >= 0 ? vanillaColor[1] : 0;
				descriptor[base + 1] = written[a] ? mask : 0;
				int[] fallback = new int[7];
				System.arraycopy(vanillaColor, 2, fallback, 0, 7);
				int[] blend = this.blendFor(program.name(), shadow ? -1 : attachments[a], fallback);
				System.arraycopy(blend, 0, descriptor, base + 2, 7);
			}
			System.arraycopy(original, 11 + originalColors * 9, descriptor, 11 + attachments.length * 9, tail);
			dump(label, tvs.source(), tfs.source(), v, f);
			long pipeline = this.backend.createPipeline(v.msl(), v.entryPoint(), f.msl(), f.entryPoint(), descriptor, label);

			// The game binds its own uniforms by index; mark which stages read each.
			List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
			int[] stageMasks = new int[uniforms.size()];
			for (int stage = 0; stage < 2; stage++) {
				PackCompiler.Stage s = stage == 0 ? v : f;
				for (String block : s.blocks()) {
					Integer index = layout.uniforms().get(block.substring("_mcmV_".length()));
					if (index != null) {
						stageMasks[index] |= stage == 0 ? 1 : 2;
					}
				}
				for (PackCompiler.TextureBinding binding : s.textures()) {
					if (binding.texture() < uniforms.size()) {
						stageMasks[binding.texture()] |= stage == 0 ? 1 : 2;
					}
				}
			}
			Compiled compiled = new Compiled(program.name(), pipeline, v, f, textures(v, f, plan), attachments, new int[0]);
			compiled.renderStage = mapping.renderStage();
			compiled.wrapped = this.backend.wrap(pipeline, uniforms, stageMasks, vanilla.name(), compiled);
			if (v.pullsVertices()) {
				compiled.wrapped.setVertexPullSlot(GbuffersEnvironment.VERTEX_DATA_SLOT);
			}
			return compiled;
		} catch (RuntimeException e) {
			this.error(program.name() + " for " + vanilla.name(), e);
			return null;
		}
	}

	/** -Dmcmetal.pack.dump=<dir>: writes every compiled program (GLSL after transformation and MSL) for inspection. */
	private static void dump(final String label, final String vertexGlsl, final String fragmentGlsl, final PackCompiler.Stage v, final PackCompiler.Stage f) {
		String dir = System.getProperty("mcmetal.pack.dump");
		if (dir == null) {
			return;
		}
		String name = label.replaceAll("[^A-Za-z0-9_.-]+", "_");
		try {
			java.nio.file.Path base = java.nio.file.Path.of(dir);
			java.nio.file.Files.createDirectories(base);
			java.nio.file.Files.writeString(base.resolve(name + ".vert.glsl"), vertexGlsl);
			java.nio.file.Files.writeString(base.resolve(name + ".frag.glsl"), fragmentGlsl);
			java.nio.file.Files.writeString(base.resolve(name + ".vert.metal"), v.msl());
			java.nio.file.Files.writeString(base.resolve(name + ".frag.metal"), f.msl());
		} catch (java.io.IOException ignored) {
		}
	}

	private static int flipCompare(final int code) {
		return switch (code) {
			case 1 -> 6;
			case 6 -> 1;
			case 2 -> 5;
			case 5 -> 2;
			default -> code;
		};
	}

	static GbuffersEnvironment.VanillaLayout layoutOf(final BackendRenderPipeline.CreateInfo info, final byte @Nullable [] vertexSpirv) {
		// Never info.shaders()' modules: the game has freed their SPIR-V by now.
		Map<Integer, String> names = Map.of();
		if (vertexSpirv != null) {
			java.nio.ByteBuffer copy = org.lwjgl.system.MemoryUtil.memAlloc(vertexSpirv.length);
			try {
				copy.put(vertexSpirv).flip();
				names = PackCompiler.inputNames(copy);
			} finally {
				org.lwjgl.system.MemoryUtil.memFree(copy);
			}
		}
		Map<String, GbuffersEnvironment.Attribute> attributes = new LinkedHashMap<>();
		for (BackendRenderPipeline.CreateInfo.AttribBinding binding : info.attribBindings()) {
			String name = names.getOrDefault(binding.location(), "Attr" + binding.location());
			attributes.put(name, new GbuffersEnvironment.Attribute(name, binding.location(), glslType(binding.format())));
		}
		Map<String, Integer> uniforms = new HashMap<>();
		List<BindGroupLayout.UniformDescription> list = info.uniforms();
		for (int i = 0; i < list.size(); i++) {
			uniforms.put(list.get(i).name(), i);
		}
		return new GbuffersEnvironment.VanillaLayout(attributes, uniforms);
	}

	static String glslType(final GpuFormat format) {
		String name = format.name();
		int components = name.startsWith("RGBA") ? 4 : name.startsWith("RGB") ? 3 : name.startsWith("RG") ? 2 : 1;
		boolean integer = name.endsWith("_UINT") || name.endsWith("_SINT");
		boolean unsigned = name.endsWith("_UINT");
		String prefix = integer ? (unsigned ? "u" : "i") : "";
		if (components == 1) {
			return integer ? (unsigned ? "uint" : "int") : "float";
		}
		return prefix + "vec" + components;
	}

	/** Our stand-in when a pack has no program for something (textured, lit by the lightmap, written to colortex0). */
	private ProgramSet.@Nullable Program defaultProgram(final String name) {
		String vertex = """
			#version 120
			varying vec2 uv;
			varying vec2 lm;
			varying vec4 color;
			void main() {
				gl_Position = ftransform();
				uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
				lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
				color = gl_Color;
			}
			""";
		String fragment = """
			#version 120
			uniform sampler2D gtexture;
			uniform sampler2D lightmap;
			varying vec2 uv;
			varying vec2 lm;
			varying vec4 color;
			void main() {
				gl_FragData[0] = texture2D(gtexture, uv) * texture2D(lightmap, lm) * color;
			}
			""";
		Preprocessor.Result vs = new Preprocessor(p -> null).processSource("/default.vsh", vertex);
		Preprocessor.Result fs = new Preprocessor(p -> null).processSource("/default.fsh", fragment);
		return new ProgramSet.Program(name, "/default.vsh", "/default.fsh", vs, fs, List.of(0));
	}

	/**
	 * Debugging: a final pass that shows one buffer (depth buffers remapped to be visible), or with "grid" colortex0-7
	 * and depthtex0 in a 3x3 grid (NaN/inf shown magenta).
	 */
	public ProgramSet.Program debugFinal(final String buffer) {
		StringBuilder f = new StringBuilder("#version 120\nvarying vec2 uv;\n");
		List<String> names = buffer.equals("grid")
			? List.of("colortex0", "colortex1", "colortex2", "colortex3", "colortex4", "colortex5", "colortex6", "colortex7", "depthtex0") : List.of(buffer);
		for (String name : names) {
			f.append("uniform sampler2D ").append(name).append(";\n");
		}
		f.append("vec3 show(vec4 c, bool depth) {\n")
			.append("  if (any(isnan(c)) || any(isinf(c))) return vec3(1.0, 0.0, 1.0);\n")
			.append("  return depth ? vec3(pow(c.r, 64.0)) : c.rgb;\n}\n")
			.append("void main() {\n");
		if (names.size() == 1) {
			f.append("  gl_FragColor = vec4(show(texture2D(").append(buffer).append(", uv), ").append(buffer.contains("depth") || buffer.contains("shadowtex"))
				.append("), 1.0);\n");
		} else {
			f.append("  vec2 cell = floor(uv * 3.0); vec2 local = fract(uv * 3.0); int i = int(cell.x + (2.0 - cell.y) * 3.0);\n  vec3 c = vec3(0.0);\n");
			for (int i = 0; i < names.size(); i++) {
				f.append("  if (i == ").append(i).append(") c = show(texture2D(").append(names.get(i)).append(", local), ").append(names.get(i).contains("depth"))
					.append(");\n");
			}
			f.append("  gl_FragColor = vec4(c, 1.0);\n");
		}
		f.append("}\n");
		ProgramSet.Program base = this.defaultFinal();
		Preprocessor.Result fs = new Preprocessor(p -> null).processSource("/final.fsh", f.toString());
		return new ProgramSet.Program("debug", base.vertexPath(), "/final.fsh", base.vertex(), fs, List.of(0));
	}

	/** The default final pass (colortex0 to the screen) for packs without one. */
	public ProgramSet.Program defaultFinal() {
		String vertex = """
			#version 120
			varying vec2 uv;
			void main() {
				gl_Position = ftransform();
				uv = gl_MultiTexCoord0.xy;
			}
			""";
		String fragment = """
			#version 120
			uniform sampler2D colortex0;
			varying vec2 uv;
			void main() {
				gl_FragColor = texture2D(colortex0, uv);
			}
			""";
		Preprocessor.Result vs = new Preprocessor(p -> null).processSource("/final.vsh", vertex);
		Preprocessor.Result fs = new Preprocessor(p -> null).processSource("/final.fsh", fragment);
		return new ProgramSet.Program("final", "/final.vsh", "/final.fsh", vs, fs, List.of(0));
	}

	private void error(final String what, final RuntimeException e) {
		String message = what + ": " + e.getMessage();
		synchronized (this.errors) {
			this.errors.add(message);
		}
		MCMetal.LOGGER.error("Shaderpack program {} failed: {}", what, e.getMessage());
	}
}
