package com.mcmetal.mixin;

import com.mcmetal.shaderpack.PackManager;
import com.mcmetal.shaderpack.PackRenderer;
import com.mcmetal.shaderpack.ShadowCasters;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shaderpacks: the shadow pass (terrain from the shadow light, encoded before the level's frame graph runs), and the
 * deferred passes between the opaque and the translucent geometry.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererPackMixin implements ShadowCasters {
	@Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow private @Nullable ViewArea viewArea;
	@Shadow private boolean usingMultiDrawIndirectForTerrain;
	@Shadow @Final private LevelRenderState levelRenderState;
	@Shadow @Final private TextureManager textureManager;
	@Shadow @Final private GameRenderer gameRenderer;

	@org.spongepowered.asm.mixin.Unique
	private final ObjectArrayList<SectionRenderDispatcher.RenderSection> mcmetal$shadowCasters = new ObjectArrayList<>();

	@Override
	public ObjectArrayList<SectionRenderDispatcher.RenderSection> mcmetal$shadowCasters() {
		return this.mcmetal$shadowCasters;
	}

	@Shadow
	public abstract ChunkSectionsToRender prepareChunkRenders(org.joml.Matrix4fc modelViewMatrix, boolean respectTranslucentOrder);

	@Shadow
	public abstract ChunkSectionsToRender prepareChunkRendersIndirect(org.joml.Matrix4fc modelViewMatrix, boolean respectTranslucentOrder);

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void mcmetal$beforeTranslucent(final CallbackInfo ci) {
		PackManager.beforeTranslucent();
	}

	@Inject(method = "addMainPass", at = @At("HEAD"))
	private void mcmetal$shadowPass(final FrameGraphBuilder frame, final FeatureRenderDispatcher.PreparedFrame featureFrame, final GpuBufferSlice terrainFog,
		final ChunkSectionsToRender chunkSectionsToRender, final boolean consistentDepthRequired, final CallbackInfo ci) {
		PackRenderer renderer = PackManager.current();
		this.mcmetal$shadowCasters.clear();
		if (renderer == null || !renderer.wantsShadow() || this.viewArea == null) {
			return;
		}
		// Shadow casters: sections within the shadow distance whose box reaches into the shadow frustum. All of them get
		// their meshes built and kept up to date (the game only does that for sections the camera sees, see
		// LevelExtractorPackMixin); only those that can shadow something the camera sees are drawn.
		Vec3 camera = this.levelRenderState.cameraRenderState.pos;
		float range = renderer.shadowRenderDistance();
		renderer.beginShadowReceivers();
		for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
			AABB box = section.getBoundingBox();
			renderer.addShadowReceiver((float) (box.minX - camera.x), (float) (box.minY - camera.y), (float) (box.minZ - camera.z), (float) (box.maxX - camera.x),
				(float) (box.maxY - camera.y), (float) (box.maxZ - camera.z));
		}
		ObjectArrayList<SectionRenderDispatcher.RenderSection> saved = new ObjectArrayList<>(this.visibleSections);
		this.visibleSections.clear();
		for (SectionRenderDispatcher.RenderSection section : ((ViewAreaAccessor) this.viewArea).mcmetal$sections()) {
			AABB box = section.getBoundingBox();
			float minX = (float) (box.minX - camera.x);
			float minY = (float) (box.minY - camera.y);
			float minZ = (float) (box.minZ - camera.z);
			float maxX = (float) (box.maxX - camera.x);
			float maxY = (float) (box.maxY - camera.y);
			float maxZ = (float) (box.maxZ - camera.z);
			if (Math.max(Math.abs(minX), Math.abs(maxX)) - 16.0F > range || Math.max(Math.abs(minZ), Math.abs(maxZ)) - 16.0F > range) {
				continue;
			}
			if (renderer.inShadowFrustum(minX, minY, minZ, maxX, maxY, maxZ)) {
				this.mcmetal$shadowCasters.add(section);
				if (section.getSectionMesh().hasRenderableLayers() && renderer.shadowsReceiver(minX, minY, minZ, maxX, maxY, maxZ)) {
					this.visibleSections.add(section);
				}
			}
		}
		Matrix4f terrainMatrix = new Matrix4f(this.levelRenderState.cameraRenderState.viewRotationMatrix);
		ChunkSectionsToRender shadow;
		try {
			shadow = this.usingMultiDrawIndirectForTerrain ? this.prepareChunkRendersIndirect(terrainMatrix, false) : this.prepareChunkRenders(terrainMatrix, false);
		} finally {
			this.visibleSections.clear();
			this.visibleSections.addAll(saved);
		}

		RenderTarget main = this.gameRenderer.mainRenderTarget();
		GpuTextureView atlas = this.textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		renderer.beginShadow();
		try {
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Shadow (opaque)", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.of(0.0))) {
				RenderSystem.bindDefaultUniforms(pass);
				shadow.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
				if (renderer.shadowEntities()) {
					// Entities and block entities submitted this frame (those in the camera's view).
					featureFrame.executeSolid(pass);
				}
			}
			renderer.shadowOpaqueDone();
			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "Shadow (translucent)", main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				shadow.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
			}
		} finally {
			renderer.endShadow();
		}
	}
}
