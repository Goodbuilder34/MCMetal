package com.mcmetal.bench;

import com.mcmetal.MCMetal;
import com.mcmetal.metal.MetalDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.Difficulty;

/**
 * Frame-time benchmark, enabled with {@code -Dmcmetal.benchmark=<seconds>}. Starts once a world is loaded and no
 * screen is open, discards a warm-up period, then records every frame and quits the game.
 */
public final class Benchmark {
	private static final int SECONDS = Integer.getInteger("mcmetal.benchmark", 0);
	private static final int WARMUP_SECONDS = Integer.getInteger("mcmetal.benchmark.warmup", 15);
	private static final boolean FULLSCREEN = Boolean.getBoolean("mcmetal.benchmark.fullscreen");
	/** Camera pitch to hold during the run: -90 = straight up at the sky, 90 = straight down. Unset = leave the camera alone. */
	private static final String PITCH = System.getProperty("mcmetal.benchmark.pitch", "");
	/** Camera yaw to hold during the run (0 = south, 90 = west). Unset = leave the camera alone. */
	private static final String YAW = System.getProperty("mcmetal.benchmark.yaw", "");

	private static long[] frames = new long[1 << 16];
	private static long cpuTotal;
	private static long[] gpuStart;
	private static int count;
	private static long lastFrame;
	private static long warmupStart;
	private static int screenFrames;
	private static long measureStart;
	private static boolean done;
	/** Saves screenshots/{name}.png right after measuring, to check the image (e.g. against another backend). */
	private static final String SCREENSHOT = System.getProperty("mcmetal.benchmark.screenshot", "");
	private static int stopCountdown = -1;

	private Benchmark() {
	}

	public static boolean enabled() {
		return SECONDS > 0;
	}

