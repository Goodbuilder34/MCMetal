package com.mcmetal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcmetal.shaderpack.BlockIds;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shaderpacks: chunk vertex colors as Iris builds them. With {@code separateAo} the ambient occlusion goes in the
 * alpha (the pack applies its own, softer AO) instead of darkening the color, and unless the pack asks for
 * {@code oldLighting} the game's per-face shading is left out (the pack lights faces from their normals).
 */
@Mixin(BlockModelLighter.class)
public class BlockModelLighterMixin {
	@WrapOperation(method = "prepareQuadAmbientOcclusion", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/QuadInstance;scaleColor(F)V"))
	private void mcmetal$ambientOcclusion(final QuadInstance instance, final float faceShade, final Operation<Void> original) {
		if (BlockIds.separateAo()) {
			// The AO colors are gray: move the level into the alpha.
			for (int v = 0; v < 4; v++) {
				instance.setColor(v, ARGB.color(ARGB.red(instance.getColor(v)), 255, 255, 255));
			}
		}
		if (!BlockIds.noFaceShading()) {
			original.call(instance, faceShade);
		}
	}

	@WrapOperation(method = "prepareQuadFlat", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/QuadInstance;setColor(I)V"))
	private void mcmetal$flat(final QuadInstance instance, final int faceShade, final Operation<Void> original) {
		original.call(instance, BlockIds.noFaceShading() ? -1 : faceShade);
	}
}
