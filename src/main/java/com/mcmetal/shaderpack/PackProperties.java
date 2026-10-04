package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * {@code shaders.properties} (and friends like {@code block.properties}): run through the preprocessor in
 * properties mode, then parsed into ordered key/value pairs. Later keys override earlier ones, except custom
 * uniforms/variables, whose order matters and is kept separately.
 */
public final class PackProperties {
	public record CustomUniform(boolean exported, String type, String name, String expression) {
	}

	private final Map<String, String> values = new LinkedHashMap<>();
	private final List<CustomUniform> customUniforms = new ArrayList<>();

	public static PackProperties parse(final @Nullable String source, final Preprocessor preprocessor, final String path) {
		PackProperties properties = new PackProperties();
		if (source == null) {
			return properties;
		}
		// Continuation lines ("key = a b \" + newline + "c d") are joined first.
		String processed = preprocessor.propertiesMode().processSource(path, source.replace("\r\n", "\n").replaceAll("\\\\[ \t]*\n", " ")).source();
		for (String raw : processed.split("\n")) {
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
				continue;
			}
			int eq = line.indexOf('=');
			int colon = line.indexOf(':');
			// Properties also allow "key: value"; only use ':' when no '=' comes first.
			int split = eq >= 0 ? eq : colon;
			if (split <= 0) {
				continue;
			}
			String key = line.substring(0, split).strip();
			String value = line.substring(split + 1).strip();
			if (key.startsWith("uniform.") || key.startsWith("variable.")) {
				String[] parts = key.split("\\.", 3);
				if (parts.length == 3) {
					properties.customUniforms.add(new CustomUniform(parts[0].equals("uniform"), parts[1], parts[2], value));
					continue;
				}
			}
			properties.values.put(key, value);
		}
		return properties;
	}

	public @Nullable String get(final String key) {
		return this.values.get(key);
	}

	public String get(final String key, final String fallback) {
		String value = this.values.get(key);
		return value != null ? value : fallback;
	}

	public boolean getBoolean(final String key, final boolean fallback) {
		String value = this.values.get(key);
		return value != null ? Boolean.parseBoolean(value.trim()) : fallback;
	}

	public Map<String, String> all() {
		return this.values;
	}

	public List<CustomUniform> customUniforms() {
		return this.customUniforms;
	}

	/**
	 * Evaluates a boolean condition over option names, as used by {@code program.X.enabled}:
	 * names are true when defined, with {@code ! && || ( )}. A plain {@code true}/{@code false} also works.
	 */
	public static boolean condition(final String expression, final Predicate<String> defined) {
		StringBuilder sb = new StringBuilder();
		for (Preprocessor.Token token : Preprocessor.tokenize(expression)) {
			if (token.ident()) {
				String name = token.text();
				if (name.equals("true")) {
					sb.append(" 1 ");
				} else if (name.equals("false")) {
					sb.append(" 0 ");
				} else {
					sb.append(defined.test(name) ? " 1 " : " 0 ");
				}
			} else {
				sb.append(token.text());
			}
		}
		try {
			return new Preprocessor.ExpressionParser(sb.toString()).parseAll() != 0.0;
		} catch (RuntimeException e) {
			throw new PackException("Bad condition '" + expression + "': " + e.getMessage());
		}
	}
}
