package com.mcmetal.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** All sections around the camera, for gathering shadow casters outside the camera's view. */
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
	@Accessor("sections")
	RotatingSectionStorage<SectionRenderDispatcher.RenderSection> mcmetal$sections();
}
