package com.mcmetal.mixin;

import com.mcmetal.metal.MetalDevice;
import com.mcmetal.shaderpack.ui.ShaderPackScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A "Shader Packs..." button at the end of Video Settings (Metal backend only). */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
	private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void mcmetal$shaderPacks(final CallbackInfo ci) {
		if (this.list != null && MetalDevice.current() != null) {
			this.list.addBig(Button.builder(Component.literal("Shader Packs..."), button -> this.minecraft.gui.setScreen(new ShaderPackScreen(this))).build());
		}
	}
}
