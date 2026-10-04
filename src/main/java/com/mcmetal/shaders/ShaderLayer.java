package com.mcmetal.shaders;

import com.mcmetal.Config;
import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalDevice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The Metal shader layer: screen-space lighting and post-processing drawn by libmcmetal (src/native/effects.mm).
 *
 * <p>Runs in two stages, hooked into {@code GameRenderer} by {@code GameRendererShaderMixin}: the world stage right
 * after the level is drawn (so the hand and HUD are untouched by AO, shadows and haze) and the final stage after the
 * game's own post effects, before the GUI. Only active on the Metal backend.
 *
 * <p>Settings ({@code config/mcmetal.properties} or {@code -Dmcmetal.<key>=...}); a strength of 0 turns an effect off:
 * <ul>
 *   <li>{@code shaders} (true/false, default true)</li>
 *   <li>{@code shaders.quality} (low/medium/high/ultra, default high): AO sample count</li>
 *   <li>{@code shaders.ao} (default 1.0), {@code shaders.aoRadius} (blocks, default 1.5)</li>
 *   <li>{@code shaders.sunShadows} (0-1, default 0.4): darkening of surfaces turned away from or shadowed from the sun</li>
 *   <li>{@code shaders.shadowLength} (blocks, default 3.0): reach of the screen-space contact shadows</li>
 *   <li>{@code shaders.tint} (0-1, default 1.0): warm sunlight / cool shade coloring</li>
 *   <li>{@code shaders.godrays} (default 0.6), {@code shaders.haze} (default 1.0)</li>
 *   <li>{@code shaders.bloom} (default 0.8), {@code shaders.tonemap} (0-1, default 0.5), {@code shaders.exposure} (default 1.0)</li>
 *   <li>{@code shaders.saturation} (default 1.1), {@code shaders.vignette} (default 0.2)</li>
 * </ul>
 */
public final class ShaderLayer {
	private static final int FX_AO = 1;
	private static final int FX_SHADOWS = 1 << 1;
	private static final int FX_RAYS = 1 << 2;
	private static final int FX_HAZE = 1 << 3;
	private static final int FX_BLOOM = 1 << 4;
	private static final int FX_TONEMAP = 1 << 5;

	// Float offsets into the parameter block (FxParams in effects.mm).
	private static final int INV_PROJ = 0;
	private static final int PROJ = 16;
	private static final int LIGHT = 32;
	private static final int LIGHT_COLOR = 36;
	private static final int FOG_COLOR = 40;
	private static final int AO = 44;
	private static final int GRADE = 48;
	private static final int MISC = 52;
	private static final int PARAM_FLOATS = 56;

	private static final boolean ENABLED = bool("shaders", false);
	private static final int AO_SAMPLES = switch (string("shaders.quality", "high")) {
		case "low" -> 8;
		case "medium" -> 12;
		case "ultra" -> 24;
		default -> 16;
	};
	private static final float AO_STRENGTH = number("shaders.ao", 1.0F);
	private static final float AO_RADIUS = number("shaders.aoRadius", 1.5F);
	private static final float SUN_SHADOWS = number("shaders.sunShadows", 0.4F);
	private static final float SHADOW_LENGTH = number("shaders.shadowLength", 3.0F);
	private static final float TINT = number("shaders.tint", 1.0F);
	private static final float GODRAYS = number("shaders.godrays", 0.6F);
	private static final float HAZE = number("shaders.haze", 1.0F);
	private static final float BLOOM = number("shaders.bloom", 0.8F);
	private static final float TONEMAP = number("shaders.tonemap", 0.5F);
	private static final float EXPOSURE = number("shaders.exposure", 1.0F);
	private static final float SATURATION = number("shaders.saturation", 1.1F);
	private static final float VIGNETTE = number("shaders.vignette", 0.2F);

	private static final MemorySegment PARAMS = Arena.global().allocate((long) PARAM_FLOATS * Float.BYTES, 16);
	private static final float[] MATRIX = new float[16];
	private static final Matrix4f projection = new Matrix4f();
	private static final Matrix4f inverseProjection = new Matrix4f();
	private static final Vector3f light = new Vector3f();
	private static final Vector3f lightColor = new Vector3f();
	private static boolean projectionCaptured;
	private static float skyExposure = 1.0F;
	private static int frame;
	private static boolean logged;

	private ShaderLayer() {
	}

	public static boolean isActive() {
		return ENABLED && MetalDevice.current() != null && !com.mcmetal.shaderpack.PackManager.active();
	}

	/** The projection the level is actually drawn with (camera bobbing and nausea included). */
	public static void captureProjection(final Matrix4f matrix) {
		projection.set(matrix);
		projectionCaptured = true;
	}

