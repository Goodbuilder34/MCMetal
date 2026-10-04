package com.mcmetal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcmetal.shaderpack.BlockIds;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shaderpacks: tags chunk vertices with the block.properties ID of the block (or fluid) they belong to. */
@Mixin(SectionCompiler.class)
public class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void mcmetal$tagBlock(final ModelBlockRenderer renderer, final BlockQuadOutput output, final float x, final float y, final float z,
		final BlockAndTintGetter level, final BlockPos pos, final BlockState state, final BlockStateModel model, final long seed, final Operation<Void> original) {
		if (!BlockIds.enabled()) {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
			return;
		}
		BlockIds.begin(state);
		try {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} finally {
			BlockIds.end();
		}
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void mcmetal$tagFluid(final FluidRenderer renderer, final BlockAndTintGetter level, final BlockPos pos, final FluidRenderer.Output output,
		final BlockState state, final FluidState fluid, final Operation<Void> original) {
		if (!BlockIds.enabled()) {
			original.call(renderer, level, pos, output, state, fluid);
			return;
		}
		BlockIds.begin(fluid.createLegacyBlock());
		try {
			original.call(renderer, level, pos, output, state, fluid);
		} finally {
			BlockIds.end();
		}
	}
}
