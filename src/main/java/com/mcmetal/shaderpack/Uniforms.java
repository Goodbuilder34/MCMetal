package com.mcmetal.shaderpack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.util.spvc.Spvc;

/**
 * Uniform values for pack programs, by name: the OptiFine/Iris built-ins (filled by {@link BuiltinUniforms} each frame),
 * the pack's custom uniforms from shaders.properties, and per-draw overrides (entityId, renderStage...).
 * {@link #write} lays them out into a program's reflected default uniform block.
 */
public final class Uniforms {
	private final Map<String, double[]> values = new HashMap<>();
	private final Map<String, double[]> perDraw = new HashMap<>();
	private final List<Custom> custom = new ArrayList<>();
	private final Map<String, Integer> biomeIds = new HashMap<>();

	private record Custom(PackProperties.CustomUniform definition, Expression expression) {
	}

	public void setCustom(final List<PackProperties.CustomUniform> definitions, final List<String> errors) {
		this.custom.clear();
		for (PackProperties.CustomUniform definition : definitions) {
			try {
				this.custom.add(new Custom(definition, Expression.parse(definition.expression())));
			} catch (PackException e) {
				errors.add("custom uniform " + definition.name() + ": " + e.getMessage());
			}
		}
	}

	public void setBiomeIds(final Map<String, Integer> ids) {
		this.biomeIds.clear();
		this.biomeIds.putAll(ids);
	}

	public void set(final String name, final double... value) {
		this.values.put(name, value);
	}

	public void set(final String name, final Matrix4fc matrix) {
		double[] m = new double[16];
		for (int c = 0; c < 4; c++) {
			for (int r = 0; r < 4; r++) {
				m[c * 4 + r] = matrix.get(c, r);
			}
		}
		this.values.put(name, m);
	}

	public boolean has(final String name) {
		return this.values.containsKey(name);
	}

	public void setPerDraw(final String name, final double... value) {
		this.perDraw.put(name, value);
	}

	public void clearPerDraw() {
		this.perDraw.clear();
	}

	public double @Nullable [] get(final String name) {
		double[] value = this.perDraw.get(name);
		if (value != null) {
			return value;
		}
		value = this.values.get(name);
		if (value != null) {
			return value;
		}
		if (name.startsWith("BIOME_")) {
			Integer id = this.biomeIds.get(name.substring(6).toLowerCase());
			return new double[]{id != null ? id : -1};
		}
		return switch (name) {
			case "PPT_NONE" -> new double[]{0};
			case "PPT_RAIN" -> new double[]{1};
			case "PPT_SNOW" -> new double[]{2};
			default -> name.startsWith("CAT_") ? new double[]{categoryId(name.substring(4))} : null;
		};
	}

	private static int categoryId(final String category) {
		String[] categories = {"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY", "THE_END", "BEACH", "FOREST", "OCEAN",
			"DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER", "MOUNTAIN", "UNDERGROUND"};
		for (int i = 0; i < categories.length; i++) {
			if (categories[i].equals(category)) {
				return i;
			}
		}
		return -1;
	}

	/** Evaluates custom uniforms and variables in declaration order (later ones may use earlier ones). */
	public void updateCustom() {
		for (Custom c : this.custom) {
			try {
				double[] value = c.expression().eval(this::get);
				String type = c.definition().type();
				if (type.equals("int") || type.equals("bool")) {
					value = value.clone();
					for (int i = 0; i < value.length; i++) {
						value[i] = type.equals("bool") ? (value[i] != 0.0 ? 1 : 0) : (double) (long) value[i];
					}
				}
				this.values.put(c.definition().name(), value);
			} catch (RuntimeException e) {
				this.values.put(c.definition().name(), new double[]{0.0});
			}
		}
	}

	/** Writes the named values into a block laid out as reflected (std140); unknown names stay zero. */
	public void write(final PackCompiler.UniformBlock block, final ByteBuffer out) {
		for (PackCompiler.UniformMember member : block.members()) {
			double[] value = this.get(member.name());
			if (value == null) {
				continue;
			}
			writeMember(member, value, out);
		}
	}

	static void writeMember(final PackCompiler.UniformMember member, final double[] value, final ByteBuffer out) {
		int base = member.baseType();
		boolean isInt = base == Spvc.SPVC_BASETYPE_INT32 || base == Spvc.SPVC_BASETYPE_UINT32 || base == Spvc.SPVC_BASETYPE_BOOLEAN;
		int rows = Math.max(1, member.vecSize());
		int columns = Math.max(1, member.columns());
		int elements = Math.max(1, member.arraySize());
		int index = 0;
		for (int e = 0; e < elements; e++) {
			int elementOffset = member.offset() + e * member.arrayStride();
			for (int c = 0; c < columns; c++) {
				int columnOffset = elementOffset + c * (columns > 1 ? member.matrixStride() : 0);
				for (int r = 0; r < rows; r++) {
					double v = index < value.length ? value[index] : 0.0;
					// A matrix value for a smaller matrix (mat3 from a mat4) keeps its columns aligned.
					if (columns > 1 && value.length == 16 && rows < 4) {
						v = value[c * 4 + r];
					}
					index++;
					int at = columnOffset + r * 4;
					if (at + 4 > out.capacity()) {
						continue;
					}
					if (isInt) {
						out.putInt(at, (int) Math.round(v));
					} else {
						out.putFloat(at, (float) v);
					}
				}
			}
		}
	}

	public static Matrix4f copy(final Matrix4fc matrix) {
		return new Matrix4f(matrix);
	}
}
