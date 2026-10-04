package com.mcmetal.mixin;

import com.mcmetal.metal.MetalSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks the start of each frame just before input events are polled, where the frame limiter waits. */
@Mixin(Minecraft.class)
public class MinecraftFrameLimiterMixin {
	@Inject(
		method = "run",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V")
	)
	private void mcmetal$beginFrame(final CallbackInfo ci) {
		MetalSurface.beginFrame();
	}
}
