package com.mcmetal.shaderpack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Fills the OptiFine/Iris built-in uniforms from the game state of the current frame. */
public final class BuiltinUniforms {
	/** Converts the game's reversed [0, 1] depth projection to OpenGL's [-1, 1] convention, which packs assume. */
	public static final Matrix4f TO_GL_DEPTH = new Matrix4f().m22(-2.0F).m32(1.0F);

	private final long start = System.nanoTime();
	private long lastFrame = System.nanoTime();
	private int frameCounter;
	private float frameTimeSmooth = 1.0F / 60.0F;
	private final Matrix4f previousModelView = new Matrix4f();
	private final Matrix4f previousProjection = new Matrix4f();
	private Vec3 previousCamera = Vec3.ZERO;
	private boolean first = true;
	private float eyeBrightnessX;
	private float eyeBrightnessY;
	private float wetness;
	private float centerDepth = 1.0F;
	/** The pack's centerDepthHalflife constant, in seconds. */
	public float centerDepthHalfLife = 1.0F;

	public final Matrix4f modelView = new Matrix4f();
	public final Matrix4f projection = new Matrix4f();
	public final Vector3f sunPosition = new Vector3f();
	public final Vector3f moonPosition = new Vector3f();
	public final Vector3f shadowLightPosition = new Vector3f();
	/** World-space direction towards the light that casts shadows (sun by day, moon by night). */
	public final Vector3f shadowLightDirection = new Vector3f(0.0F, 1.0F, 0.0F);
	public float sunAngle;
	public int viewWidth;
	public int viewHeight;
	public final Matrix4f shadowModelView = new Matrix4f();
	public final Matrix4f shadowProjection = new Matrix4f();
	/** Shadow settings from the pack's constants (Iris defaults). */
	public float shadowDistance = 160.0F;
	public float shadowIntervalSize = 2.0F;
	public float shadowNearPlane = 0.05F;
	public float shadowFarPlane = 256.0F;

