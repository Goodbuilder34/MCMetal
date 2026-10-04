package com.mcmetal.mixin;

import com.mcmetal.bench.Benchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftBenchmarkMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void mcmetal$benchmarkFrame(final boolean advanceGameTime, final CallbackInfo ci) {
		if (Benchmark.enabled()) {
			Benchmark.onFrameEnd((Minecraft) (Object) this);
		}
	}
}
