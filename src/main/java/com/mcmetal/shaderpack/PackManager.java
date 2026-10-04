package com.mcmetal.shaderpack;

import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalDevice;
import com.mcmetal.metal.PackBackend;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Which shaderpack is in use: {@code config/mcmetal-shaderpacks.properties} ({@code pack=<file in shaderpacks/>},
 * empty for none) plus the per-pack option values in {@code shaderpacks/<pack>.txt}, as Iris stores them.
 * Packs only run on the Metal backend.
 */
public final class PackManager {
	private static @Nullable PackRenderer renderer;
	private static @Nullable String loadedName;
	private static boolean configRead;
	private static @Nullable String selected;
	private static boolean failed;
	private static float renderScale = 0.75F;

	private PackManager() {
	}

	public static Path packsDirectory() {
		return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
	}

	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcmetal-shaderpacks.properties");
	}

	/** Pack files and folders in shaderpacks/. */
	public static List<String> available() {
		List<String> names = new ArrayList<>();
		Path dir = packsDirectory();
		if (!Files.isDirectory(dir)) {
			return names;
		}
		try (Stream<Path> files = Files.list(dir)) {
			files.forEach(file -> {
				String name = file.getFileName().toString();
				if (name.toLowerCase().endsWith(".zip") || Files.isDirectory(file.resolve("shaders"))) {
					names.add(name);
				}
			});
		} catch (IOException e) {
			MCMetal.LOGGER.warn("Couldn't list {}", dir, e);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	/** A pack's file name without the .zip extension, for display. */
	public static String displayName(final String file) {
		return file.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
	}

	public static @Nullable String selected() {
		readConfig();
		return selected;
	}

	/** Selects a pack (null = none), saves the choice and reloads on the next frame. */
	public static void select(final @Nullable String name) {
		readConfig();
		selected = name;
		failed = false;
		saveConfig();
		unload();
	}

	/**
	 * Resolution the pack renders at, relative to the game's framebuffer (0.5-1.0). Below 1 the result is upscaled with
	 * MetalFX before the GUI is drawn; pack cost scales with the pixel count, so 0.75 renders 56% of the pixels.
	 */
	public static float renderScale() {
		readConfig();
		String override = System.getProperty("mcmetal.shaderRenderScale");
		if (override != null) {
			return parseScale(override);
		}
		return renderScale;
	}

	public static void setRenderScale(final float scale) {
		readConfig();
		renderScale = parseScale(Float.toString(scale));
		saveConfig();
	}

	private static float parseScale(final String value) {
		try {
			float scale = Float.parseFloat(value.trim());
			return Float.isFinite(scale) ? Math.max(0.5F, Math.min(1.0F, scale)) : 1.0F;
		} catch (NumberFormatException e) {
			return 1.0F;
		}
	}

	private static void saveConfig() {
		Properties properties = new Properties();
		properties.setProperty("pack", selected != null ? selected : "");
		properties.setProperty("renderScale", Float.toString(renderScale));
		try (Writer writer = Files.newBufferedWriter(configFile())) {
			properties.store(writer, "MCMetal shaderpack selection");
		} catch (IOException e) {
			MCMetal.LOGGER.warn("Couldn't save {}", configFile(), e);
		}
	}

	/** Reloads the current pack (e.g. after changing its options). */
	public static void reload() {
		failed = false;
		unload();
	}

	private static void readConfig() {
		if (configRead) {
			return;
		}
		configRead = true;
		String override = System.getProperty("mcmetal.shaderpack");
		if (override != null) {
			selected = override.isBlank() ? null : override.trim();
		}
		Path file = configFile();
		if (Files.isRegularFile(file)) {
			Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
				String pack = properties.getProperty("pack", "").trim();
				if (override == null) {
					selected = pack.isEmpty() ? null : pack;
				}
				renderScale = parseScale(properties.getProperty("renderScale", "0.75"));
			} catch (IOException e) {
				MCMetal.LOGGER.warn("Couldn't read {}", file, e);
			}
		}
	}

	private static void unload() {
		if (renderer != null) {
			renderer.close();
			renderer = null;
			loadedName = null;
		}
	}

	public static @Nullable PackRenderer current() {
		return renderer;
	}

	private static @Nullable PackRenderer ensureLoaded() {
		readConfig();
		MetalDevice device = MetalDevice.current();
		if (selected == null || device == null || failed) {
			if (renderer != null) {
				unload();
			}
			return null;
		}
		if (renderer != null && selected.equals(loadedName)) {
			return renderer;
		}
		unload();
		try {
			ShaderPack pack = ShaderPack.load(packsDirectory().resolve(selected));
			loadOptions(pack);
			renderer = new PackRenderer(pack, new PackBackend(device));
			loadedName = selected;
			MCMetal.LOGGER.info("Loaded shaderpack {} ({} options)", selected, pack.options().all().size());
		} catch (IOException | RuntimeException e) {
			failed = true;
			MCMetal.LOGGER.error("Couldn't load shaderpack {}", selected, e);
		}
		return renderer;
	}

	public static Path optionsFile(final ShaderPack pack) {
		return packsDirectory().resolve(pack.name() + ".txt");
	}

	/** Loads a pack with its saved options, for editing them (independent of the running instance). */
	public static ShaderPack loadForEditing(final String name) throws IOException {
		ShaderPack pack = ShaderPack.load(packsDirectory().resolve(name));
		loadOptions(pack);
		return pack;
	}

	/** Problems of the running pack (compile errors etc.), for display. */
	public static List<String> problems() {
		return renderer != null ? List.copyOf(renderer.errors()) : List.of();
	}

	private static void loadOptions(final ShaderPack pack) throws IOException {
		Path file = optionsFile(pack);
		if (!Files.isRegularFile(file)) {
			return;
		}
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file)) {
			properties.load(reader);
		}
		for (String key : properties.stringPropertyNames()) {
			pack.options().set(key, properties.getProperty(key).trim());
		}
	}

	public static void saveOptions(final ShaderPack pack) {
		Properties properties = new Properties();
		for (Map.Entry<String, String> entry : pack.options().changed().entrySet()) {
			properties.setProperty(entry.getKey(), entry.getValue());
		}
		try (Writer writer = Files.newBufferedWriter(optionsFile(pack))) {
			properties.store(writer, "Shaderpack options (changed from the defaults)");
		} catch (IOException e) {
			MCMetal.LOGGER.warn("Couldn't save options of {}", pack.name(), e);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Frame hooks
	// ---------------------------------------------------------------------------------------------

	public static boolean beginLevel(final GameRenderState state, final Matrix4f projection, final RenderTarget main) {
		PackRenderer r = ensureLoaded();
		Minecraft minecraft = Minecraft.getInstance();
		if (r == null || minecraft.level == null) {
			return false;
		}
		String dimension = minecraft.level.dimension().identifier().toString();
		return r.beginLevel(state, projection, main, dimension);
	}

	public static void beforeTranslucent() {
		if (renderer != null) {
			renderer.beforeTranslucent();
		}
	}

	public static void endLevel() {
		if (renderer != null) {
			renderer.endLevel();
		}
	}

	public static void beginHand() {
		if (renderer != null) {
			renderer.beginHand();
		}
	}

	public static void endHand(final GameRenderState state) {
		if (renderer != null) {
			renderer.endHand(state);
		}
	}

	/** A pack is selected and running (the game should use classic transparency, the built-in shader layer stays off). */
	public static boolean active() {
		return renderer != null && MetalDevice.current() != null;
	}
}
