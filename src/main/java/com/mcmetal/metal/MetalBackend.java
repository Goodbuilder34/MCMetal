package com.mcmetal.metal;

import com.mcmetal.MCMetal;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

public class MetalBackend implements GpuBackend {
	private static final long SDL_WINDOW_METAL = 0x20000000L;

	private @Nullable BackendCreationException libraryLoadFailure;

	@Override
	public String getName() {
		return "Metal";
	}

	public static boolean isSupportedPlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		if (this.libraryLoadFailure != null) {
			throw this.libraryLoadFailure;
		}
		if (!isSupportedPlatform()) {
			this.libraryLoadFailure = new BackendCreationException("Metal backend requires an Apple Silicon Mac", BackendCreationException.Reason.PLATFORM_ERROR);
			throw this.libraryLoadFailure;
		}
		try {
			Native.load();
		} catch (Throwable e) {
			MCMetal.LOGGER.error("Failed to load libmcmetal", e);
			this.libraryLoadFailure = new BackendCreationException("Failed to load libmcmetal: " + e.getMessage(), BackendCreationException.Reason.PLATFORM_ERROR);
			throw this.libraryLoadFailure;
		}
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, SDL_WINDOW_METAL | flags);
	}

	@Override
	public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
		return new FrontendGpuDevice(MetalDevice.create(debugOptions));
	}
}
