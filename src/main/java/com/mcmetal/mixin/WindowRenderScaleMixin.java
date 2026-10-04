package com.mcmetal.mixin;

import com.mcmetal.RenderScale;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales the framebuffer size the game renders at (see {@link RenderScale}). Both sources of the size are covered:
 * the explicit query (startup, mode changes, surface configuration) and SDL's pixel-size-changed event.
 * Mouse input uses the window's point size, so it is unaffected.
 */
@Mixin(Window.class)
public class WindowRenderScaleMixin {
	@Inject(method = "queryFramebufferSize", at = @At("RETURN"), cancellable = true)
	private void mcmetal$scaleQueriedSize(final CallbackInfoReturnable<Window.FramebufferSize> cir) {
		if (RenderScale.isActive()) {
			Window.FramebufferSize size = cir.getReturnValue();
			cir.setReturnValue(new Window.FramebufferSize(RenderScale.apply(size.width()), RenderScale.apply(size.height())));
		}
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int mcmetal$scaleEventWidth(final int width) {
		return RenderScale.isActive() && width > 0 ? RenderScale.apply(width) : width;
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int mcmetal$scaleEventHeight(final int height) {
		return RenderScale.isActive() && height > 0 ? RenderScale.apply(height) : height;
	}
}
