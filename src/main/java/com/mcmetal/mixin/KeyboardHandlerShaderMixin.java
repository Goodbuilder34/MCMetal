package com.mcmetal.mixin;

import com.mcmetal.metal.MetalDevice;
import com.mcmetal.shaderpack.ui.ShaderPackScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** O opens the shader pack screen in game (as with Iris). */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerShaderMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void mcmetal$shaderPackKey(final long handle, final int action, final KeyEvent event, final CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (action == InputConstants.PRESS && event.key() == InputConstants.KEY_O && minecraft.gui.screen() == null && minecraft.level != null
			&& MetalDevice.current() != null && handle == minecraft.getWindow().handle()) {
			minecraft.gui.setScreen(new ShaderPackScreen(null));
			ci.cancel();
		}
	}
}
