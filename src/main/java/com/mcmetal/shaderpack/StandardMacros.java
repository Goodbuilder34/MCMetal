package com.mcmetal.shaderpack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The macros every pack program sees, matching what Iris defines so packs take their Iris code paths.
 * We report an OpenGL 4.1 / macOS environment: packs already keep that path free of compute shaders, SSBOs and
 * image load/store (which macOS OpenGL never had), and it is what their Mac users run.
 */
public final class StandardMacros {
	public static final String[] RENDER_STAGES = {
		"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT",
		"ENTITIES", "BLOCK_ENTITIES", "DESTROYED_BLOCKS", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES",
		"CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"
	};

	private StandardMacros() {
	}

	public static Map<String, String> get() {
		Map<String, String> macros = new LinkedHashMap<>();
		macros.put("MC_VERSION", "260300");
		macros.put("MC_GL_VERSION", "410");
		macros.put("MC_GLSL_VERSION", "410");
		macros.put("MC_OS_MAC", "");
		macros.put("MC_GL_VENDOR_OTHER", "");
		macros.put("MC_GL_RENDERER_OTHER", "");
		macros.put("MC_RENDER_QUALITY", "1.0");
		macros.put("MC_SHADOW_QUALITY", "1.0");
		macros.put("MC_HAND_DEPTH", "0.125");
		macros.put("IS_IRIS", "");
		macros.put("IRIS_VERSION", "10800");
		macros.put("IRIS_TAG_SUPPORT", "2");
		for (int i = 0; i < RENDER_STAGES.length; i++) {
			macros.put("MC_RENDER_STAGE_" + RENDER_STAGES[i], String.valueOf(i));
		}
		return macros;
	}

	public static void defineStandard(final Preprocessor preprocessor) {
		get().forEach(preprocessor::define);
	}

	/** Properties files test options as macros; shader files define them themselves (via their settings file). */
	public static void defineOptions(final Preprocessor preprocessor, final PackOptions options) {
		for (PackOptions.Option option : options.all().values()) {
			if (option.kind() == PackOptions.Kind.BOOLEAN) {
				if (Boolean.parseBoolean(options.value(option.name()))) {
					preprocessor.define(option.name(), "");
				}
			} else if (option.kind() == PackOptions.Kind.VALUE) {
				preprocessor.define(option.name(), options.value(option.name()));
			}
		}
	}
}
