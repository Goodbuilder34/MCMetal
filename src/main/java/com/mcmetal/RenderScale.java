package com.mcmetal;

/**
 * Optional render resolution scale. The game renders at {@code scale} times the window's pixel size and the
 * CAMetalLayer stretches the image to the window, so 1.0 (the default) changes nothing. Set it in
 * {@code config/mcmetal.properties} as {@code renderScale=0.6667} or with {@code -Dmcmetal.renderScale=0.6667}.
 */
public final class RenderScale {
	private static final double SCALE = load();

	private RenderScale() {
	}

	public static boolean isActive() {
		return SCALE != 1.0 && MCMetal.isEnabled();
	}

	public static int apply(final int pixels) {
		return Math.max(1, (int) Math.round(pixels * SCALE));
	}

	private static double load() {
		String value = Config.get("renderScale");
		if (value == null) {
			return 1.0;
		}
		try {
			double scale = Double.parseDouble(value.trim());
			if (scale >= 0.25 && scale <= 1.0) {
				MCMetal.LOGGER.info("Render scale {}", scale);
				return scale;
			}
		} catch (NumberFormatException ignored) {
		}
		MCMetal.LOGGER.warn("Ignoring render scale '{}' (must be between 0.25 and 1.0)", value);
		return 1.0;
	}
}
