package com.mcmetal.mixin;

import com.mcmetal.shaderpack.PackManager;
import com.mcmetal.shaders.ShaderLayer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the Metal shader layer ({@link ShaderLayer}) into the frame: after the level, and after post effects. */
@Mixin(GameRenderer.class)
public class GameRendererShaderMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@org.spongepowered.asm.mixin.Unique
	private final Matrix4f mcmetal$levelProjection = new Matrix4f();

	@org.spongepowered.asm.mixin.Unique
	private boolean mcmetal$packFrame;

	@ModifyArg(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
		)
	)
	private Matrix4f mcmetal$captureProjection(final Matrix4f projection) {
		ShaderLayer.captureProjection(projection);
		this.mcmetal$levelProjection.set(projection);
		return projection;
	}

	@Inject(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V")
	)
	private void mcmetal$beginPackLevel(final CallbackInfo ci) {
		this.mcmetal$packFrame = PackManager.beginLevel(this.gameRenderState, this.mcmetal$levelProjection, this.mainRenderTarget);
	}

	@Inject(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V", shift = At.Shift.AFTER)
	)
	private void mcmetal$worldEffects(final CallbackInfo ci) {
		if (this.mcmetal$packFrame) {
			PackManager.endLevel();
			return;
		}
		if (ShaderLayer.isActive()) {
			ShaderLayer.applyWorld(this.gameRenderState, this.mainRenderTarget);
		}
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
	private void mcmetal$beginPackHand(final CallbackInfo ci) {
		if (this.mcmetal$packFrame) {
			PackManager.beginHand();
		}
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", shift = At.Shift.AFTER))
	private void mcmetal$endPackHand(final CallbackInfo ci) {
		if (this.mcmetal$packFrame) {
			PackManager.endHand(this.gameRenderState);
			this.mcmetal$packFrame = false;
		}
	}

	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void mcmetal$classicTransparencyForPacks(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (PackManager.active()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void mcmetal$finalEffects(final CallbackInfo ci) {
		if (ShaderLayer.isActive() && !PackManager.active()) {
			ShaderLayer.applyFinal(this.mainRenderTarget);
		}
	}
}
