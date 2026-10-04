package com.mcmetal.shaderpack;

import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalRenderPipeline;
import com.mcmetal.metal.MetalTexture;
import com.mcmetal.metal.PackBackend;
import com.mcmetal.metal.PackHooks;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Runs a shaderpack on the Metal backend. While the level and the hand are drawn, the game's passes on its main
 * framebuffer are redirected to the pack's gbuffers (colortex + depth) and its pipelines replaced by the pack's
 * programs; deferred passes run before translucents, composite and final passes after the hand, and the final pass
 * writes the game's framebuffer, on which the rest of the frame (GUI) is drawn as usual.
 */
public final class PackRenderer implements PackHooks {
	private enum Phase { IDLE, WORLD, HAND, SHADOW }

	private enum Mode { WORLD, HAND, SHADOW }

	private record VariantKey(MetalRenderPipeline vanilla, Mode mode) {
	}

	private static final boolean DEBUG = Boolean.getBoolean("mcmetal.pack.verbose");
	/** Debugging: skip deferred and composite passes (with mcmetal.pack.debug, shows the raw gbuffers output). */
	private static final boolean RAW = Boolean.getBoolean("mcmetal.pack.raw");
	private static final Object FAILED = new Object();
	private static final Object PENDING = new Object();

	private final ShaderPack pack;
	private final PackBackend backend;
	private final PackProperties properties;
	private final Uniforms uniforms = new Uniforms();
	private final BuiltinUniforms builtins = new BuiltinUniforms();
	private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
	private final ExecutorService executor;
	private final Map<VariantKey, Object> variants = new ConcurrentHashMap<>();
	private final AtomicInteger compiling = new AtomicInteger();

	private @Nullable String dimension;
	private @Nullable ProgramSet set;
	private @Nullable RenderTargets targets;
	private @Nullable PackPrograms programs;
	private final Map<String, Integer> customSlots = new HashMap<>();
	/** Custom textures from shaders.properties: stage (gbuffers, deferred, composite...) → slot → texture. */
	private final Map<String, Map<Integer, Long>> customTextures = new HashMap<>();
	private final List<PackPrograms.Compiled> prepare = new ArrayList<>();
	private final List<PackPrograms.Compiled> deferred = new ArrayList<>();
	private final List<PackPrograms.Compiled> composite = new ArrayList<>();
	private PackPrograms.@Nullable Compiled finalPass;
	private volatile boolean passesReady;
	private long shadowFallback;

	private Phase phase = Phase.IDLE;
	private @Nullable GpuTexture mainColor;
	private @Nullable GpuTexture mainDepth;
	/**
	 * Reduced render resolution (PackManager.renderScale): the pack renders at the scaled size, its final pass writes
	 * this texture, and MetalFX upscales it into the game's framebuffer, where the GUI is then drawn at full size.
	 */
	private long scaledFinal;
	private int scaledFinalWidth;
	private int scaledFinalHeight;
	private boolean upscaleFailed;
	private boolean depth1Copied;
	/** Whether any program reads depthtex2 (depth before the hand); without one the per-frame copy is skipped. */
	private boolean usesDepth2 = true;
	private float[] fogColor = {0.0F, 0.0F, 0.0F, 1.0F};
	private final int[] stagesScratch = new int[128];
	private final int[] slotsScratch = new int[128];
	private final long[] texturesScratch = new long[128];
	private boolean announced;
	private final ItemIds itemIds;
	// Shadow pass state.
	private boolean shadowsEnabled;
	private boolean shadowRendered;
	private float shadowRenderDistanceMul = 1.0F;

	public PackRenderer(final ShaderPack pack, final PackBackend backend) {
		this.pack = pack;
		this.backend = backend;
		Preprocessor preprocessor = new Preprocessor(pack::raw);
		StandardMacros.defineStandard(preprocessor);
		StandardMacros.defineOptions(preprocessor, pack.options());
		this.properties = PackProperties.parse(pack.raw("/shaders.properties"), preprocessor, "/shaders.properties");
		this.uniforms.setCustom(this.properties.customUniforms(), this.errors);
		// Block IDs for mc_Entity and the pack's AO/face shading settings: chunk meshes are rebuilt to carry them.
		String blocks = pack.raw("/block.properties");
		Preprocessor blockPreprocessor = new Preprocessor(pack::raw);
		StandardMacros.defineStandard(blockPreprocessor);
		StandardMacros.defineOptions(blockPreprocessor, pack.options());
		BlockIds.setActive(BlockIds.parse(blocks != null ? PackProperties.parse(blocks, blockPreprocessor, "/block.properties") : null).withLighting(this.properties));
		String items = pack.raw("/item.properties");
		this.itemIds = ItemIds.parse(items != null ? PackProperties.parse(items, blockPreprocessor, "/item.properties") : null);
		rebuildChunks();
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		this.executor = Executors.newFixedThreadPool(threads, runnable -> {
			Thread thread = new Thread(runnable, "MCMetal shader compiler");
			thread.setDaemon(true);
			return thread;
		});
		this.shadowFallback = backend.createTexture(GpuFormat.D32_FLOAT, 1, 1, 1, "Pack shadow fallback");
		backend.clearDepth(this.shadowFallback, 1.0);
	}

	public ShaderPack pack() {
		return this.pack;
	}

	public List<String> errors() {
		return this.errors;
	}

	public Uniforms uniforms() {
		return this.uniforms;
	}

