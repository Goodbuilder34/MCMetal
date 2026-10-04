package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The pack's block.properties ({@code block.<id>=minecraft:stone minecraft:wheat:age=7 ...}): the ID each block state
 * gets as {@code mc_Entity.x}. While chunk meshes are built, the ID of the block being tesselated is stored in the
 * unused high bytes of its vertices' UV2 (light) coordinates, which the pack's terrain programs decode.
 */
public final class BlockIds {
	private record Rule(Map<String, List<String>> properties, int id) {
	}

	private static volatile @Nullable BlockIds active;
	private static final ThreadLocal<int[]> CURRENT = ThreadLocal.withInitial(() -> new int[1]);

	private final Map<Block, List<Rule>> rules = new HashMap<>();
	private final Map<String, Integer> tagRules = new HashMap<>();
	private final Map<BlockState, Integer> cache = new ConcurrentHashMap<>();

	private BlockIds() {
	}

	/** Parses block.properties (already preprocessed). */
	public static BlockIds parse(final @Nullable PackProperties properties) {
		BlockIds ids = new BlockIds();
		if (properties == null) {
			return ids;
		}
		for (Map.Entry<String, String> entry : properties.all().entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith("block.")) {
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(key.substring("block.".length()).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			for (String token : entry.getValue().trim().split("\\s+")) {
				if (!token.isEmpty()) {
					ids.add(token, id);
				}
			}
		}
		return ids;
	}

	private void add(final String token, final int id) {
		if (token.startsWith("#")) {
			this.tagRules.putIfAbsent(token.substring(1), id);
			return;
		}
		// [namespace:]name[:prop=value[,value...]]...
		String[] parts = token.split(":");
		String namespace = "minecraft";
		String name;
		int next;
		if (parts.length >= 2 && !parts[1].contains("=")) {
			namespace = parts[0];
			name = parts[1];
			next = 2;
		} else {
			name = parts[0];
			next = 1;
		}
		Identifier identifier = Identifier.tryBuild(namespace, name);
		if (identifier == null) {
			return;
		}
		Block block = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		if (block == null) {
			return;
		}
		Map<String, List<String>> properties = new HashMap<>();
		for (int i = next; i < parts.length; i++) {
			int eq = parts[i].indexOf('=');
			if (eq > 0) {
				properties.put(parts[i].substring(0, eq), List.of(parts[i].substring(eq + 1).split(",")));
			}
		}
		this.rules.computeIfAbsent(block, b -> new ArrayList<>()).add(new Rule(properties, id));
	}

	/** The ID of a block state, or -1. */
	public int idOf(final BlockState state) {
		Integer cached = this.cache.get(state);
		if (cached != null) {
			return cached;
		}
		int id = this.lookup(state);
		this.cache.put(state, id);
		return id;
	}

	private int lookup(final BlockState state) {
		List<Rule> list = this.rules.get(state.getBlock());
		if (list != null) {
			for (Rule rule : list) {
				if (matches(state, rule.properties())) {
					return rule.id();
				}
			}
		}
		if (!this.tagRules.isEmpty()) {
			for (var tag : state.typeHolder().tags().toList()) {
				Integer id = this.tagRules.get(tag.location().toString());
				if (id != null) {
					return id;
				}
			}
		}
		return -1;
	}

	private static boolean matches(final BlockState state, final Map<String, List<String>> properties) {
		for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
			Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
			if (property == null) {
				return false;
			}
			String value = valueName(state, property);
			if (!entry.getValue().contains(value)) {
				return false;
			}
		}
		return true;
	}

	private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> property) {
		return property.getName(state.getValue(property));
	}

	// ---------------------------------------------------------------------------------------------
	// Mesh building
	// ---------------------------------------------------------------------------------------------

	public static void setActive(final @Nullable BlockIds ids) {
		active = ids;
	}

	public static boolean enabled() {
		return active != null;
	}

	/** Marks the block being tesselated on this thread (0 = none). */
	public static void begin(final BlockState state) {
		BlockIds ids = active;
		CURRENT.get()[0] = ids != null ? ids.idOf(state) + 1 : 0;
	}

	public static void end() {
		CURRENT.get()[0] = 0;
	}

	/** Light coordinates with the current block's ID in the high bytes of both 16-bit halves. */
	public static int encodeLight(final int light) {
		int id = CURRENT.get()[0];
		if (id <= 0) {
			return light;
		}
		return (light & 0x00FF00FF) | ((id & 0xFF) << 8) | ((id >> 8 & 0x7F) << 24);
	}
}
