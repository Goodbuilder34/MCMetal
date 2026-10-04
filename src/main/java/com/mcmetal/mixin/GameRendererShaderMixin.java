package com.mcmetal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mcmetal.shaderpack.PackManager;
import com.mcmetal.shaders.ShaderLayer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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

	/** The view rotation before view bobbing was moved into it (restored after the level, see {@link #mcmetal$bobInModelView}). */
	@org.spongepowered.asm.mixin.Unique
	private final Matrix4f mcmetal$viewRotation = new Matrix4f();

	@org.spongepowered.asm.mixin.Unique
	private boolean mcmetal$bobMoved;

	/**
	 * Shaderpacks: view bobbing (and the hurt tilt) go in the model-view matrix instead of the projection, like Iris.
	 * Packs rebuild positions from depth assuming a plain perspective projection (only its diagonal and last column);
	 * with the bobbing in it, shading and shadows swayed up and down while walking. Drawing is unchanged: P * (B * V).
	 */
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;"))
	private Matrix4f mcmetal$bobInModelView(final Matrix4f projection, final Matrix4fc bob, final Operation<Matrix4f> original) {
		if (!PackManager.active()) {
			return original.call(projection, bob);
		}
		Matrix4f viewRotation = this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix;
		this.mcmetal$viewRotation.set(viewRotation);
		this.mcmetal$bobMoved = true;
		new Matrix4f(bob).mul(this.mcmetal$viewRotation, viewRotation);
		return projection;
	}

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
		if (this.mcmetal$bobMoved) {
			this.mcmetal$bobMoved = false;
			this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix.set(this.mcmetal$viewRotation);
		}
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
