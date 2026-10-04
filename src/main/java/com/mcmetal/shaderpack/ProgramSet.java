package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The programs of a pack for one dimension, preprocessed with the current options, plus the pack-wide directives
 * found in them ({@code const} settings like {@code shadowMapResolution} or {@code colortex0Format}).
 */
public final class ProgramSet {
	/** A program's two stages after preprocessing (comments kept), and its render target directive. */
	public record Program(String name, String vertexPath, String fragmentPath, Preprocessor.Result vertex, Preprocessor.Result fragment,
		@Nullable List<Integer> drawBuffers) {
	}

	public static final List<String> GBUFFERS = List.of(
		"gbuffers_basic", "gbuffers_line", "gbuffers_textured", "gbuffers_textured_lit", "gbuffers_skybasic", "gbuffers_skytextured",
		"gbuffers_clouds", "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_terrain_cutout_mip",
		"gbuffers_damagedblock", "gbuffers_block", "gbuffers_block_translucent", "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities",
		"gbuffers_entities_translucent", "gbuffers_entities_glowing", "gbuffers_lightning", "gbuffers_armor_glint", "gbuffers_spidereyes",
		"gbuffers_hand", "gbuffers_hand_water", "gbuffers_weather", "gbuffers_water", "gbuffers_particles", "gbuffers_particles_translucent"
	);

	/** Iris's fallback chain: a missing gbuffers program uses the next one that exists. */
	public static final Map<String, String> FALLBACK = Map.ofEntries(
		Map.entry("gbuffers_line", "gbuffers_basic"), Map.entry("gbuffers_textured", "gbuffers_basic"),
		Map.entry("gbuffers_textured_lit", "gbuffers_textured"), Map.entry("gbuffers_skybasic", "gbuffers_basic"),
		Map.entry("gbuffers_skytextured", "gbuffers_textured"), Map.entry("gbuffers_clouds", "gbuffers_textured"),
		Map.entry("gbuffers_terrain", "gbuffers_textured_lit"), Map.entry("gbuffers_terrain_solid", "gbuffers_terrain"),
		Map.entry("gbuffers_terrain_cutout", "gbuffers_terrain"), Map.entry("gbuffers_terrain_cutout_mip", "gbuffers_terrain"),
		Map.entry("gbuffers_damagedblock", "gbuffers_terrain"), Map.entry("gbuffers_block", "gbuffers_terrain"),
		Map.entry("gbuffers_block_translucent", "gbuffers_block"), Map.entry("gbuffers_beaconbeam", "gbuffers_textured"),
		Map.entry("gbuffers_item", "gbuffers_entities"), Map.entry("gbuffers_entities", "gbuffers_textured_lit"),
		Map.entry("gbuffers_entities_translucent", "gbuffers_entities"), Map.entry("gbuffers_entities_glowing", "gbuffers_entities"),
		Map.entry("gbuffers_lightning", "gbuffers_entities"), Map.entry("gbuffers_armor_glint", "gbuffers_textured"),
		Map.entry("gbuffers_spidereyes", "gbuffers_textured"), Map.entry("gbuffers_hand", "gbuffers_textured_lit"),
		Map.entry("gbuffers_hand_water", "gbuffers_hand"), Map.entry("gbuffers_weather", "gbuffers_textured_lit"),
		Map.entry("gbuffers_water", "gbuffers_terrain"), Map.entry("gbuffers_particles", "gbuffers_textured_lit"),
		Map.entry("gbuffers_particles_translucent", "gbuffers_particles"),
		Map.entry("shadow_solid", "shadow"), Map.entry("shadow_cutout", "shadow")
	);

