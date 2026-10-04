package com.mcmetal.shaderpack.ui;

import com.mcmetal.MCMetal;
import com.mcmetal.shaderpack.PackManager;
import com.mcmetal.shaderpack.PackOptions;
import com.mcmetal.shaderpack.PackProperties;
import com.mcmetal.shaderpack.Preprocessor;
import com.mcmetal.shaderpack.ShaderPack;
import com.mcmetal.shaderpack.StandardMacros;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * A pack's option menus, laid out like OptiFine/Iris from shaders.properties: {@code screen} (root) and
 * {@code screen.NAME} entries list options, {@code [NAME]} links, {@code <profile>}, {@code <empty>} and {@code *}
 * (every option not placed elsewhere). Options in {@code sliders} are sliders, the rest cycle on click (shift-click
 * goes back). Changes are saved to shaderpacks/&lt;pack&gt;.txt and the pack reloads when the menu is closed.
 */
public final class ShaderOptionsScreen extends OptionsSubScreen {
	/** State shared by a pack's root screen and its sub-screens. */
	private static final class Editor {
		final ShaderPack pack;
		final String packName;
		final PackLang lang;
		PackProperties properties;
		final Map<String, String> initial;
		final Set<String> sliders = new HashSet<>();
		final Map<String, List<String>> profiles = new LinkedHashMap<>();

		Editor(final ShaderPack pack, final String packName) {
			this.pack = pack;
			this.packName = packName;
			this.lang = new PackLang(pack);
			this.initial = new LinkedHashMap<>(pack.options().changed());
			this.properties = this.parseProperties();
			String sliders = this.properties.get("sliders");
			if (sliders != null) {
				this.sliders.addAll(List.of(sliders.trim().split("\\s+")));
			}
			for (Map.Entry<String, String> entry : this.properties.all().entrySet()) {
				if (entry.getKey().startsWith("profile.")) {
					this.profiles.put(entry.getKey().substring("profile.".length()), List.of(entry.getValue().trim().split("\\s+")));
				}
			}
		}

		PackProperties parseProperties() {
			Preprocessor preprocessor = new Preprocessor(this.pack::raw);
			StandardMacros.defineStandard(preprocessor);
			StandardMacros.defineOptions(preprocessor, this.pack.options());
			return PackProperties.parse(this.pack.raw("/shaders.properties"), preprocessor, "/shaders.properties");
		}

		boolean changed() {
			return !this.initial.equals(this.pack.options().changed());
		}

		/** Settings of a profile, following "profile.OTHER" references. */
		Map<String, String> profileSettings(final String profile, final int depth) {
			Map<String, String> settings = new LinkedHashMap<>();
			List<String> tokens = this.profiles.get(profile);
			if (tokens == null || depth > 8) {
				return settings;
			}
			for (String token : tokens) {
				if (token.startsWith("profile.")) {
					settings.putAll(this.profileSettings(token.substring("profile.".length()), depth + 1));
				} else if (token.startsWith("!")) {
					settings.put(token.substring(1), "false");
				} else if (token.contains("=") || token.contains(":")) {
					int split = token.contains("=") ? token.indexOf('=') : token.indexOf(':');
					settings.put(token.substring(0, split), token.substring(split + 1));
				} else if (!token.isEmpty()) {
					settings.put(token, "true");
				}
			}
			return settings;
		}

		@Nullable String currentProfile() {
			PackOptions options = this.pack.options();
			for (String profile : this.profiles.keySet()) {
				boolean matches = true;
				for (Map.Entry<String, String> setting : this.profileSettings(profile, 0).entrySet()) {
					if (options.get(setting.getKey()) != null && !options.value(setting.getKey()).equals(setting.getValue())) {
						matches = false;
						break;
					}
				}
				if (matches) {
					return profile;
				}
			}
			return null;
		}

		/** Options placed on some screen (for "*"). */
		Set<String> placedOptions() {
			Set<String> placed = new HashSet<>();
			for (Map.Entry<String, String> entry : this.properties.all().entrySet()) {
				if (entry.getKey().equals("screen") || entry.getKey().startsWith("screen.") && !entry.getKey().endsWith(".columns")) {
					placed.addAll(List.of(entry.getValue().trim().split("\\s+")));
				}
			}
			return placed;
		}
	}

	private final Editor editor;
	private final @Nullable String screenName;