	public static void applyWorld(final GameRenderState state, final RenderTarget target) {
		MetalDevice device = MetalDevice.current();
		GpuTexture color = target.getColorTexture();
		GpuTexture depth = target.getDepthTexture();
		if (!ENABLED || device == null || color == null || depth == null) {
			return;
		}
		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		SkyRenderState sky = state.levelRenderState.skyRenderState;
		if (!projectionCaptured) {
			projection.set(camera.projectionMatrix);
		}
		projectionCaptured = false;
		projection.invert(inverseProjection);
		putMatrix(INV_PROJ, inverseProjection);
		putMatrix(PROJ, projection);

		updateSkyExposure(camera);
		boolean submerged = camera.fogType == FogType.WATER || camera.fogType == FogType.LAVA || camera.fogType == FogType.POWDER_SNOW;

		// Sun or moon, whichever is higher; both are dark near the horizon so the switch is invisible.
		float intensity = 0.0F;
		lightColor.set(0.0F);
		if (sky.skybox == DimensionType.Skybox.OVERWORLD && !submerged) {
			float sunY = (float) Math.cos(sky.sunAngle);
			float moonY = (float) Math.cos(sky.moonAngle);
			boolean sun = sunY >= moonY;
			float angle = sun ? sky.sunAngle : sky.moonAngle;
			float height = sun ? sunY : moonY;
			light.set((float) -Math.sin(angle), (float) Math.cos(angle), 0.0F);
			float rise = smoothstep(-0.05F, 0.25F, height);
			if (sun) {
				intensity = rise;
				float day = smoothstep(0.0F, 0.35F, height);
				lightColor.set(1.0F, 0.5F + 0.46F * day, 0.25F + 0.65F * day);
			} else {
				intensity = rise * 0.3F;
				lightColor.set(0.55F, 0.65F, 1.0F).mul(0.5F);
			}
			intensity *= sky.rainBrightness * sky.rainBrightness * skyExposure;
		} else {
			light.set(0.0F, 1.0F, 0.0F);
		}
		camera.viewRotationMatrix.transformDirection(light).normalize();

		float shadowStrength = SUN_SHADOWS * intensity;
		float rays = GODRAYS * intensity;
		putVec(LIGHT, light.x, light.y, light.z, shadowStrength);
		putVec(LIGHT_COLOR, lightColor.x, lightColor.y, lightColor.z, rays);
		Vector4f fog = camera.fogData.color;
		float haze = submerged ? 0.0F : HAZE * 0.0012F * skyExposure;
		putVec(FOG_COLOR, fog.x * fog.x, fog.y * fog.y, fog.z * fog.z, haze);
		putVec(AO, AO_STRENGTH, AO_RADIUS, AO_SAMPLES, SHADOW_LENGTH);
		putVec(GRADE, BLOOM, EXPOSURE, SATURATION, VIGNETTE);
		putVec(MISC, frame++, TONEMAP, TINT, 0.0F);

		int flags = 0;
		if (AO_STRENGTH > 0.0F) {
			flags |= FX_AO;
		}
		if (shadowStrength > 0.001F) {
			flags |= FX_SHADOWS;
		}
		if (rays > 0.001F) {
			flags |= FX_RAYS;
		}
		if (haze > 0.0F) {
			flags |= FX_HAZE;
		}
		if (device.applyWorldEffects(color, depth, PARAMS.address(), flags) && !logged) {
			logged = true;
			MCMetal.LOGGER.info("Metal shader layer active (AO {} samples)", AO_SAMPLES);
		}
	}

	public static void applyFinal(final RenderTarget target) {
		MetalDevice device = MetalDevice.current();
		GpuTexture color = target.getColorTexture();
		if (!ENABLED || device == null || color == null) {
			return;
		}
		int flags = 0;
		if (BLOOM > 0.0F) {
			flags |= FX_BLOOM;
		}
		if (TONEMAP > 0.0F) {
			flags |= FX_TONEMAP;
		}
		// The parameter block still holds this frame's world-stage values; only the grading fields are read here.
		device.applyFinalEffects(color, PARAMS.address(), flags);
	}

	// Sky light at the camera, eased over about a second: fades sun effects out in caves and back in outside.
	private static void updateSkyExposure(final CameraRenderState camera) {
		Minecraft minecraft = Minecraft.getInstance();
		float target = minecraft.level != null ? minecraft.level.getBrightness(LightLayer.SKY, camera.blockPos) / 15.0F : 1.0F;
		skyExposure += (target - skyExposure) * 0.03F;
	}

	private static void putMatrix(final int offset, final Matrix4f matrix) {
		matrix.get(MATRIX);
		MemorySegment.copy(MATRIX, 0, PARAMS, ValueLayout.JAVA_FLOAT, (long) offset * Float.BYTES, 16);
	}

	private static void putVec(final int offset, final float x, final float y, final float z, final float w) {
		long base = (long) offset * Float.BYTES;
		PARAMS.set(ValueLayout.JAVA_FLOAT, base, x);
		PARAMS.set(ValueLayout.JAVA_FLOAT, base + 4, y);
		PARAMS.set(ValueLayout.JAVA_FLOAT, base + 8, z);
		PARAMS.set(ValueLayout.JAVA_FLOAT, base + 12, w);
	}

	private static float smoothstep(final float edge0, final float edge1, final float x) {
		float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static String string(final String key, final String fallback) {
		String value = Config.get(key);
		return value != null ? value.toLowerCase() : fallback;
	}

	private static boolean bool(final String key, final boolean fallback) {
		String value = Config.get(key);
		return value != null ? Boolean.parseBoolean(value) : fallback;
	}

	private static float number(final String key, final float fallback) {
		String value = Config.get(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Math.max(0.0F, Float.parseFloat(value));
		} catch (NumberFormatException e) {
			MCMetal.LOGGER.warn("Ignoring {}='{}' (not a number)", key, value);
			return fallback;
		}
	}
}
