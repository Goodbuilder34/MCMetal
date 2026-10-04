package com.mcmetal.shaderpack.ui;

import com.mcmetal.shaderpack.ShaderPack;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** A pack's lang/xx_XX.lang labels (option.NAME, value.NAME.VALUE, screen.NAME, profile.NAME...). */
final class PackLang {
	private final Map<String, String> entries = new HashMap<>();

	PackLang(final ShaderPack pack) {
		this.load(pack, "en_us");
		String language = Minecraft.getInstance().options.languageCode;
		if (language != null && !language.equalsIgnoreCase("en_us")) {
			this.load(pack, language);
		}
	}

	private void load(final ShaderPack pack, final String code) {
		String[] parts = code.split("_");
		String region = parts.length > 1 ? parts[0] + "_" + parts[1].toUpperCase() : code;
		for (String name : new String[]{region, code, code.toLowerCase(), code.toUpperCase()}) {
			String text = pack.raw("/lang/" + name + ".lang");
			if (text != null) {
				for (String line : text.split("\n")) {
					line = line.strip();
					int eq = line.indexOf('=');
					if (line.isEmpty() || line.startsWith("#") || eq <= 0) {
						continue;
					}
					this.entries.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
				}
				return;
			}
		}
	}

	@Nullable String get(final String key) {
		return this.entries.get(key);
	}

	String get(final String key, final String fallback) {
		String value = this.entries.get(key);
		return value != null ? value : fallback;
	}
}
