package com.mcmetal.shaderpack;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The pack's item.properties ({@code item.<id>=minecraft:torch ...}), for heldItemId / heldItemId2. */
public final class ItemIds {
	private final Map<Item, Integer> ids = new HashMap<>();

	public static ItemIds parse(final @Nullable PackProperties properties) {
		ItemIds result = new ItemIds();
		if (properties == null) {
			return result;
		}
		for (Map.Entry<String, String> entry : properties.all().entrySet()) {
			if (!entry.getKey().startsWith("item.")) {
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(entry.getKey().substring("item.".length()).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			for (String token : entry.getValue().trim().split("\\s+")) {
				Identifier identifier = Identifier.tryParse(token.contains(":") ? token : "minecraft:" + token);
				if (identifier != null) {
					BuiltInRegistries.ITEM.getOptional(identifier).ifPresent(item -> result.ids.putIfAbsent(item, id));
				}
			}
		}
		return result;
	}

	public int idOf(final ItemStack stack) {
		if (stack.isEmpty()) {
			return -1;
		}
		Integer id = this.ids.get(stack.getItem());
		return id != null ? id : -1;
	}
}
