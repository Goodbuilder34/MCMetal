package com.mcmetal;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings from {@code config/mcmetal.properties}; each key can be overridden with {@code -Dmcmetal.<key>=...}.
 * <ul>
 *   <li>{@code renderScale} (0.25-1.0, default 1.0): render resolution relative to the window, see {@link RenderScale}.</li>
 *   <li>{@code frameLimiter} (true/false, default false): low-latency limiter, one frame per display refresh started
 *   as late as possible, for much lower power use with nearly uncapped responsiveness.</li>
 * </ul>
 */
public final class Config {
	private static final Properties FILE = load();

	private Config() {
	}

	public static String get(final String key) {
		String value = System.getProperty("mcmetal." + key);
		return value != null ? value.trim() : (FILE.getProperty(key) != null ? FILE.getProperty(key).trim() : null);
	}

	public static boolean frameLimiter() {
		return Boolean.parseBoolean(get("frameLimiter"));
	}

	private static Properties load() {
		Properties properties = new Properties();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mcmetal.properties");
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			} catch (IOException e) {
				MCMetal.LOGGER.warn("Couldn't read {}", file, e);
			}
		}
		return properties;
	}
}
