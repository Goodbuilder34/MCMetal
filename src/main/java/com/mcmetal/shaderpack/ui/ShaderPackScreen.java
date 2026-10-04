package com.mcmetal.shaderpack.ui;

import com.mcmetal.shaderpack.PackManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.Blaze3D;
import org.jspecify.annotations.Nullable;

/** Lists the packs in shaderpacks/ (plus "off"); clicking one switches to it. Opened with O or from Video Settings. */
public final class ShaderPackScreen extends OptionsSubScreen {
	public ShaderPackScreen(final @Nullable Screen lastScreen) {
		super(lastScreen, net.minecraft.client.Minecraft.getInstance().options, Component.literal("Shader Packs"));
	}

	@Override
	protected void addOptions() {
		if (this.list == null) {
			return;
		}
		String selected = PackManager.selected();
		this.list.addBig(this.renderScaleButton());
		this.list.addBig(this.packButton(null, selected == null));
		for (String name : PackManager.available()) {
			this.list.addBig(this.packButton(name, name.equals(selected)));
		}
		if (selected != null) {
			List<String> problems = PackManager.problems();
			if (!problems.isEmpty()) {
				this.list.addHeader(Component.literal(problems.size() + " program(s) couldn't be translated (hover for details)").withStyle(ChatFormatting.YELLOW));
				Button details = Button.builder(Component.literal("Details"), b -> {}).build();
				details.setTooltip(Tooltip.create(Component.literal(String.join("\n", problems.subList(0, Math.min(problems.size(), 12))))));
				this.list.addBig(details);
			}
		}
	}

	private static final float[] SCALES = {1.0F, 0.85F, 0.75F, 0.67F, 0.5F};

	private Button renderScaleButton() {
		Button button = Button.builder(scaleLabel(PackManager.renderScale()), b -> {
			float current = PackManager.renderScale();
			int index = 0;
			for (int i = 0; i < SCALES.length; i++) {
				if (Math.abs(SCALES[i] - current) < 0.005F) {
					index = i;
				}
			}
			PackManager.setRenderScale(SCALES[(index + 1) % SCALES.length]);
			b.setMessage(scaleLabel(PackManager.renderScale()));
		}).width(310).build();
		button.setTooltip(Tooltip.create(Component.literal(
			"Resolution the shaderpack renders at, upscaled to the screen with MetalFX. The GUI stays sharp. "
				+ "Shader cost scales with pixels: 75% renders 56% of them, 50% a quarter.")));
		return button;
	}

	private static Component scaleLabel(final float scale) {
		return Component.literal("Render Scale: " + Math.round(scale * 100.0F) + "%");
	}

	private Button packButton(final @Nullable String name, final boolean selected) {
		Component label = Component.literal(name != null ? stripExtension(name) : "OFF (vanilla rendering)");
		if (selected) {
			label = Component.literal("» ").append(label).append(" «").withStyle(ChatFormatting.GREEN);
		}
		return Button.builder(label, button -> {
			if (!selected) {
				PackManager.select(name);
				this.rebuildWidgets();
			}
		}).width(310).build();
	}

	static String stripExtension(final String name) {
		return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		Button options = Button.builder(Component.literal("Shader Options..."), button -> {
			String selected = PackManager.selected();
			if (selected != null) {
				ShaderOptionsScreen.open(this, selected);
			}
		}).width(150).build();
		options.active = PackManager.selected() != null;
		footer.addChild(options);
		footer.addChild(Button.builder(Component.literal("Open Folder"), button -> Blaze3D.openPath(PackManager.packsDirectory())).width(100).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(100).build());
	}

	@Override
	public void removed() {
	}
}
