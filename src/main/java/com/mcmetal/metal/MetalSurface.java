package com.mcmetal.metal;

import com.mcmetal.Config;
import com.mcmetal.MCMetal;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

/**
 * The window's CAMetalLayer. The drawable is acquired lazily inside {@link #blitFromTexture} rather than at the start of
 * the frame, so the game never holds one of the three drawables while it is still simulating and recording.
 */
public class MetalSurface implements GpuSurfaceBackend {
	private static final Set<GpuSurface.PresentMode> PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);

	private static volatile @Nullable MetalSurface current;

	private final MetalDevice device;
	private final long windowHandle;
	private final long view;
	private final long surface;
	private int width;
	private int height;

	MetalSurface(final MetalDevice device, final long windowHandle) {
		this.device = device;
		this.windowHandle = windowHandle;
		this.view = SDLMetal.SDL_Metal_CreateView(windowHandle);
		if (this.view == 0L) {
			throw new IllegalStateException("Failed to create Metal view: " + SDLError.SDL_GetError());
		}
		long layer = SDLMetal.SDL_Metal_GetLayer(this.view);
		this.surface = Native.surfaceCreate(device.context(), layer);
		current = this;
	}

	/**
	 * Start of a frame, called before the game reads input. With the frame limiter on this waits until the latest
	 * moment the frame can still make the next display refresh; otherwise it only timestamps the frame.
	 */
	public static void beginFrame() {
		MetalSurface surface = current;
		MetalDevice device = MetalDevice.current();
		if (device != null) {
			Native.frameBegin(surface != null ? surface.surface : 0L, device.context());
		}
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		if (config.width() <= 0 || config.height() <= 0) {
			throw new SurfaceException("Invalid surface size " + config.width() + "x" + config.height());
		}
		this.width = config.width();
		this.height = config.height();
		float refreshHz = this.displayRefreshRate();
		Native.surfaceConfigure(this.surface, this.width, this.height, config.presentMode() != GpuSurface.PresentMode.IMMEDIATE, refreshHz);
		Native.surfaceSetLimiter(this.surface, Config.frameLimiter());
		MCMetal.LOGGER.info("Configured Metal surface {}x{} present mode {} ({} Hz display)", this.width, this.height, config.presentMode(), refreshHz);
	}

	/** Refresh rate of the display the window is on, or 0 if SDL doesn't know it. */
	private float displayRefreshRate() {
		int display = SDLVideo.SDL_GetDisplayForWindow(this.windowHandle);
		if (display == 0) {
			return 0.0F;
		}
		SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);
		return mode != null ? mode.refresh_rate() : 0.0F;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		MetalTextureView view = (MetalTextureView) textureView;
		int copyWidth = Math.min(this.width, view.getWidth(0));
		int copyHeight = Math.min(this.height, view.getHeight(0));
		// Returns false if no drawable was available in time; the frame is simply dropped.
		Native.surfaceBlit(this.surface, this.device.context(), view.handle(), 0, copyWidth, copyHeight);
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens at submit.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return PRESENT_MODES;
	}

	@Override
	public void close() {
		if (current == this) {
			current = null;
		}
		Native.surfaceDestroy(this.surface);
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
