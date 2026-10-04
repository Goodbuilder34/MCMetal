package com.mcmetal.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The files of an OptiFine/Iris-format shaderpack (a zip or a folder containing {@code shaders/}), addressed by
 * absolute paths below {@code shaders/}: {@code /world0/composite.fsh}, {@code /lib/settings.glsl}.
 * Text files are read eagerly (packs are small); binary files (textures) on demand.
 */
public final class ShaderPack {
	private final String name;
	private final Path location;
	private final Map<String, String> text = new TreeMap<>();
	private final Map<String, byte[]> binary = new HashMap<>();
	private final PackOptions options;

	private ShaderPack(final String name, final Path location) {
		this.name = name;
		this.location = location;
		this.options = new PackOptions();
	}

	public static ShaderPack load(final Path location) throws IOException {
		String fileName = location.getFileName().toString();
		ShaderPack pack = new ShaderPack(fileName, location);
		if (Files.isDirectory(location)) {
			Path root = location.resolve("shaders");
			if (!Files.isDirectory(root)) {
				throw new PackException(fileName + " has no shaders/ folder");
			}
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
					String path = "/" + root.relativize(file).toString().replace('\\', '/');
					pack.add(path, Files.readAllBytes(file));
				}
			}
		} else {
			try (ZipFile zip = new ZipFile(location.toFile())) {
				// The shaders/ folder may be nested one level down (e.g. "MyPack/shaders/...").
				String prefix = null;
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					String entry = entries.nextElement().getName();
					int index = entry.indexOf("shaders/");
					if (index >= 0 && (index == 0 || entry.charAt(index - 1) == '/') && (prefix == null || index < prefix.length())) {
						prefix = entry.substring(0, index + "shaders/".length());
					}
				}
				if (prefix == null) {
					throw new PackException(fileName + " has no shaders/ folder");
				}
				entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
						continue;
					}
					try (InputStream in = zip.getInputStream(entry)) {
						pack.add("/" + entry.getName().substring(prefix.length()), in.readAllBytes());
					}
				}
			}
		}
		pack.options.discover(pack.text);
		return pack;
	}

	private void add(final String path, final byte[] bytes) {
		String lower = path.toLowerCase();
		if (lower.endsWith(".glsl") || lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".gsh") || lower.endsWith(".csh")
			|| lower.endsWith(".properties") || lower.endsWith(".lang") || lower.endsWith(".json") || lower.endsWith(".txt") || lower.endsWith(".inc")) {
			this.text.put(path, new String(bytes, StandardCharsets.UTF_8));
		} else {
			this.binary.put(path, bytes);
		}
	}

	public String name() {
		return this.name;
	}

	public Path location() {
		return this.location;
	}

	public PackOptions options() {
		return this.options;
	}

	public boolean exists(final String path) {
		return this.text.containsKey(path) || this.binary.containsKey(path);
	}

	/** Raw text of a file, without option edits. */
	public @Nullable String raw(final String path) {
		return this.text.get(path);
	}

	/** Text of a file with the current option values applied to its {@code #define}/{@code const} lines. */
	public @Nullable String source(final String path) {
		String source = this.text.get(path);
		return source != null ? this.options.apply(source) : null;
	}

	public byte @Nullable [] bytes(final String path) {
		byte[] bytes = this.binary.get(path);
		if (bytes == null && this.text.containsKey(path)) {
			return this.text.get(path).getBytes(StandardCharsets.UTF_8);
		}
		return bytes;
	}

	public Iterable<String> textFiles() {
		return this.text.keySet();
	}
}
