package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Shaderpack options, discovered OptiFine-style from the sources:
 * <ul>
 *   <li>boolean: {@code #define NAME} (on) or {@code //#define NAME} (off), when some {@code #ifdef}/{@code defined} tests it</li>
 *   <li>value: {@code #define NAME 2 // [1 2 3]}</li>
 *   <li>const: {@code const int shadowMapResolution = 2048; // [1024 2048 4096]}</li>
 * </ul>
 * Changed values are applied by rewriting those lines in every file that contains them, as OptiFine and Iris do.
 */
public final class PackOptions {
	public enum Kind { BOOLEAN, VALUE, CONST }

	public record Option(String name, Kind kind, String defaultValue, List<String> allowed, String file) {
		public boolean isBoolean() {
			return this.kind == Kind.BOOLEAN || (this.kind == Kind.CONST && (this.defaultValue.equals("true") || this.defaultValue.equals("false")));
		}
	}

	private static final Pattern BOOL_DEFINE = Pattern.compile("^(\\s*)(//+)?\\s*#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(//.*)?$");
	private static final Pattern VALUE_DEFINE = Pattern.compile("^(\\s*)#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\s/]+)(\\s*//.*)$");
	private static final Pattern CONST = Pattern.compile("^(\\s*)const\\s+(int|float|bool)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+?)\\s*;(\\s*//.*)?$");
	private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
	private static final Pattern IFDEF = Pattern.compile("#\\s*(?:ifdef|ifndef|elif|if)\\b(.*)");
	private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private final Map<String, Option> options = new LinkedHashMap<>();
	private final Map<String, String> values = new HashMap<>();

	void discover(final Map<String, String> files) {
		Set<String> tested = new HashSet<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			if (!isShaderFile(file.getKey())) {
				continue;
			}
			for (String line : file.getValue().split("\n")) {
				Matcher ifdef = IFDEF.matcher(line.trim());
				if (ifdef.matches()) {
					Matcher word = WORD.matcher(ifdef.group(1));
					while (word.find()) {
						tested.add(word.group());
					}
				}
			}
		}
		Map<String, String> conflicting = new HashMap<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			if (!isShaderFile(file.getKey())) {
				continue;
			}
			for (String raw : file.getValue().split("\n")) {
				String line = raw.stripTrailing();
				Option option = parse(line, file.getKey());
				if (option == null || (option.kind() == Kind.BOOLEAN && !tested.contains(option.name()))) {
					continue;
				}
				Option existing = this.options.get(option.name());
				if (existing == null) {
					this.options.put(option.name(), option);
				} else if (!existing.defaultValue().equals(option.defaultValue()) || existing.kind() != option.kind()) {
					conflicting.put(option.name(), file.getKey());
				}
			}
		}
		// An option whose default differs between files is ambiguous; leave those lines alone.
		conflicting.keySet().forEach(this.options::remove);
	}

	private static boolean isShaderFile(final String path) {
		return path.endsWith(".glsl") || path.endsWith(".vsh") || path.endsWith(".fsh") || path.endsWith(".csh") || path.endsWith(".gsh");
	}

	private static @Nullable Option parse(final String line, final String file) {
		if (line.indexOf('#') >= 0) {
			Matcher bool = BOOL_DEFINE.matcher(line);
			if (bool.matches()) {
				return new Option(bool.group(3), Kind.BOOLEAN, bool.group(2) == null ? "true" : "false", List.of("true", "false"), file);
			}
			Matcher value = VALUE_DEFINE.matcher(line);
			if (value.matches()) {
				Matcher list = LIST.matcher(value.group(4));
				if (list.find()) {
					return new Option(value.group(2), Kind.VALUE, value.group(3), split(list.group(1)), file);
				}
			}
			return null;
		}
		if (line.contains("const")) {
			Matcher constant = CONST.matcher(line);
			if (constant.matches() && constant.group(5) != null) {
				String type = constant.group(2);
				Matcher list = LIST.matcher(constant.group(5));
				if (list.find()) {
					return new Option(constant.group(3), Kind.CONST, constant.group(4), split(list.group(1)), file);
				}
				if (type.equals("bool")) {
					return new Option(constant.group(3), Kind.CONST, constant.group(4), List.of("true", "false"), file);
				}
			}
		}
		return null;
	}

	private static List<String> split(final String list) {
		return new ArrayList<>(Arrays.asList(list.trim().split("\\s+")));
	}

	public Map<String, Option> all() {
		return this.options;
	}

	public @Nullable Option get(final String name) {
		return this.options.get(name);
	}

	public String value(final String name) {
		String value = this.values.get(name);
		if (value != null) {
			return value;
		}
		Option option = this.options.get(name);
		return option != null ? option.defaultValue() : "";
	}

	public boolean isChanged(final String name) {
		return this.values.containsKey(name);
	}

	/** Sets a value; null or the default resets it. */
	public void set(final String name, final @Nullable String value) {
		Option option = this.options.get(name);
		if (option == null) {
			return;
		}
		if (value == null || value.equals(option.defaultValue())) {
			this.values.remove(name);
		} else {
			this.values.put(name, value);
		}
	}

	public Map<String, String> changed() {
		return this.values;
	}

	String apply(final String source) {
		if (this.values.isEmpty()) {
			return source;
		}
		StringBuilder sb = new StringBuilder(source.length() + 64);
		int start = 0;
		while (start <= source.length()) {
			int end = source.indexOf('\n', start);
			if (end < 0) {
				end = source.length();
			}
			String line = source.substring(start, end);
			sb.append(this.applyLine(line));
			if (end < source.length()) {
				sb.append('\n');
			}
			start = end + 1;
		}
		return sb.toString();
	}

	private String applyLine(final String line) {
		if (line.indexOf('#') < 0 && !line.contains("const")) {
			return line;
		}
		String trimmed = line.stripTrailing();
		Matcher bool = BOOL_DEFINE.matcher(trimmed);
		if (bool.matches() && this.values.containsKey(bool.group(3)) && this.isKind(bool.group(3), Kind.BOOLEAN)) {
			boolean on = Boolean.parseBoolean(this.values.get(bool.group(3)));
			String comment = bool.group(4) != null ? " " + bool.group(4) : "";
			return bool.group(1) + (on ? "" : "//") + "#define " + bool.group(3) + comment;
		}
		Matcher value = VALUE_DEFINE.matcher(trimmed);
		if (value.matches() && this.values.containsKey(value.group(2)) && this.isKind(value.group(2), Kind.VALUE)) {
			return value.group(1) + "#define " + value.group(2) + " " + this.values.get(value.group(2)) + value.group(4);
		}
		Matcher constant = CONST.matcher(trimmed);
		if (constant.matches() && this.values.containsKey(constant.group(3)) && this.isKind(constant.group(3), Kind.CONST)) {
			String comment = constant.group(5) != null ? constant.group(5) : "";
			return constant.group(1) + "const " + constant.group(2) + " " + constant.group(3) + " = " + this.values.get(constant.group(3)) + ";" + comment;
		}
		return line;
	}

	private boolean isKind(final String name, final Kind kind) {
		Option option = this.options.get(name);
		return option != null && option.kind() == kind;
	}
}
