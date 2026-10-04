package com.mcmetal.metal;

import com.mcmetal.MCMetal;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class MetalDevice implements GpuDeviceBackend {
	private final long context;
	private final DeviceInfo deviceInfo;
	private final boolean labels;
	private final MetalCommandEncoder commandEncoder;
	private static @Nullable MetalDevice current;

	private MetalDevice(final long context, final DeviceInfo deviceInfo, final boolean labels) {
		this.context = context;
		this.deviceInfo = deviceInfo;
		this.labels = labels;
		this.commandEncoder = new MetalCommandEncoder(this);
		current = this;
	}

	/** The active Metal device, or null when the game is running on a vanilla backend. */
	public static @Nullable MetalDevice current() {
		return current;
	}

	/** See {@link Native#gpuTime}. */
	public long[] gpuTime() {
		return Native.gpuTime(this.context);
	}

	static MetalDevice create(final GpuDebugOptions debugOptions) throws BackendCreationException {
		Native.DeviceProperties properties = Native.deviceCreate();
		if (properties.context() == 0L) {
			throw new BackendCreationException("No Metal device available", BackendCreationException.Reason.PLATFORM_ERROR);
		}
		long[] info = properties.info();
		int family = (int) info[Native.INFO_GPU_FAMILY];
		if (family < 7) {
			Native.deviceDestroy(properties.context());
			throw new BackendCreationException(
				"Metal device " + properties.name() + " is not an Apple GPU family 7+ (M1 or newer)",
				BackendCreationException.Reason.PLATFORM_ERROR,
				List.of("MTLGPUFamilyApple7")
			);
		}

		long maxBuffer = info[Native.INFO_MAX_BUFFER_LENGTH];
		DeviceInfo deviceInfo = new DeviceInfo(
			properties.name(),
			"Apple",
			"Metal (Apple GPU family " + family + ", macOS " + System.getProperty("os.version") + ")",
			true,
			"Metal",
			1.0F,
			new DeviceLimits(16, MetalBuffer.UNIFORM_OFFSET_ALIGNMENT, 16384, maxBuffer, Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
			new DeviceFeatures(true, true, true, false, true, true, true, true),
			Set.of("MTLGPUFamilyApple" + family),
			// Apple GPUs need gl_Position marked invariant for exact depth matches across passes.
			new HintsAndWorkarounds(false, false, true, false),
			DeviceType.INTEGRATED
		);
		boolean labels = debugOptions.useLabels() || Boolean.getBoolean("mcmetal.labels");
		MCMetal.LOGGER.info(
			"Created Metal device {} (family {}, {} MB working set)", properties.name(), family, info[Native.INFO_WORKING_SET_SIZE] >> 20
		);
		return new MetalDevice(properties.context(), deviceInfo, labels);
	}

	long context() {
		return this.context;
	}

	boolean labels() {
		return this.labels;
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
		return new MetalSurface(this, windowHandle);
	}

	@Override
	public MetalCommandEncoder createCommandEncoder() {
		return this.commandEncoder;
	}

	@Override
	public GpuSampler createSampler(
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
		return new MetalSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(
		final @Nullable String label,
		final @GpuTexture.Usage int usage,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return new MetalTexture(this, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		return new MetalTextureView(this, (MetalTexture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
		return new MetalBuffer.Direct(this, label, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		MetalBuffer.Direct buffer = new MetalBuffer.Direct(this, label, usage, data.remaining());
		// A fresh buffer can't be in flight on the GPU, so its initial contents go straight into shared memory.
		MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.contents(), data.remaining());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return this.labels;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
		// Runs on the frontend's compile executor: SPIR-V -> MSL -> MTLRenderPipelineState all happen off the render thread.
		MetalRenderPipeline pipeline = MetalRenderPipeline.compile(this, pipelineCreateInfo);
		return () -> pipeline;
	}

	@Override
	public void close() {
		this.commandEncoder.destroy();
		Native.deviceDestroy(this.context);
		current = null;
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(final int size) {
		return new MetalQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}
}