	/**
	 * @param levelProjection the projection the level is drawn with (bobbing included), in the game's convention
	 * @param sunPathRotation the pack's sunPathRotation constant in degrees
	 */
	public void update(final Uniforms u, final GameRenderState state, final Matrix4f levelProjection, final int width, final int height, final float sunPathRotation,
		final int renderDistanceBlocks) {
		Minecraft minecraft = Minecraft.getInstance();
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		SkyRenderState sky = state.levelRenderState.skyRenderState;
		float partial = state.levelRenderState.worldPartialTicks;
		long now = System.nanoTime();
		float frameTime = Math.min((now - this.lastFrame) / 1e9F, 1.0F);
		this.lastFrame = now;
		this.frameTimeSmooth += (frameTime - this.frameTimeSmooth) * 0.1F;
		this.viewWidth = width;
		this.viewHeight = height;

		// Matrices.
		this.modelView.set(camera.viewRotationMatrix);
		TO_GL_DEPTH.mul(levelProjection, this.projection);
		if (this.first) {
			this.previousModelView.set(this.modelView);
			this.previousProjection.set(this.projection);
			this.previousCamera = camera.pos;
		}
		u.set("gbufferModelView", this.modelView);
		u.set("gbufferModelViewInverse", new Matrix4f(this.modelView).invert());
		u.set("gbufferProjection", this.projection);
		u.set("gbufferProjectionInverse", new Matrix4f(this.projection).invert());
		u.set("gbufferPreviousModelView", this.previousModelView);
		u.set("gbufferPreviousProjection", this.previousProjection);

		Vec3 pos = camera.pos;
		u.set("cameraPosition", pos.x, pos.y, pos.z);
		u.set("previousCameraPosition", this.previousCamera.x, this.previousCamera.y, this.previousCamera.z);
		u.set("cameraPositionInt", Math.floor(pos.x), Math.floor(pos.y), Math.floor(pos.z));
		u.set("cameraPositionFract", pos.x - Math.floor(pos.x), pos.y - Math.floor(pos.y), pos.z - Math.floor(pos.z));
		u.set("previousCameraPositionInt", Math.floor(this.previousCamera.x), Math.floor(this.previousCamera.y), Math.floor(this.previousCamera.z));
		u.set("previousCameraPositionFract", this.previousCamera.x - Math.floor(this.previousCamera.x), this.previousCamera.y - Math.floor(this.previousCamera.y),
			this.previousCamera.z - Math.floor(this.previousCamera.z));
		u.set("eyeAltitude", pos.y);

		// Screen and timing.
		u.set("viewWidth", width);
		u.set("viewHeight", height);
		u.set("aspectRatio", (double) width / Math.max(1, height));
		u.set("near", 0.05);
		u.set("far", renderDistanceBlocks);
		u.set("frameCounter", this.frameCounter);
		u.set("frameTime", frameTime);
		u.set("frameTimeSmooth", this.frameTimeSmooth);
		u.set("frameTimeCounter", ((now - this.start) / 1e9) % 3600.0);
		u.set("frameMod8", this.frameCounter % 8);

		// Celestial: OptiFine's sunAngle is 0 at sunrise, 0.25 at noon (the game's angle is 0 at noon).
		float celestial = (float) (sky.sunAngle / (Math.PI * 2.0));
		this.sunAngle = fract(celestial + 0.25F);
		float shadowAngle = this.sunAngle < 0.5F ? this.sunAngle : this.sunAngle - 0.5F;
		u.set("sunAngle", this.sunAngle);
		u.set("shadowAngle", shadowAngle);
		Matrix4f celestialMatrix = new Matrix4f().rotateY((float) Math.toRadians(-90.0)).rotateZ((float) Math.toRadians(sunPathRotation)).rotateX(sky.sunAngle);
		Vector3f sunWorld = celestialMatrix.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
		Vector3f moonWorld = new Vector3f(sunWorld).negate();
		this.modelView.transformDirection(new Vector3f(sunWorld).mul(100.0F), this.sunPosition);
		this.modelView.transformDirection(new Vector3f(moonWorld).mul(100.0F), this.moonPosition);
		boolean day = this.sunAngle < 0.5F;
		this.shadowLightPosition.set(day ? this.sunPosition : this.moonPosition);
		this.shadowLightDirection.set(day ? sunWorld : moonWorld);
		u.set("sunPosition", this.sunPosition.x, this.sunPosition.y, this.sunPosition.z);
		u.set("moonPosition", this.moonPosition.x, this.moonPosition.y, this.moonPosition.z);
		u.set("shadowLightPosition", this.shadowLightPosition.x, this.shadowLightPosition.y, this.shadowLightPosition.z);
		Vector3f up = this.modelView.transformDirection(new Vector3f(0.0F, 100.0F, 0.0F));
		u.set("upPosition", up.x, up.y, up.z);
		u.set("moonPhase", sky.moonPhase.index());

		// Shadow matrices (Iris's ShadowMatrices): an orthographic view from the sun or moon, snapped to a grid.
		float skyAngle = shadowAngle < 0.25F ? shadowAngle + 0.75F : shadowAngle - 0.25F;
		this.shadowModelView.identity().translate(0.0F, 0.0F, -100.0F).rotateX((float) Math.toRadians(90.0)).rotateZ((float) Math.toRadians(skyAngle * -360.0F))
			.rotateX((float) Math.toRadians(sunPathRotation));
		if (this.shadowIntervalSize != 0.0F) {
			float half = this.shadowIntervalSize / 2.0F;
			this.shadowModelView.translate((float) pos.x % this.shadowIntervalSize - half, (float) pos.y % this.shadowIntervalSize - half,
				(float) pos.z % this.shadowIntervalSize - half);
		}
		float h = this.shadowDistance;
		this.shadowProjection.identity().ortho(-h, h, -h, h, this.shadowNearPlane, this.shadowFarPlane);
		u.set("shadowModelView", this.shadowModelView);
		u.set("shadowModelViewInverse", new Matrix4f(this.shadowModelView).invert());
		u.set("shadowProjection", this.shadowProjection);
		u.set("shadowProjectionInverse", new Matrix4f(this.shadowProjection).invert());
		u.set("_mcm_shadowFix", new Matrix4f(this.shadowModelView).mul(new Matrix4f(this.modelView).invert()));
		u.set("_mcm_shadowProj", this.shadowProjection);

		// Fog and sky colors.
		Vector4f fog = camera.fogData.color;
		u.set("fogColor", fog.x, fog.y, fog.z);
		if (sky.skyColor != null) {
			u.set("skyColor", sky.skyColor.x(), sky.skyColor.y(), sky.skyColor.z());
		}
		u.set("fogStart", camera.fogData.renderDistanceStart);
		u.set("fogEnd", camera.fogData.renderDistanceEnd);
		u.set("fogDensity", 0.0);
		u.set("fogMode", 9729);
		u.set("isEyeInWater", camera.fogType == FogType.WATER ? 1 : camera.fogType == FogType.LAVA ? 2 : camera.fogType == FogType.POWDER_SNOW ? 3 : 0);
		u.set("cloudHeight", state.levelRenderState.cloudHeight);
		u.set("screenBrightness", minecraft.options.gamma().get());
		u.set("hideGUI", state.guiRenderState.isHudHidden ? 1 : 0);
		u.set("renderStage", 0);
		u.set("entityId", -1);
		u.set("blockEntityId", -1);
		u.set("currentRenderedItemId", -1);
		u.set("entityColor", 0, 0, 0, 0);
		// centerDepthSmooth: depth at the screen center, from a raycast along the view direction (no GPU readback).
		float target = 1.0F;
		if (minecraft.level != null && minecraft.getCameraEntity() != null) {
			Vec3 forward = minecraft.getCameraEntity().getViewVector(partial);
			Vec3 to = camera.pos.add(forward.scale(renderDistanceBlocks));
			var hit = minecraft.level.clip(new net.minecraft.world.level.ClipContext(camera.pos, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, minecraft.getCameraEntity()));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
				float d = (float) Math.max(0.05, hit.getLocation().distanceTo(camera.pos));
				float ndc = (-this.projection.m22() * d + this.projection.m32()) / d;
				target = Math.min(1.0F, ndc * 0.5F + 0.5F);
			}
		}
		float halfLife = this.centerDepthHalfLife;
		this.centerDepth = this.first ? target : this.centerDepth + (target - this.centerDepth) * (1.0F - (float) Math.pow(0.5, frameTime / Math.max(halfLife, 1e-3F)));
		u.set("centerDepthSmooth", this.centerDepth);
		u.set("endFlashIntensity", sky.endFlashIntensity);

