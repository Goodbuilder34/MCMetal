package com.mcmetal.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mcmetal.shaderpack.ShadowCasters;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shaderpacks: the game only builds and updates the meshes of sections the camera sees, so shadow casters outside the
 * view were missing from the shadow map (or kept stale geometry after blocks changed). Sections the shadow pass draws
 * get their section updates scheduled too.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorPackMixin {
	@Shadow @Final private LevelRenderer levelRenderer;
	@org.spongepowered.asm.mixin.Unique
	private int mcmetal$frame;

	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", ordinal = 0))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> mcmetal$withShadowCasters(final ObjectArrayList<SectionRenderDispatcher.RenderSection> visible) {
		ObjectArrayList<SectionRenderDispatcher.RenderSection> casters = ((ShadowCasters) this.levelRenderer).mcmetal$shadowCasters();
		// Sections out of view only need their pending rebuilds picked up eventually: check them every fourth frame.
		if (casters.isEmpty() || (this.mcmetal$frame++ & 3) != 0) {
			return visible;
		}
		ReferenceOpenHashSet<SectionRenderDispatcher.RenderSection> seen = new ReferenceOpenHashSet<>(visible);
		ObjectArrayList<SectionRenderDispatcher.RenderSection> all = new ObjectArrayList<>(visible);
		for (SectionRenderDispatcher.RenderSection section : casters) {
			if (seen.add(section)) {
				all.add(section);
			}
		}
		return all;
	}
}