	private static void rebuildChunks() {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.level != null) {
			minecraft.levelExtractor.allChanged();
		}
	}

	public void close() {
		BlockIds.setActive(null);
		rebuildChunks();
		PackHooks.install(null);
		this.executor.shutdownNow();
		this.unloadDimension();
		this.backend.release(this.shadowFallback);
		this.backend.release(this.scaledFinal);
		this.scaledFinal = 0L;
	}

	// ---------------------------------------------------------------------------------------------
	// Loading per dimension
	// ---------------------------------------------------------------------------------------------

	private void loadDimension(final String dimension) {
		this.unloadDimension();
		this.dimension = dimension;
		toast("Loading shaderpack...", PackManager.displayName(this.pack.name()));
		ProgramSet set = new ProgramSet(this.pack, ProgramSet.folderFor(this.pack, dimension), this.properties);
		set.load();
		this.errors.addAll(set.errors());
		this.set = set;
		java.util.regex.Pattern depth2 = java.util.regex.Pattern.compile("\\bdepthtex2\\b");
		this.usesDepth2 = set.programs().values().stream()
			.anyMatch(p -> depth2.matcher(p.vertex().source()).find() || depth2.matcher(p.fragment().source()).find());
		RenderTargets targets = new RenderTargets(this.backend, set.constants(), this.properties, this.pack);
		targets.hasExplicitClearColor0 = set.constants().containsKey("colortex0ClearColor");
		this.targets = targets;
		this.loadCustomTextures();
		this.configureShadows(set, targets);
		PackPrograms programs = new PackPrograms(this.backend, set, targets, this.customSlots, this.errors);
		this.customTextures.forEach((stage, slots) -> slots.keySet().forEach(slot -> {
			if (slot >= PackPrograms.SLOT_COLORTEX && slot < PackPrograms.SLOT_COLORTEX + RenderTargets.COLORTEX) {
				programs.overrides.add(stage + ":colortex" + (slot - PackPrograms.SLOT_COLORTEX));
			}
		}));
		this.programs = programs;
		this.passesReady = false;

		List<ProgramSet.Program> passes = new ArrayList<>();
		for (String prefix : List.of("prepare", "deferred", "composite")) {
			for (String name : ProgramSet.passNames(prefix)) {
				ProgramSet.Program program = set.get(name);
				if (program != null) {
					passes.add(program);
				}
			}
		}
		String upTo = System.getProperty("mcmetal.pack.upto");
		if (upTo != null) {
			for (int i = 0; i < passes.size(); i++) {
				if (passes.get(i).name().equals(upTo)) {
					passes.subList(i + 1, passes.size()).clear();
					break;
				}
			}
		}
		ProgramSet.Program finalProgram = set.get("final");
		// -Dmcmetal.pack.debug=<buffer>: show one buffer instead of the pack's final pass.
		String debug = System.getProperty("mcmetal.pack.debug");
		if (debug != null) {
			finalProgram = programs.debugFinal(debug);
		}
		ProgramSet.Program effectiveFinal = finalProgram != null ? finalProgram : programs.defaultFinal();
		// Full-screen passes first, then the world's essential gbuffers variants, then everything else: the pool runs
		// tasks in submission order, and the pack switches on once the first two groups are done.
		long start = System.nanoTime();
		List<java.util.concurrent.CompletableFuture<PackPrograms.@Nullable Compiled>> futures = new ArrayList<>();
		for (ProgramSet.Program program : passes) {
			futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> programs.compileFullscreen(program, false, GpuFormat.RGBA8_UNORM), this.executor));
		}
		java.util.concurrent.CompletableFuture<PackPrograms.@Nullable Compiled> finalFuture = java.util.concurrent.CompletableFuture.supplyAsync(
			() -> programs.compileFullscreen(effectiveFinal, true, GpuFormat.RGBA8_UNORM), this.executor);
		List<java.util.concurrent.CompletableFuture<?>> waitFor = new ArrayList<>(futures);
		waitFor.add(finalFuture);
		waitFor.addAll(this.precompileVariants(programs, targets));
		String packName = this.pack.name();
		java.util.concurrent.CompletableFuture.allOf(waitFor.toArray(java.util.concurrent.CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
			List<PackPrograms.Compiled> compiled = new ArrayList<>();
			for (java.util.concurrent.CompletableFuture<PackPrograms.@Nullable Compiled> future : futures) {
				PackPrograms.Compiled c = future.getNow(null);
				if (c != null) {
					compiled.add(c);
				}
			}
			PackPrograms.Compiled fin = finalFuture.getNow(null);
			synchronized (this) {
				if (this.programs != programs) {
					return;
				}
				for (PackPrograms.Compiled c : compiled) {
					if (RAW && !c.name.startsWith("prepare")) {
						continue;
					}
					(c.name.startsWith("prepare") ? this.prepare : c.name.startsWith("deferred") ? this.deferred : this.composite).add(c);
				}
				this.finalPass = fin;
				this.passesReady = fin != null;
			}
			long ms = (System.nanoTime() - start) / 1_000_000;
			MCMetal.LOGGER.info("Shaderpack {} ({}): {} passes compiled, ready in {} ms{}", packName, set.folder(), compiled.size() + 1, ms,
				this.errors.isEmpty() ? "" : ", " + this.errors.size() + " problems");
			toast(fin != null ? "Shaderpack ready" : "Shaderpack failed to load", PackManager.displayName(packName)
				+ (fin != null ? " (" + String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0) + " s)" : ""));
		});
	}

	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId TOAST =
		new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(3000L);

	/** Shows (or replaces) the shaderpack status toast; callable from any thread. */
	static void toast(final String title, final String message) {
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		minecraft.execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST,
			net.minecraft.network.chat.Component.literal(title), net.minecraft.network.chat.Component.literal(message)));
	}

	private void configureShadows(final ProgramSet set, final RenderTargets targets) {
		Map<String, String> c = set.constants();
		this.shadowsEnabled = set.get("shadow") != null && !"false".equals(this.properties.get("shadowTerrain")) && !Boolean.getBoolean("mcmetal.pack.noshadow");
		this.builtins.shadowDistance = constant(c, "shadowDistance", 160.0F);
		this.builtins.shadowIntervalSize = constant(c, "shadowIntervalSize", 2.0F);
		this.builtins.shadowNearPlane = constant(c, "shadowNearPlane", 0.05F);
		this.builtins.shadowFarPlane = constant(c, "shadowFarPlane", 256.0F);
		this.shadowRenderDistanceMul = constant(c, "shadowDistanceRenderMul", 1.0F);
		if (this.shadowsEnabled) {
			targets.createShadow(Math.max(16, Math.min(8192, (int) constant(c, "shadowMapResolution", 1024.0F))));
		}
	}

	private static float constant(final Map<String, String> constants, final String name, final float fallback) {
		String value = constants.get(name);
		if (value == null) {
			return fallback;
		}
		try {
			return Float.parseFloat(value.trim().replaceAll("[fF]$", ""));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void loadCustomTextures() {
		int next = PackPrograms.SLOT_CUSTOM;
		for (Map.Entry<String, String> entry : this.properties.all().entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith("texture.") || key.equals("texture.noise")) {
				continue;
			}
			String[] parts = key.split("\\.");
			if (parts.length != 3) {
				continue;
			}
			String stage = parts[1];
			String name = parts[2];
			String canonical = GlslTransformer.canonicalSampler(name, false);
			String value = entry.getValue().trim().split("\\s+")[0];
			byte[] bytes = this.pack.bytes(Preprocessor.resolve("/shaders.properties", value));
			if (bytes == null || next >= PackPrograms.SLOT_UNBOUND) {
				continue;
			}
			try {
				long texture = PackTextures.load(this.backend, bytes, name);
				// colortexN overrides are bound in place of that buffer for the stage; keep them simple: own slot.
				Integer existing = this.customSlots.get(canonical);
				int slot = canonical.startsWith("colortex") ? PackPrograms.slotOf(canonical, Map.of()) : existing != null ? existing : next++;
				if (!canonical.startsWith("colortex")) {
					this.customSlots.put(canonical, slot);
				}
				this.customTextures.computeIfAbsent(stage, k -> new HashMap<>()).put(slot, texture);
			} catch (Exception e) {
				this.errors.add("texture " + key + ": " + e.getMessage());
			}
		}
	}

	private void unloadDimension() {
		PackPrograms.Compiled[] all;
		synchronized (this) {
			List<PackPrograms.Compiled> list = new ArrayList<>(this.prepare);
			list.addAll(this.deferred);
			list.addAll(this.composite);
			if (this.finalPass != null) {
				list.add(this.finalPass);
			}
			all = list.toArray(new PackPrograms.Compiled[0]);
			this.prepare.clear();
			this.deferred.clear();
			this.composite.clear();
			this.finalPass = null;
			this.programs = null;
			this.passesReady = false;
		}
		for (PackPrograms.Compiled c : all) {
			this.backend.destroyPipeline(c.pipeline);
		}
		for (Object value : this.variants.values()) {
			if (value instanceof PackPrograms.Compiled c) {
				this.backend.destroyPipeline(c.pipeline);
			}
		}
		this.variants.clear();
		if (this.targets != null) {
			this.targets.destroy();
			this.targets = null;
		}
		this.customTextures.values().forEach(m -> m.values().forEach(this.backend::release));
		this.customTextures.clear();
		this.customSlots.clear();
	}

	// ---------------------------------------------------------------------------------------------
	// Frame phases (called from the GameRenderer/LevelRenderer mixins)
	// ---------------------------------------------------------------------------------------------

	/** Start of level rendering. Returns false when the pack isn't ready (the game then renders normally). */
	public boolean beginLevel(final GameRenderState state, final Matrix4f levelProjection, final RenderTarget main, final String dimension) {
		if (!dimension.equals(this.dimension)) {
			this.loadDimension(dimension);
		}
		RenderTargets targets = this.targets;
		if (!this.passesReady || targets == null || main.getColorTexture() == null || main.getDepthTexture() == null) {
			return false;
		}
		if (!this.announced) {
			this.announced = true;
			MCMetal.LOGGER.info("Shaderpack {} active", this.pack.name());
		}
		this.mainColor = main.getColorTexture();
		this.mainDepth = main.getDepthTexture();
		int width = main.width;
		int height = main.height;
		float scale = this.upscaleFailed ? 1.0F : PackManager.renderScale();
		if (scale < 1.0F && this.backend.upscaleSupported()) {
			width = Math.max(1, Math.round(main.width * scale));
			height = Math.max(1, Math.round(main.height * scale));
		}
		targets.resize(width, height);
		this.prepareScaledFinal(width, height, width != main.width || height != main.height);
		float sunPathRotation = 0.0F;
		String rotation = this.set != null ? this.set.constants().get("sunPathRotation") : null;
		if (rotation != null) {
			try {
				sunPathRotation = Float.parseFloat(rotation.trim().replaceAll("[fF]$", ""));
			} catch (NumberFormatException ignored) {
			}
		}
		int far = state.optionsRenderState.renderDistance * 16;
		String halfLife = this.set != null ? this.set.constants().get("centerDepthHalflife") : null;
		if (halfLife != null) {
			try {
				this.builtins.centerDepthHalfLife = Float.parseFloat(halfLife.trim().replaceAll("[fF]$", ""));
			} catch (NumberFormatException ignored) {
			}
		}
		this.builtins.update(this.uniforms, state, levelProjection, width, height, sunPathRotation, far);
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			this.uniforms.set("heldItemId", this.itemIds.idOf(player.getMainHandItem()));
			this.uniforms.set("heldItemId2", this.itemIds.idOf(player.getOffhandItem()));
		}
		Vector4fc fog = state.levelRenderState.cameraRenderState.fogData.color;
		this.fogColor = new float[]{fog.x(), fog.y(), fog.z(), 1.0F};
		targets.beginFrame(this.fogColor);
		if (DEBUG && this.builtins.frame() % 120 == 1) {
			StringBuilder sb = new StringBuilder("[pack] uniforms:");
			for (String name : List.of("worldTime", "sunAngle", "sunPosition", "shadowLightPosition", "upPosition", "eyeBrightnessSmooth", "viewWidth", "far",
				"fogColor", "skyColor", "frameTimeCounter", "rainStrength", "gbufferProjection", "gbufferModelView")) {
				double[] v = this.uniforms.get(name);
				sb.append(' ').append(name).append('=').append(v == null ? "null" : java.util.Arrays.toString(v));
			}
			for (PackProperties.CustomUniform c : this.properties.customUniforms()) {
				double[] v = this.uniforms.get(c.name());
				sb.append(' ').append(c.name()).append('=').append(v == null ? "null" : java.util.Arrays.toString(v));
			}
			MCMetal.LOGGER.info(sb.toString());
		}
		PackHooks.install(this);
		this.mipsFresh = 0;
		for (PackPrograms.Compiled pass : this.prepare) {
			this.runPass(pass, false);
		}
		this.depth1Copied = false;
		this.shadowRendered = false;
		this.phase = Phase.WORLD;
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Shadow pass
	// ---------------------------------------------------------------------------------------------

	/** Whether to render the shadow map this frame. */
	public boolean wantsShadow() {
		return this.phase == Phase.WORLD && this.shadowsEnabled && this.targets != null && this.targets.shadowtex0 != 0L && this.programs != null;
	}

	public boolean shadowEntities() {
		return !"false".equals(this.properties.get("shadowEntities"));
	}

	/** How far from the camera shadow-casting terrain is gathered (blocks). */
	public float shadowRenderDistance() {
		return this.builtins.shadowDistance * Math.max(this.shadowRenderDistanceMul, 0.0F) + 16.0F;
	}

	/** Whether a section (camera-relative box) can cast into the shadow map. */
	public boolean inShadowFrustum(final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
		this.toShadowSpace(minX, minY, minZ, maxX, maxY, maxZ);
		float[] b = this.shadowBox;
		float h = this.builtins.shadowDistance;
		// No near-plane test: anything between the sun and the receivers can cast, and packs usually compress shadow depth
		// (e.g. gl_Position.z *= 0.2) so it is still inside the depth range.
		return b[0] <= h && b[3] >= -h && b[1] <= h && b[4] >= -h && b[2] <= this.builtins.shadowFarPlane;
	}

	// Shadow caster culling (like Iris's advanced shadow culling): a caster only matters if, seen from the light, it
	// covers part of something the camera sees and is not entirely behind it. Receivers are the camera's visible
	// sections; per cell of a grid across the shadow map, the farthest receiver depth from the light is kept.
	private static final float RECEIVER_CELL = 8.0F;
	/** -Dmcmetal.pack.noShadowCulling=true: draw every shadow caster in the shadow frustum (for checking the culling). */
	private static final boolean NO_SHADOW_CULLING = Boolean.getBoolean("mcmetal.pack.noShadowCulling");
	private final float[] shadowBox = new float[6];
	private float[] receiverDepth = new float[0];
	private int receiverCells;

	/** Starts collecting the sections the camera sees this frame (see {@link #addShadowReceiver}). */
	public void beginShadowReceivers() {
		int cells = (int) Math.ceil(this.builtins.shadowDistance * 2.0F / RECEIVER_CELL) + 1;
		if (this.receiverDepth.length != cells * cells) {
			this.receiverDepth = new float[cells * cells];
		}
		this.receiverCells = cells;
		java.util.Arrays.fill(this.receiverDepth, Float.NEGATIVE_INFINITY);
	}

	/** A section the camera sees (camera-relative box): it can receive shadows. */
	public void addShadowReceiver(final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
		this.toShadowSpace(minX, minY, minZ, maxX, maxY, maxZ);
		float[] b = this.shadowBox;
		int n = this.receiverCells;
		int x0 = this.cell(b[0]);
		int x1 = this.cell(b[3]);
		int y0 = this.cell(b[1]);
		int y1 = this.cell(b[4]);
		if (x1 < 0 || y1 < 0 || x0 >= n || y0 >= n) {
			return;
		}
		for (int y = Math.max(y0, 0); y <= Math.min(y1, n - 1); y++) {
			for (int x = Math.max(x0, 0); x <= Math.min(x1, n - 1); x++) {
				int i = y * n + x;
				this.receiverDepth[i] = Math.max(this.receiverDepth[i], b[5]);
			}
		}
	}

	/** Whether a section (camera-relative box) can shadow any receiver: some of it is nearer the light than one below it. */
	public boolean shadowsReceiver(final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
		if (NO_SHADOW_CULLING) {
			return true;
		}
		this.toShadowSpace(minX, minY, minZ, maxX, maxY, maxZ);
		float[] b = this.shadowBox;
		int n = this.receiverCells;
		for (int y = Math.max(this.cell(b[1]), 0); y <= Math.min(this.cell(b[4]), n - 1); y++) {
			for (int x = Math.max(this.cell(b[0]), 0); x <= Math.min(this.cell(b[3]), n - 1); x++) {
				if (this.receiverDepth[y * n + x] > b[2]) {
					return true;
				}
			}
		}
		return false;
	}

	private int cell(final float coordinate) {
		return (int) Math.floor((coordinate + this.builtins.shadowDistance) / RECEIVER_CELL);
	}

	/** Bounds of a camera-relative box in shadow view space: x, y and depth from the light (min in [0-2], max in [3-5]). */
	private void toShadowSpace(final float minX, final float minY, final float minZ, final float maxX, final float maxY, final float maxZ) {
		org.joml.Matrix4f m = this.builtins.shadowModelView;
		float cx = (minX + maxX) * 0.5F;
		float cy = (minY + maxY) * 0.5F;
		float cz = (minZ + maxZ) * 0.5F;
		float ex = (maxX - minX) * 0.5F;
		float ey = (maxY - minY) * 0.5F;
		float ez = (maxZ - minZ) * 0.5F;
		float x = m.m00() * cx + m.m10() * cy + m.m20() * cz + m.m30();
		float y = m.m01() * cx + m.m11() * cy + m.m21() * cz + m.m31();
		float depth = -(m.m02() * cx + m.m12() * cy + m.m22() * cz + m.m32());
		float rx = Math.abs(m.m00()) * ex + Math.abs(m.m10()) * ey + Math.abs(m.m20()) * ez;
		float ry = Math.abs(m.m01()) * ex + Math.abs(m.m11()) * ey + Math.abs(m.m21()) * ez;
		float rz = Math.abs(m.m02()) * ex + Math.abs(m.m12()) * ey + Math.abs(m.m22()) * ez;
		float[] b = this.shadowBox;
		b[0] = x - rx;
		b[1] = y - ry;
		b[2] = depth - rz;
		b[3] = x + rx;
		b[4] = y + ry;
		b[5] = depth + rz;
	}

	/** Shadow phase: passes on the main target go to the shadow maps until {@link #endShadow}. */
	public void beginShadow() {
		RenderTargets targets = this.targets;
		if (targets == null) {
			return;
		}
		for (int i = 0; i < 2; i++) {
			if (targets.shadowClearEnabled[i]) {
				float[] c = targets.shadowClear[i];
				this.backend.clear(targets.shadowcolor[i], c[0], c[1], c[2], c[3]);
			}
		}
		this.phase = Phase.SHADOW;
	}

	/** Between opaque and translucent shadow casters: shadowtex1 keeps the opaque depth. */
	public void shadowOpaqueDone() {
		RenderTargets targets = this.targets;
		if (targets != null) {
			this.backend.copy(targets.shadowtex0, targets.shadowtex1, targets.shadowResolution, targets.shadowResolution);
		}
	}

	public void endShadow() {
		if (this.phase == Phase.SHADOW) {
			this.phase = Phase.WORLD;
			this.shadowRendered = true;
		}
	}

	public boolean isActive() {
		return this.phase != Phase.IDLE;
	}

	/** Opaque geometry is done: copy depth for depthtex1 and run the deferred passes, inside the game's open pass. */
	public void beforeTranslucent() {
		if (this.phase != Phase.WORLD || this.depth1Copied || this.targets == null) {
			return;
		}
		var encoder = this.backend.encoder();
		boolean inPass = encoder.inRenderPass();
		if (inPass) {
			encoder.suspendRenderPass();
		}
		this.copyDepth1();
		Phase saved = this.phase;
		this.phase = Phase.IDLE;
		this.mipsFresh = 0;
		for (PackPrograms.Compiled pass : this.deferred) {
			this.runPass(pass, false);
		}
		this.phase = saved;
		if (inPass) {
			encoder.resumeRenderPass();
		}
	}

	private void copyDepth1() {
		RenderTargets targets = this.targets;
		if (targets != null) {
			this.backend.copy(targets.depth, targets.depth1, targets.width, targets.height);
			this.depth1Copied = true;
		}
	}

	public void endLevel() {
		if (this.phase != Phase.WORLD) {
			return;
		}
		if (!this.depth1Copied) {
			this.copyDepth1();
		}
		this.phase = Phase.IDLE;
	}

	public void beginHand() {
		RenderTargets targets = this.targets;
		if (targets == null || this.mainColor == null || !this.passesReady) {
			return;
		}
		if (this.usesDepth2) {
			this.backend.copy(targets.depth, targets.depth2, targets.width, targets.height);
		}
		this.phase = Phase.HAND;
	}

	/** After the hand: composite and final passes; the final pass writes the game's framebuffer. */
	public void endHand(final GameRenderState state) {
		if (this.phase != Phase.HAND || this.targets == null) {
			this.phase = Phase.IDLE;
			return;
		}
		this.phase = Phase.IDLE;
		this.mipsFresh = 0;
		for (PackPrograms.Compiled pass : this.composite) {
			this.runPass(pass, false);
		}
		if (this.finalPass != null) {
			this.runPass(this.finalPass, true);
		}
		// Buffers flipped an odd number of times: swap so the latest contents are "main" for the next frame.
		RenderTargets targets = this.targets;
		for (int i = 0; i < RenderTargets.COLORTEX; i++) {
			if (targets.read[i] == 1) {
				RenderTargets.Buffer buffer = targets.buffers[i];
				long t = buffer.textures[0];
				buffer.textures[0] = buffer.textures[1];
				buffer.textures[1] = t;
				targets.read[i] = 0;
			}
		}
		this.builtins.endFrame(state.levelRenderState.cameraRenderState);
	}

	// ---------------------------------------------------------------------------------------------
	// Full-screen passes
	// ---------------------------------------------------------------------------------------------

	private void runPass(final PackPrograms.Compiled pass, final boolean finalPass) {
		RenderTargets targets = this.targets;
		if (targets == null || this.mainColor == null) {
			return;
		}
		for (int mip : pass.mipmapped) {
			long texture = targets.current(mip);
			// Consecutive passes (e.g. bloom then exposure) often ask for mipmaps of a buffer neither changed.
			if (!this.mipsAreFresh(texture)) {
				this.backend.generateMipmaps(texture);
				this.markMipsFresh(texture);
			}
		}
		long[] outputs;
		int width;
		int height;
		long scaledFinal = finalPass ? this.scaledFinal : 0L;
		if (finalPass) {
			outputs = new long[]{scaledFinal != 0L ? scaledFinal : PackBackend.handle(this.mainColor)};
			width = targets.width;
			height = targets.height;
		} else {
			outputs = new long[pass.drawBuffers.length];
			for (int i = 0; i < outputs.length; i++) {
				outputs[i] = targets.other(pass.drawBuffers[i]);
			}
			RenderTargets.Buffer first = targets.buffers[pass.drawBuffers.length > 0 ? pass.drawBuffers[0] : 0];
			width = first.width;
			height = first.height;
		}
		this.backend.beginPass(outputs, null, 0L, Double.NaN, width, height, pass.overwrites, pass.name);
		this.backend.setPipeline(pass.pipeline);
		this.bindTextures(pass, false);
		this.writeUniforms(pass);
		this.backend.draw(3);
		this.backend.endPass();
		for (long output : outputs) {
			this.staleMips(output);
		}
		if (!finalPass) {
			for (int b : pass.drawBuffers) {
				targets.flip(b);
			}
		} else if (scaledFinal != 0L && !this.backend.upscale(scaledFinal, PackBackend.handle(this.mainColor))) {
			MCMetal.LOGGER.warn("MetalFX upscaling failed; the shaderpack renders at full resolution from the next frame");
			this.upscaleFailed = true;
		}
	}

	/**
	 * Textures whose mipmaps match their level 0, valid within one run of consecutive full-screen passes (where only
	 * the passes' own outputs change). Reset before each run.
	 */
	private final long[] mipsFreshTextures = new long[RenderTargets.COLORTEX * 2];
	private int mipsFresh;

	private boolean mipsAreFresh(final long texture) {
		for (int i = 0; i < this.mipsFresh; i++) {
			if (this.mipsFreshTextures[i] == texture) {
				return true;
			}
		}
		return false;
	}

	private void markMipsFresh(final long texture) {
		if (this.mipsFresh < this.mipsFreshTextures.length) {
			this.mipsFreshTextures[this.mipsFresh++] = texture;
		}
	}

	private void staleMips(final long texture) {
		for (int i = 0; i < this.mipsFresh; i++) {
			if (this.mipsFreshTextures[i] == texture) {
				this.mipsFreshTextures[i] = this.mipsFreshTextures[--this.mipsFresh];
				return;
			}
		}
	}

	private void prepareScaledFinal(final int width, final int height, final boolean scaled) {
		if (!scaled) {
			if (this.scaledFinal != 0L) {
				this.backend.release(this.scaledFinal);
				this.scaledFinal = 0L;
			}
			return;
		}
		if (this.scaledFinal == 0L || this.scaledFinalWidth != width || this.scaledFinalHeight != height) {
			this.backend.release(this.scaledFinal);
			// The final pass is compiled for RGBA8 (see compileFullscreen).
			this.scaledFinal = this.backend.createTexture(GpuFormat.RGBA8_UNORM, width, height, 1, "Pack final (scaled)");
			this.scaledFinalWidth = width;
			this.scaledFinalHeight = height;
		}
	}

	private void bindTextures(final PackPrograms.Compiled program, final boolean gbuffers) {
		RenderTargets targets = this.targets;
		if (targets == null) {
			return;
		}
		int n = 0;
		for (PackPrograms.TextureUse use : program.textures) {
			if (n >= this.slotsScratch.length - 1) {
				break;
			}
			this.stagesScratch[n] = use.stages();
			this.slotsScratch[n] = use.slot();
			this.texturesScratch[n] = this.resolve(use, gbuffers, program);
			n++;
		}
		this.stagesScratch[n] = 3;
		this.slotsScratch[n] = PackPrograms.SLOT_UNBOUND;
		this.texturesScratch[n] = targets.black;
		n++;
		this.backend.bindTextures(this.stagesScratch, this.slotsScratch, this.texturesScratch, n);
	}

	private long resolve(final PackPrograms.TextureUse use, final boolean gbuffers, final PackPrograms.Compiled program) {
		RenderTargets targets = this.targets;
		String c = use.canonical();
		int slot = use.slot();
		Map<Integer, Long> stageTextures = this.customTextures.get(stageOf(program.name, gbuffers));
		Long custom = stageTextures != null ? stageTextures.get(slot) : null;
		if (custom != null) {
			return custom;
		}
		int colortex = PackPrograms.parseIndex(c, "colortex");
		if (colortex >= 0 && colortex < RenderTargets.COLORTEX) {
			if (gbuffers) {
				for (int b : program.drawBuffers) {
					if (b == colortex) {
						// Being rendered to: read the other copy instead of creating a feedback loop.
						return targets.other(colortex);
					}
				}
			}
			return targets.current(colortex);
		}
		return switch (c) {
			case "depthtex0" -> gbuffers ? targets.depth1 : targets.depth;
			case "depthtex1" -> targets.depth1;
			case "depthtex2" -> targets.depth2;
			case "shadowtex0", "shadow", "watershadow" -> this.shadowRendered && this.phase != Phase.SHADOW ? targets.shadowtex0 : this.shadowFallback;
			case "shadowtex1" -> this.shadowRendered && this.phase != Phase.SHADOW ? targets.shadowtex1 : this.shadowFallback;
			case "shadowcolor", "shadowcolor0" -> this.shadowRendered && this.phase != Phase.SHADOW ? targets.shadowcolor[0] : targets.white;
			case "shadowcolor1" -> this.shadowRendered && this.phase != Phase.SHADOW ? targets.shadowcolor[1] : targets.white;
			case "noisetex" -> targets.noise;
			case "normals" -> targets.normalsDefault;
			case "specular" -> targets.black;
			case "gtexture", "lightmap" -> targets.white;
			default -> targets.black;
		};
	}

	/** The texture.&lt;stage&gt; name a program belongs to. */
	private static String stageOf(final String program, final boolean gbuffers) {
		if (gbuffers) {
			return program.startsWith("shadow") ? "shadow" : "gbuffers";
		}
		if (program.startsWith("deferred")) {
			return "deferred";
		}
		if (program.startsWith("prepare")) {
			return "prepare";
		}
		if (program.startsWith("shadowcomp")) {
			return "shadowcomp";
		}
		return program.startsWith("composite") || program.equals("final") ? "composite" : "debug";
	}

	private void writeUniforms(final PackPrograms.Compiled program) {
		if (program.vertexBlock != null && program.vertexBlock.size() > 0) {
			// MSL rounds the struct up to 16 bytes.
			ByteBuffer buffer = this.backend.scratch((program.vertexBlock.size() + 15) & ~15);
			this.uniforms.write(program.vertexBlock, buffer);
			this.backend.setStageBytes(1, PackCompiler.DEFAULT_BLOCK_SLOT, buffer);
		}
		if (program.fragmentBlock != null && program.fragmentBlock.size() > 0) {
			ByteBuffer buffer = this.backend.scratch((program.fragmentBlock.size() + 15) & ~15);
			this.uniforms.write(program.fragmentBlock, buffer);
			this.backend.setStageBytes(2, PackCompiler.DEFAULT_BLOCK_SLOT, buffer);
		}
	}

	// ---------------------------------------------------------------------------------------------
	// PackHooks: redirection and substitution inside the game's passes
	// ---------------------------------------------------------------------------------------------

	@Override
	public @Nullable Redirect redirect(final @Nullable MetalTexture color, final @Nullable MetalTexture depth) {
		RenderTargets targets = this.targets;
		PackPrograms programs = this.programs;
		if (this.phase == Phase.IDLE || targets == null || programs == null) {
			return null;
		}
		boolean main = color != null && color == this.mainColor;
		boolean depthOnly = color == null && depth != null && depth == this.mainDepth;
		if (!main && !depthOnly) {
			return null;
		}
		if (this.phase == Phase.SHADOW) {
			int[] buffers = programs.shadowAttachments();
			long[] colors = new long[buffers.length];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = targets.shadowcolor[buffers[i]];
			}
			return new Redirect(colors, targets.shadowtex0, targets.shadowResolution, targets.shadowResolution);
		}
		long[] colors;
		if (main) {
			colors = new long[programs.worldBuffers.length];
			for (int i = 0; i < colors.length; i++) {
				colors[i] = targets.current(programs.worldBuffers[i]);
			}
		} else {
			colors = new long[0];
		}
		long packDepth = depth != null ? targets.depth : 0L;
		// The pack's buffers may be smaller than the game's framebuffer (render scale).
		return new Redirect(colors, packDepth, targets.width, targets.height);
	}

	@Override
	public boolean clearColor(final MetalTexture texture, final Vector4fc color) {
		return this.phase != Phase.IDLE && texture == this.mainColor;
	}

	@Override
	public boolean clearDepth(final MetalTexture texture, final double depth) {
		if (this.phase == Phase.WORLD || this.phase == Phase.SHADOW) {
			return texture == this.mainDepth;
		}
		// The hand keeps the world's depth (its own depth is compressed in front of it).
		return this.phase == Phase.HAND;
	}

	@Override
	public @Nullable MetalRenderPipeline substitute(final MetalRenderPipeline vanilla) {
		PackPrograms programs = this.programs;
		RenderTargets targets = this.targets;
		if (programs == null || targets == null) {
			return null;
		}
		if (vanilla.pack() != null) {
			return vanilla;
		}
		Mode mode = this.phase == Phase.HAND ? Mode.HAND : this.phase == Phase.SHADOW ? Mode.SHADOW : Mode.WORLD;
		Object existing = this.variants.get(new VariantKey(vanilla, mode));
		if (existing instanceof PackPrograms.Compiled compiled) {
			return compiled.wrapped;
		}
		if (existing == null) {
			this.requestVariant(vanilla, mode, programs, targets);
		}
		return null;
	}

	/** Starts compiling the pack's variant of a game pipeline (no-op when already requested). */
	/** Starts compiling the pack's variant of a game pipeline; null when nothing new was started. */
	private java.util.concurrent.@Nullable CompletableFuture<Void> requestVariant(final MetalRenderPipeline vanilla, final Mode mode, final PackPrograms programs,
		final RenderTargets targets) {
		VariantKey key = new VariantKey(vanilla, mode);
		PackPrograms.Mapping mapping = PackPrograms.map(vanilla.name(), mode == Mode.HAND, mode == Mode.SHADOW);
		if (mapping == null || vanilla.createInfo() == null) {
			if (this.variants.putIfAbsent(key, FAILED) == null && DEBUG) {
				MCMetal.LOGGER.info("[pack] no program for {} ({})", vanilla.name(), mode);
			}
			return null;
		}
		if (this.variants.putIfAbsent(key, PENDING) != null) {
			return null;
		}
		int[] attachments = mode == Mode.SHADOW ? programs.shadowAttachments() : programs.attachmentsFor(mapping.program());
		GpuFormat[] formats = new GpuFormat[attachments.length];
		for (int i = 0; i < attachments.length; i++) {
			formats[i] = mode == Mode.SHADOW ? targets.shadowFormats[attachments[i]] : targets.buffers[attachments[i]].format;
		}
		this.compiling.incrementAndGet();
		return java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				int before = this.errors.size();
				PackPrograms.Compiled compiled = programs.compileVariant(vanilla, mapping, mode == Mode.HAND, mode == Mode.SHADOW, attachments, formats);
				if (DEBUG || compiled == null) {
					MCMetal.LOGGER.info("[pack] {} ({}) -> {}: {}", vanilla.name(), mode, mapping.program(), compiled != null ? "ok"
						: this.errors.size() > before ? this.errors.get(this.errors.size() - 1) : "failed");
				}
				if (this.programs == programs) {
					this.variants.put(key, compiled != null ? compiled : FAILED);
				}
			} finally {
				this.compiling.decrementAndGet();
			}
		}, this.executor);
	}

	/** Compiles variants of every game pipeline created so far, so geometry doesn't pop in after a pack loads. */
	/**
	 * Queues the pack's variants of every game pipeline. The ones the world can't be drawn without (terrain, sky,
	 * entities and their shadows) go first, and their futures are returned so the pack waits for them; the rest
	 * (hand, particles, effects...) compile after.
	 */
	private List<java.util.concurrent.CompletableFuture<Void>> precompileVariants(final PackPrograms programs, final RenderTargets targets) {
		List<java.util.concurrent.CompletableFuture<Void>> essential = new ArrayList<>();
		List<MetalRenderPipeline> pipelines = new ArrayList<>();
		for (MetalRenderPipeline vanilla : this.backend.device().pipelines()) {
			if (vanilla.pack() == null && vanilla.createInfo() != null) {
				pipelines.add(vanilla);
			}
		}
		for (MetalRenderPipeline vanilla : pipelines) {
			String name = vanilla.name();
			boolean core = name.contains("terrain") || name.contains("sky") || name.contains("entity");
			if (core) {
				addIfStarted(essential, this.requestVariant(vanilla, Mode.WORLD, programs, targets));
				if (this.shadowsEnabled && (name.contains("terrain") || name.contains("entity"))) {
					addIfStarted(essential, this.requestVariant(vanilla, Mode.SHADOW, programs, targets));
				}
			}
		}
		for (MetalRenderPipeline vanilla : pipelines) {
			String name = vanilla.name();
			this.requestVariant(vanilla, Mode.WORLD, programs, targets);
			if (name.contains("entity") || name.contains("item") || name.contains("glint")) {
				this.requestVariant(vanilla, Mode.HAND, programs, targets);
			}
			if (this.shadowsEnabled && (name.contains("terrain") || name.contains("entity") || name.contains("item"))) {
				this.requestVariant(vanilla, Mode.SHADOW, programs, targets);
			}
		}
		return essential;
	}

	private static void addIfStarted(final List<java.util.concurrent.CompletableFuture<Void>> list, final java.util.concurrent.@Nullable CompletableFuture<Void> future) {
		if (future != null) {
			list.add(future);
		}
	}

	@Override
	public long @Nullable [] attachments(final MetalRenderPipeline pipeline) {
		PackPrograms programs = this.programs;
		RenderTargets targets = this.targets;
		if (programs == null || targets == null || !programs.perProgramAttachments || this.phase == Phase.SHADOW
			|| !(pipeline.pack() instanceof PackPrograms.Compiled compiled)) {
			return null;
		}
		long[] colors = new long[compiled.drawBuffers.length];
		for (int i = 0; i < colors.length; i++) {
			colors[i] = targets.current(compiled.drawBuffers[i]);
		}
		return colors;
	}

	@Override
	public void bindPackResources(final MetalRenderPipeline pipeline) {
		if (!(pipeline.pack() instanceof PackPrograms.Compiled compiled)) {
			return;
		}
		this.uniforms.setPerDraw("renderStage", compiled.renderStage);
		this.bindTextures(compiled, true);
		this.writeUniforms(compiled);
		this.uniforms.clearPerDraw();
	}

	public boolean isCompiling() {
		return this.compiling.get() > 0 || !this.passesReady;
	}
}