		var level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level != null) {
			long time = level.getDefaultClockTime();
			u.set("worldTime", time % 24000L);
			u.set("worldDay", time / 24000L);
			float rain = level.getRainLevel(partial);
			u.set("rainStrength", rain);
			this.wetness += (rain - this.wetness) * (1.0F - (float) Math.exp(-frameTime / (rain > this.wetness ? 30.0F : 10.0F)));
			u.set("wetness", this.wetness);
			u.set("thunderStrength", level.getThunderLevel(partial));
			u.set("bedrockLevel", level.getMinY());
			u.set("heightLimit", level.getHeight());
			u.set("logicalHeightLimit", level.getHeight());
			u.set("hasSkylight", level.dimensionType().hasSkyLight() ? 1 : 0);
			u.set("hasCeiling", level.dimensionType().hasCeiling() ? 1 : 0);
			u.set("ambientLight", level.dimensionType().ambientLight());

			BlockPos eye = camera.blockPos;
			int block = level.getBrightness(LightLayer.BLOCK, eye);
			int skyLight = level.getBrightness(LightLayer.SKY, eye);
			u.set("eyeBrightness", block * 16, skyLight * 16);
			float k = 1.0F - (float) Math.exp(-frameTime / 0.5F);
			if (this.first) {
				this.eyeBrightnessX = block * 16;
				this.eyeBrightnessY = skyLight * 16;
			}
			this.eyeBrightnessX += (block * 16 - this.eyeBrightnessX) * k;
			this.eyeBrightnessY += (skyLight * 16 - this.eyeBrightnessY) * k;
			u.set("eyeBrightnessSmooth", Math.round(this.eyeBrightnessX), Math.round(this.eyeBrightnessY));

			Holder<Biome> biome = level.getBiome(eye);
			int biomeId = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getId(biome.value());
			u.set("biome", biomeId);
			u.set("temperature", biome.value().getBaseTemperature());
			u.set("rainfall", biome.value().hasPrecipitation() ? 0.5 : 0.0);
			Biome.Precipitation precipitation = biome.value().getPrecipitationAt(eye, level.getSeaLevel());
			u.set("biome_precipitation", precipitation == Biome.Precipitation.NONE ? 0 : precipitation == Biome.Precipitation.RAIN ? 1 : 2);
			u.set("biome_category", -1);
		}
		if (player != null) {
			u.set("nightVision", player.hasEffect(MobEffects.NIGHT_VISION) ? 1.0 : 0.0);
			u.set("blindness", player.hasEffect(MobEffects.BLINDNESS) ? player.getEffectBlendFactor(MobEffects.BLINDNESS, partial) : 0.0);
			u.set("darknessFactor", player.hasEffect(MobEffects.DARKNESS) ? player.getEffectBlendFactor(MobEffects.DARKNESS, partial) : 0.0);
			u.set("darknessLightFactor", 0.0);
			u.set("playerMood", player.getCurrentMood());
			u.set("is_sneaking", player.isCrouching() ? 1 : 0);
			u.set("is_sprinting", player.isSprinting() ? 1 : 0);
			u.set("is_hurt", player.hurtTime > 0 ? 1 : 0);
			u.set("is_invisible", player.isInvisible() ? 1 : 0);
			u.set("is_burning", player.isOnFire() ? 1 : 0);
			u.set("is_on_ground", player.onGround() ? 1 : 0);
			u.set("isElytraFlying", player.isFallFlying() ? 1 : 0);
			Vec3 eyePos = player.getEyePosition(partial);
			u.set("eyePosition", eyePos.x, eyePos.y, eyePos.z);
			u.set("relativeEyePosition", eyePos.x - pos.x, eyePos.y - pos.y, eyePos.z - pos.z);
			Vec3 look = player.getViewVector(partial);
			u.set("playerLookVector", look.x, look.y, look.z);
			u.set("playerBodyVector", look.x, 0, look.z);
			ItemStack main = player.getMainHandItem();
			ItemStack off = player.getOffhandItem();
			u.set("heldBlockLightValue", lightOf(main));
			u.set("heldBlockLightValue2", lightOf(off));
			u.set("velocity", player.getDeltaMovement().length());
		}
		if (!u.has("heldItemId")) {
			u.set("heldItemId", -1);
			u.set("heldItemId2", -1);
		}

		u.updateCustom();
		this.frameCounter++;
		this.first = false;
	}

	public int frame() {
		return this.frameCounter;
	}

	/** Remembers this frame's matrices for gbufferPrevious* (call at the end of the frame). */
	public void endFrame(final CameraRenderState camera) {
		this.previousModelView.set(this.modelView);
		this.previousProjection.set(this.projection);
		this.previousCamera = camera.pos;
	}

	private static int lightOf(final ItemStack stack) {
		if (stack.getItem() instanceof BlockItem blockItem) {
			return blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		return 0;
	}

	private static float fract(final float x) {
		return x - (float) Math.floor(x);
	}
}