	public static void onFrameEnd(final Minecraft minecraft) {
		if (done) {
			// -Dmcmetal.benchmark.screen=packs|options[:SCREEN]: open a menu, let it draw, then take the screenshot.
			if (screenFrames > 0 && --screenFrames == 0) {
				Screenshot.grab(minecraft.gameDirectory, SCREENSHOT + ".png", minecraft.gameRenderer.mainRenderTarget(), 1, message -> {});
				stopCountdown = 30;
				return;
			}
			// Give the screenshot readback a few frames to complete before quitting.
			if (stopCountdown > 0 && --stopCountdown == 0) {
				minecraft.stop();
			}
			return;
		}
		long now = System.nanoTime();
		if (minecraft.level == null || minecraft.gui.screen() != null) {
			// Anything that interrupts the run (death screen, menu) restarts it, so a pause never counts as a frame.
			if (measureStart != 0L) {
				MCMetal.LOGGER.warn("[bench] interrupted by {}, restarting", minecraft.gui.screen());
			}
			warmupStart = 0L;
			measureStart = 0L;
			count = 0;
			cpuTotal = 0L;
			return;
		}
		if (!PITCH.isEmpty() && minecraft.player != null) {
			minecraft.player.setXRot(Float.parseFloat(PITCH));
		}
		if (!YAW.isEmpty() && minecraft.player != null) {
			minecraft.player.setYRot(Float.parseFloat(YAW));
			minecraft.player.setYHeadRot(Float.parseFloat(YAW));
		}
		if (warmupStart == 0L) {
			if (FULLSCREEN && !minecraft.options.fullscreen().get()) {
				minecraft.options.fullscreen().set(true);
			}
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server != null) {
				// No mobs: the test player can't be killed (or distracted) mid-run.
				server.execute(() -> server.setDifficulty(Difficulty.PEACEFUL, true));
				// -Dmcmetal.benchmark.time=<ticks>: a fixed time of day, for comparable screenshots.
				Long time = Long.getLong("mcmetal.benchmark.time");
				if (time != null) {
					server.execute(() -> server.overworld().dimensionType().defaultClock()
						.ifPresent(clock -> server.clockManager().setTotalTicks(clock, time)));
				}
				// -Dmcmetal.benchmark.commands="cmd1|cmd2": commands run as the player (e.g. to build a test scene).
				String commands = System.getProperty("mcmetal.benchmark.commands", "");
				if (!commands.isEmpty()) {
					server.execute(() -> {
						var player = server.getPlayerList().getPlayers().getFirst();
						for (String command : commands.split("\\|")) {
							server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS), command.trim());
						}
					});
				}
			}
			warmupStart = now;
			MCMetal.LOGGER.info("[bench] world ready, warming up for {}s", WARMUP_SECONDS);
			return;
		}
		if (measureStart == 0L) {
			if (now - warmupStart < WARMUP_SECONDS * 1_000_000_000L) {
				return;
			}
			measureStart = now;
			lastFrame = now;
			MetalDevice metal = MetalDevice.current();
			gpuStart = metal != null ? metal.gpuTime() : null;
			MCMetal.LOGGER.info("[bench] measuring for {}s", SECONDS);
			return;
		}
		if (count == frames.length) {
			frames = Arrays.copyOf(frames, frames.length * 2);
		}
		frames[count++] = now - lastFrame;
		// Time from frame start to the final blit: the CPU cost of simulating + recording, excluding present waits.
		cpuTotal += minecraft.getFrameTimeNs();
		lastFrame = now;
		if (now - measureStart >= SECONDS * 1_000_000_000L) {
			done = true;
			report(minecraft);
			String screen = System.getProperty("mcmetal.benchmark.screen", "");
			if (SCREENSHOT.isEmpty()) {
				minecraft.stop();
			} else if (!screen.isEmpty()) {
				com.mcmetal.shaderpack.ui.ShaderPackScreen packs = new com.mcmetal.shaderpack.ui.ShaderPackScreen(null);
				minecraft.gui.setScreen(packs);
				String selected = com.mcmetal.shaderpack.PackManager.selected();
				if (screen.startsWith("options") && selected != null) {
					com.mcmetal.shaderpack.ui.ShaderOptionsScreen.open(packs, selected);
					if (screen.contains(":")) {
						com.mcmetal.shaderpack.ui.ShaderOptionsScreen.openSub(minecraft.gui.screen(), screen.substring(screen.indexOf(':') + 1));
					}
				}
				screenFrames = 20;
			} else {
				Screenshot.grab(minecraft.gameDirectory, SCREENSHOT + ".png", minecraft.gameRenderer.mainRenderTarget(), 1, message -> {});
				stopCountdown = 30;
			}
		}
	}

	private static void report(final Minecraft minecraft) {
		String dump = System.getProperty("mcmetal.benchmark.dump");
		if (dump != null) {
			StringBuilder out = new StringBuilder(count * 8);
			for (int i = 0; i < count; i++) {
				out.append(frames[i]).append('\n');
			}
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of(dump), out);
			} catch (java.io.IOException e) {
				MCMetal.LOGGER.warn("Couldn't write frame dump {}", dump, e);
			}
		}
		long[] sorted = Arrays.copyOf(frames, count);
		Arrays.sort(sorted);
		long total = 0L;
		for (long frame : sorted) {
			total += frame;
		}
		double avgFps = count / (total / 1e9);
		double median = sorted[count / 2] / 1e6;
		double p99 = sorted[Math.min(count - 1, (int) (count * 0.99))] / 1e6;
		double p999 = sorted[Math.min(count - 1, (int) (count * 0.999))] / 1e6;
		// "1% low" = average FPS over the slowest 1% of frames.
		int slowCount = Math.max(1, count / 100);
		long slowTotal = 0L;
		for (int i = count - slowCount; i < count; i++) {
			slowTotal += sorted[i];
		}
		double onePercentLow = slowCount / (slowTotal / 1e9);
		String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
		double cpuMs = cpuTotal / 1e6 / count;
		String gpu = "n/a";
		MetalDevice metal = MetalDevice.current();
		if (metal != null && gpuStart != null) {
			long[] gpuEnd = metal.gpuTime();
			long buffers = gpuEnd[1] - gpuStart[1];
			if (buffers > 0) {
				double seconds = (lastFrame - measureStart) / 1e9;
				gpu = String.format(
					Locale.ROOT, "%.2f shown_fps=%.1f passes/frame=%.1f merged/frame=%.1f hoisted/frame=%.1f clearmerged/frame=%.1f latency_ms=%.2f limiter_misses/s=%.2f", (gpuEnd[0] - gpuStart[0]) / 1e6 / buffers, (gpuEnd[2] - gpuStart[2]) / seconds,
					(double) (gpuEnd[3] - gpuStart[3]) / buffers, (double) (gpuEnd[4] - gpuStart[4]) / buffers,
					(double) (gpuEnd[5] - gpuStart[5]) / buffers, (double) (gpuEnd[6] - gpuStart[6]) / buffers,
					gpuEnd[8] > gpuStart[8] ? (gpuEnd[7] - gpuStart[7]) / 1e6 / (gpuEnd[8] - gpuStart[8]) : 0.0, (gpuEnd[9] - gpuStart[9]) / seconds
				);
			}
		}
		MCMetal.LOGGER.info(String.format(
			Locale.ROOT,
			"[bench] RESULT res=%dx%d backend=%s frames=%d avg_fps=%.1f median_fps=%.0f median_ms=%.2f p99_ms=%.2f p99.9_ms=%.2f one_percent_low_fps=%.1f max_ms=%.2f cpu_ms=%.2f gpu_ms=%s",
			minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), backend, count, avgFps, 1000.0 / median, median, p99, p999, onePercentLow, sorted[count - 1] / 1e6, cpuMs, gpu
		));
	}
}