	private static final Pattern DRAWBUFFERS = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9A-Fa-f]*)\\s*\\*/");
	private static final Pattern RENDERTARGETS = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]*)\\*/");
	private static final Pattern CONST = Pattern.compile("const\\s+(int|float|bool|vec4|ivec4|vec3)\\s+(\\w+)\\s*=\\s*([^;]+);");

	private final ShaderPack pack;
	private final String folder;
	private final PackProperties properties;
	private final Map<String, Program> programs = new LinkedHashMap<>();
	private final Map<String, String> constants = new HashMap<>();
	private final List<String> errors = new ArrayList<>();

	public ProgramSet(final ShaderPack pack, final String folder, final PackProperties properties) {
		this.pack = pack;
		this.folder = folder;
		this.properties = properties;
	}

	/** Picks the folder for a dimension (e.g. "minecraft:overworld") from dimension.properties, or the defaults. */
	public static String folderFor(final ShaderPack pack, final String dimension) {
		String dims = pack.raw("/dimension.properties");
		if (dims != null) {
			String wildcard = null;
			for (String line : dims.split("\n")) {
				line = line.strip();
				if (!line.startsWith("dimension.") || !line.contains("=")) {
					continue;
				}
				String folder = line.substring("dimension.".length(), line.indexOf('=')).strip();
				for (String id : line.substring(line.indexOf('=') + 1).strip().split("\\s+")) {
					if (id.equals("*")) {
						wildcard = folder;
					} else if (id.equals(dimension)) {
						return folder;
					}
				}
			}
			if (wildcard != null) {
				return wildcard;
			}
		}
		return switch (dimension) {
			case "minecraft:the_nether" -> "world-1";
			case "minecraft:the_end" -> "world1";
			default -> "world0";
		};
	}

	public ShaderPack pack() {
		return this.pack;
	}

	public String folder() {
		return this.folder;
	}

	public PackProperties properties() {
		return this.properties;
	}

	public Map<String, Program> programs() {
		return this.programs;
	}

	public @Nullable Program get(final String name) {
		return this.programs.get(name);
	}

	/** {@code const} settings from all programs (last one wins), e.g. {@code shadowMapResolution} → "2048". */
	public Map<String, String> constants() {
		return this.constants;
	}

	public List<String> errors() {
		return this.errors;
	}

	public static List<String> passNames(final String prefix) {
		List<String> names = new ArrayList<>();
		names.add(prefix);
		for (int i = 1; i < 100; i++) {
			names.add(prefix + i);
		}
		return names;
	}

	/** Loads every program the pack has for this dimension (gbuffers, shadow, deferred, composite, final...). */
	public void load() {
		List<String> names = new ArrayList<>(GBUFFERS);
		names.add("shadow");
		names.add("shadow_solid");
		names.add("shadow_cutout");
		names.addAll(passNames("prepare"));
		names.addAll(passNames("deferred"));
		names.addAll(passNames("composite"));
		names.add("final");
		for (String name : names) {
			String vertex = this.find(name + ".vsh");
			String fragment = this.find(name + ".fsh");
			if (vertex == null || fragment == null) {
				continue;
			}
			if (!this.enabled(name)) {
				continue;
			}
			try {
				Preprocessor.Result vs = this.preprocess(vertex);
				Preprocessor.Result fs = this.preprocess(fragment);
				Program program = new Program(name, vertex, fragment, vs, fs, drawBuffers(fs.source()));
				this.programs.put(name, program);
				this.collectConstants(vs.source());
				this.collectConstants(fs.source());
			} catch (PackException e) {
				this.errors.add(name + ": " + e.getMessage());
			}
		}
	}

	private boolean enabled(final String name) {
		String condition = this.properties.get("program." + this.folder + "/" + name + ".enabled");
		if (condition == null) {
			condition = this.properties.get("program." + name + ".enabled");
		}
		if (condition == null) {
			return true;
		}
		PackOptions options = this.pack.options();
		return PackProperties.condition(condition, option -> {
			PackOptions.Option o = options.get(option);
			if (o != null && o.kind() == PackOptions.Kind.BOOLEAN) {
				return Boolean.parseBoolean(options.value(option));
			}
			return StandardMacros.get().containsKey(option);
		});
	}

	private @Nullable String find(final String file) {
		String inFolder = "/" + this.folder + "/" + file;
		if (this.pack.exists(inFolder)) {
			return inFolder;
		}
		String root = "/" + file;
		return this.pack.exists(root) ? root : null;
	}

	public Preprocessor.Result preprocess(final String path) {
		Preprocessor preprocessor = new Preprocessor(this.pack::source);
		StandardMacros.defineStandard(preprocessor);
		return preprocessor.process(path);
	}

	static @Nullable List<Integer> drawBuffers(final String source) {
		List<Integer> result = null;
		Matcher m = DRAWBUFFERS.matcher(source);
		int last = -1;
		while (m.find()) {
			last = m.start();
			result = new ArrayList<>();
			for (char c : m.group(1).toCharArray()) {
				result.add(Character.digit(c, 16));
			}
		}
		Matcher r = RENDERTARGETS.matcher(source);
		while (r.find()) {
			if (r.start() < last) {
				continue;
			}
			last = r.start();
			result = new ArrayList<>();
			for (String part : r.group(1).split(",")) {
				if (!part.isBlank()) {
					result.add(Integer.parseInt(part.trim()));
				}
			}
		}
		return result;
	}

	private void collectConstants(final String source) {
		Matcher m = CONST.matcher(source);
		while (m.find()) {
			this.constants.put(m.group(2), m.group(3).trim());
		}
	}
}