	private ShaderOptionsScreen(final Screen lastScreen, final Editor editor, final @Nullable String screenName, final Component title) {
		super(lastScreen, Minecraft.getInstance().options, title);
		this.editor = editor;
		this.screenName = screenName;
	}

	/** Opens the root option screen of a pack. */
	public static void open(final Screen parent, final String packName) {
		try {
			ShaderPack pack = PackManager.loadForEditing(packName);
			Editor editor = new Editor(pack, packName);
			Minecraft.getInstance().gui.setScreen(new ShaderOptionsScreen(parent, editor, null,
				Component.literal(ShaderPackScreen.stripExtension(packName))));
		} catch (Exception e) {
			MCMetal.LOGGER.error("Couldn't open the options of {}", packName, e);
		}
	}

	/** Opens a sub-screen of an open option screen (for testing). */
	public static void openSub(final @Nullable Screen current, final String name) {
		if (current instanceof ShaderOptionsScreen screen) {
			Minecraft.getInstance().gui.setScreen(new ShaderOptionsScreen(screen, screen.editor, name, Component.literal(screen.editor.lang.get("screen." + name, name))));
		}
	}

	@Override
	protected void addOptions() {
		if (this.list == null) {
			return;
		}
		String key = this.screenName == null ? "screen" : "screen." + this.screenName;
		String layout = this.editor.properties.get(key);
		List<String> tokens = new ArrayList<>();
		if (layout != null) {
			tokens.addAll(List.of(layout.trim().split("\\s+")));
		} else if (this.screenName == null) {
			tokens.add("*");
		}
		String columnsValue = this.editor.properties.get(key + ".columns");
		int columns = 2;
		if (columnsValue != null) {
			try {
				columns = Integer.parseInt(columnsValue.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		List<AbstractWidget> widgets = new ArrayList<>();
		for (String token : tokens) {
			if (token.equals("*")) {
				Set<String> placed = this.editor.placedOptions();
				for (PackOptions.Option option : this.editor.pack.options().all().values()) {
					if (!placed.contains(option.name()) && !option.allowed().isEmpty() || !placed.contains(option.name()) && option.isBoolean()) {
						widgets.add(this.optionWidget(option));
					}
				}
				continue;
			}
			AbstractWidget widget = this.tokenWidget(token);
			if (widget != null) {
				widgets.add(widget);
			}
		}
		if (columns <= 1) {
			widgets.forEach(this.list::addBig);
		} else {
			for (int i = 0; i < widgets.size(); i += 2) {
				this.list.addSmall(widgets.get(i), i + 1 < widgets.size() ? widgets.get(i + 1) : null);
			}
		}
	}

	private @Nullable AbstractWidget tokenWidget(final String token) {
		if (token.equals("<empty>")) {
			return new StringWidget(Component.empty(), this.font);
		}
		if (token.equals("<profile>")) {
			return this.profileButton();
		}
		if (token.startsWith("[") && token.endsWith("]")) {
			String name = token.substring(1, token.length() - 1);
			Component label = Component.literal(this.editor.lang.get("screen." + name, name) + "...");
			Button button = Button.builder(label, b -> this.minecraft.gui.setScreen(new ShaderOptionsScreen(this, this.editor, name,
				Component.literal(this.editor.lang.get("screen." + name, name))))).build();
			String comment = this.editor.lang.get("screen." + name + ".comment");
			if (comment != null) {
				button.setTooltip(Tooltip.create(Component.literal(comment)));
			}
			return button;
		}
		PackOptions.Option option = this.editor.pack.options().get(token);
		if (option == null) {
			// Info-only entries (e.g. "ABOUT" defined as a #define without values) show as a label.
			String label = this.editor.lang.get("option." + token);
			return label != null ? new StringWidget(Component.literal(label), this.font) : null;
		}
		return this.optionWidget(option);
	}

	private AbstractWidget profileButton() {
		List<String> names = new ArrayList<>(this.editor.profiles.keySet());
		String current = this.editor.currentProfile();
		MutableComponent label = Component.literal("Profile: ").append(current != null ? Component.literal(this.editor.lang.get("profile." + current, current))
			: Component.literal("Custom").withStyle(ChatFormatting.GOLD));
		Button button = Button.builder(label, b -> {
			if (names.isEmpty()) {
				return;
			}
			int index = current != null ? names.indexOf(current) : -1;
			index = this.minecraft.hasShiftDown() ? (index <= 0 ? names.size() - 1 : index - 1) : (index + 1) % names.size();
			for (Map.Entry<String, String> setting : this.editor.profileSettings(names.get(index), 0).entrySet()) {
				this.editor.pack.options().set(setting.getKey(), setting.getValue());
			}
			this.rebuildWidgets();
		}).build();
		button.active = !names.isEmpty();
		return button;
	}

	private Component optionLabel(final PackOptions.Option option) {
		return Component.literal(this.editor.lang.get("option." + option.name(), option.name()));
	}

	private Component valueLabel(final PackOptions.Option option, final String value) {
		if (option.isBoolean()) {
			boolean on = Boolean.parseBoolean(value);
			return on ? CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN) : CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED);
		}
		String label = this.editor.lang.get("value." + option.name() + "." + value);
		if (label == null) {
			label = this.editor.lang.get("prefix." + option.name(), "") + value + this.editor.lang.get("suffix." + option.name(), "");
		}
		return Component.literal(label);
	}

	private Component message(final PackOptions.Option option, final String value) {
		MutableComponent message = Component.empty().append(this.optionLabel(option)).append(": ").append(this.valueLabel(option, value));
		if (this.editor.pack.options().isChanged(option.name())) {
			message = Component.literal("* ").withStyle(ChatFormatting.YELLOW).append(message);
		}
		return message;
	}

	private List<String> values(final PackOptions.Option option) {
		if (option.isBoolean()) {
			return List.of("false", "true");
		}
		List<String> values = new ArrayList<>(option.allowed());
		if (!values.contains(option.defaultValue())) {
			values.add(0, option.defaultValue());
		}
		return values;
	}

	private AbstractWidget optionWidget(final PackOptions.Option option) {
		PackOptions options = this.editor.pack.options();
		List<String> values = this.values(option);
		AbstractWidget widget;
		if (this.editor.sliders.contains(option.name()) && values.size() > 1) {
			widget = new ValueSlider(option, values);
		} else {
			widget = Button.builder(this.message(option, options.value(option.name())), b -> {
				int index = values.indexOf(options.value(option.name()));
				index = this.minecraft.hasShiftDown() ? (index <= 0 ? values.size() - 1 : index - 1) : (index + 1) % values.size();
				options.set(option.name(), values.get(index));
				b.setMessage(this.message(option, values.get(index)));
			}).build();
		}
		String comment = this.editor.lang.get("option." + option.name() + ".comment");
		if (comment != null) {
			widget.setTooltip(Tooltip.create(Component.literal(comment.replace("§r", "").replace(". ", ".\n"))));
		}
		return widget;
	}

	/** A slider over an option's allowed values. */
	private final class ValueSlider extends AbstractSliderButton {
		private final PackOptions.Option option;
		private final List<String> values;

		ValueSlider(final PackOptions.Option option, final List<String> values) {
			super(0, 0, 150, 20, Component.empty(), indexValue(values, ShaderOptionsScreen.this.editor.pack.options().value(option.name())));
			this.option = option;
			this.values = values;
			this.updateMessage();
		}

		private static double indexValue(final List<String> values, final String value) {
			int index = Math.max(0, values.indexOf(value));
			return values.size() <= 1 ? 0.0 : (double) index / (values.size() - 1);
		}

		private String current() {
			int index = (int) Math.round(this.value * (this.values.size() - 1));
			return this.values.get(Math.max(0, Math.min(this.values.size() - 1, index)));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(ShaderOptionsScreen.this.message(this.option, this.current()));
		}

		@Override
		protected void applyValue() {
			ShaderOptionsScreen.this.editor.pack.options().set(this.option.name(), this.current());
		}
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(Component.literal("Reset"), button -> {
			for (String name : List.copyOf(this.editor.pack.options().changed().keySet())) {
				this.editor.pack.options().set(name, null);
			}
			this.rebuildWidgets();
		}).width(100).tooltip(Tooltip.create(Component.literal("Resets every option of the pack to its default"))).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
	}

	@Override
	public void onClose() {
		if (this.screenName == null && this.editor.changed()) {
			PackManager.saveOptions(this.editor.pack);
			if (this.editor.packName.equals(PackManager.selected())) {
				PackManager.reload();
			}
		}
		this.minecraft.gui.setScreen(this.lastScreen);
	}

	@Override
	protected void rebuildWidgets() {
		// Options can change which screens and entries exist (#if in shaders.properties).
		this.editor.properties = this.editor.parseProperties();
		super.rebuildWidgets();
	}

	@Override
	public void removed() {
	}
}
