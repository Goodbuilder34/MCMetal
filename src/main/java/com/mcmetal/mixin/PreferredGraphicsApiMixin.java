package com.mcmetal.mixin;

import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalBackend;
import com.mojang.renderpearl.api.device.GpuBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts Metal in front of the vanilla backend order; OpenGL/Vulkan remain as fallbacks if Metal fails to start. */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
	private void mcmetal$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
		if (!MCMetal.isEnabled()) {
			return;
		}
		GpuBackend[] vanilla = cir.getReturnValue();
		GpuBackend[] backends = new GpuBackend[vanilla.length + 1];
		backends[0] = new MetalBackend();
		System.arraycopy(vanilla, 0, backends, 1, vanilla.length);
		cir.setReturnValue(backends);
	}
}
