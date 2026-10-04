package com.mcmetal.shaderpack;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** Implemented by the game's LevelRenderer (see LevelRendererPackMixin): the sections the last shadow pass drew. */
public interface ShadowCasters {
	ObjectArrayList<SectionRenderDispatcher.RenderSection> mcmetal$shadowCasters();
}
