package com.mcmetal.mixin;

import com.mcmetal.shaderpack.BlockIds;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Shaderpacks: block IDs ride in the high bytes of terrain vertices' light coordinates (see {@link BlockIds}). */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {
	@Shadow @Final private boolean blockFormat;

	@ModifyVariable(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private int mcmetal$blockId(final int lightCoords) {
		return this.blockFormat ? BlockIds.encodeLight(lightCoords) : lightCoords;
	}
}
