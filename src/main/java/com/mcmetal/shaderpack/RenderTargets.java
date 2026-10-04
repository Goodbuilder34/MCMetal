package com.mcmetal.shaderpack;

import com.mcmetal.metal.PackBackend;
import com.mojang.renderpearl.api.GpuFormat;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * The pack's screen-sized buffers. Each colortex has two textures that are flipped as passes read one and write the
 * other (OptiFine's ping-pong buffers); {@code read[i]} says which one currently holds the latest contents.
 * Depth: the live depth buffer (OpenGL convention, also depthtex0) and the depthtex1/depthtex2 copies taken before
 * translucents and before the hand.
 */
public final class RenderTargets {
	public static final int COLORTEX = 16;

	public static final class Buffer {
		public final int index;
		public GpuFormat format = GpuFormat.RGBA8_UNORM;
		public boolean clear = true;
		public float[] clearColor = {0.0F, 0.0F, 0.0F, 0.0F};
		public boolean mipmapped;
		/** size.buffer.colortexN: relative scale (when absolute is false) or absolute size. */
		public float sizeX = 1.0F;
		public float sizeY = 1.0F;
		public boolean absolute;
		public long[] textures = new long[2];
		public int width;
		public int height;
		public boolean used;

		Buffer(final int index) {
			this.index = index;
		}
	}

	private final PackBackend backend;
	public final Buffer[] buffers = new Buffer[COLORTEX];
	/** Which of the two textures of each buffer holds the latest contents. */
	public final int[] read = new int[COLORTEX];
	/** How often each buffer was flipped this frame. */
	public final int[] flips = new int[COLORTEX];
	public long depth;
	public long depth1;
	public long depth2;
	public long noise;
	public long black;
	public long white;
	public long normalsDefault;
	public int width;
	public int height;
	// Shadow maps (created when the pack has a shadow program).
	public int shadowResolution;
	public long shadowtex0;
	public long shadowtex1;
	public final long[] shadowcolor = new long[2];
	public final GpuFormat[] shadowFormats = {GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM};
	public final float[][] shadowClear = {{1.0F, 1.0F, 1.0F, 1.0F}, {1.0F, 1.0F, 1.0F, 1.0F}};
	public final boolean[] shadowClearEnabled = {true, true};

	public RenderTargets(final PackBackend backend, final Map<String, String> constants, final PackProperties properties, final ShaderPack pack) {
		this.backend = backend;
		for (int i = 0; i < COLORTEX; i++) {
			Buffer buffer = new Buffer(i);
			this.buffers[i] = buffer;
			String format = constants.get("colortex" + i + "Format");
			if (format == null && i < 8) {
				format = constants.get(new String[]{"gcolorFormat", "gdepthFormat", "gnormalFormat", "compositeFormat", "gaux1Format", "gaux2Format", "gaux3Format",
					"gaux4Format"}[i]);
			}
			if (format != null) {
				buffer.format = parseFormat(format.trim());
			}
			String clear = constants.get("colortex" + i + "Clear");
			if (clear != null) {
				buffer.clear = Boolean.parseBoolean(clear.trim());
			}
			if (i == 1) {
				buffer.clearColor = new float[]{1.0F, 1.0F, 1.0F, 1.0F};
			}
			String clearColor = constants.get("colortex" + i + "ClearColor");
			if (clearColor != null) {
				buffer.clearColor = parseVec4(clearColor);
			}
			String mip = constants.get("colortex" + i + "MipmapEnabled");
			buffer.mipmapped = mip != null && Boolean.parseBoolean(mip.trim());
			String size = properties.get("size.buffer.colortex" + i);
			if (size != null) {
				String[] parts = size.trim().split("\\s+");
				if (parts.length == 2) {
					buffer.absolute = !parts[0].contains(".") && !parts[1].contains(".");
					buffer.sizeX = Float.parseFloat(parts[0]);
					buffer.sizeY = Float.parseFloat(parts[1]);
				}
			}
		}
		this.black = backend.createTexture(GpuFormat.RGBA8_UNORM, 1, 1, 1, "Pack black");
		this.white = backend.createTexture(GpuFormat.RGBA8_UNORM, 1, 1, 1, "Pack white");
		this.normalsDefault = backend.createTexture(GpuFormat.RGBA8_UNORM, 1, 1, 1, "Pack flat normal");
		backend.clear(this.black, 0.0F, 0.0F, 0.0F, 0.0F);
		backend.clear(this.white, 1.0F, 1.0F, 1.0F, 1.0F);
		backend.clear(this.normalsDefault, 0.5F, 0.5F, 1.0F, 1.0F);
		this.noise = this.loadNoise(constants, properties, pack);
		for (int i = 0; i < 2; i++) {
			String format = constants.get("shadowcolor" + i + "Format");
			if (format == null && i == 0) {
				format = constants.get("shadowcolorFormat");
			}
			if (format != null) {
				this.shadowFormats[i] = parseFormat(format.trim());
			}
			String clear = constants.get("shadowcolor" + i + "Clear");
			if (clear != null) {
				this.shadowClearEnabled[i] = Boolean.parseBoolean(clear.trim());
			}
			String clearColor = constants.get("shadowcolor" + i + "ClearColor");
			if (clearColor != null) {
				this.shadowClear[i] = parseVec4(clearColor);
			}
		}
	}

	/** (Re)creates the shadow maps at a resolution. */
	public void createShadow(final int resolution) {
		if (resolution == this.shadowResolution && this.shadowtex0 != 0L) {
			return;
		}
		this.destroyShadow();
		this.shadowResolution = resolution;
		this.shadowtex0 = this.backend.createTexture(GpuFormat.D32_FLOAT, resolution, resolution, 1, "shadowtex0");
		this.shadowtex1 = this.backend.createTexture(GpuFormat.D32_FLOAT, resolution, resolution, 1, "shadowtex1");
		for (int i = 0; i < 2; i++) {
			this.shadowcolor[i] = this.backend.createTexture(this.shadowFormats[i], resolution, resolution, 1, "shadowcolor" + i);
			float[] c = this.shadowClear[i];
			this.backend.clear(this.shadowcolor[i], c[0], c[1], c[2], c[3]);
		}
		this.backend.clearDepth(this.shadowtex0, 1.0);
		this.backend.clearDepth(this.shadowtex1, 1.0);
	}

	private void destroyShadow() {
		this.backend.release(this.shadowtex0);
		this.backend.release(this.shadowtex1);
		this.backend.release(this.shadowcolor[0]);
		this.backend.release(this.shadowcolor[1]);
		this.shadowtex0 = 0L;
		this.shadowtex1 = 0L;
		this.shadowcolor[0] = 0L;
		this.shadowcolor[1] = 0L;
	}

	private long loadNoise(final Map<String, String> constants, final PackProperties properties, final ShaderPack pack) {
		String path = properties.get("texture.noise");
		if (path != null) {
			byte[] bytes = pack.bytes(Preprocessor.resolve("/shaders.properties", path.trim()));
			if (bytes != null) {
				try {
					return PackTextures.load(this.backend, bytes, "noisetex");
				} catch (Exception e) {
					// Fall back to generated noise below.
				}
			}
		}
		int size = 256;
		String resolution = constants.get("noiseTextureResolution");
		if (resolution != null) {
			try {
				size = Integer.parseInt(resolution.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		ByteBuffer data = MemoryUtil.memAlloc(size * size * 4);
		Random random = new Random(0L);
		for (int i = 0; i < size * size * 4; i++) {
			data.put(i, (byte) random.nextInt(256));
		}
		long texture = this.backend.createTexture(GpuFormat.RGBA8_UNORM, size, size, 1, "noisetex");
		this.backend.upload(texture, data, size, size, 4);
		MemoryUtil.memFree(data);
		return texture;
	}

	/** (Re)creates the screen-sized textures when the size changes. */
	public void resize(final int width, final int height) {
		if (width == this.width && height == this.height && this.depth != 0L) {
			return;
		}
		this.width = width;
		this.height = height;
		for (Buffer buffer : this.buffers) {
			for (int t = 0; t < 2; t++) {
				this.backend.release(buffer.textures[t]);
				buffer.textures[t] = 0L;
			}
			int w = buffer.absolute ? (int) buffer.sizeX : Math.max(1, Math.round(width * buffer.sizeX));
			int h = buffer.absolute ? (int) buffer.sizeY : Math.max(1, Math.round(height * buffer.sizeY));
			buffer.width = w;
			buffer.height = h;
			int mips = buffer.mipmapped ? 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2.0)) : 1;
			for (int t = 0; t < 2; t++) {
				buffer.textures[t] = this.backend.createTexture(buffer.format, w, h, mips, "colortex" + buffer.index + (t == 0 ? "" : " alt"));
				this.backend.clear(buffer.textures[t], buffer.clearColor[0], buffer.clearColor[1], buffer.clearColor[2], buffer.clearColor[3]);
			}
		}
		this.backend.release(this.depth);
		this.backend.release(this.depth1);
		this.backend.release(this.depth2);
		this.depth = this.backend.createTexture(GpuFormat.D32_FLOAT, width, height, 1, "Pack depth (depthtex0)");
		this.depth1 = this.backend.createTexture(GpuFormat.D32_FLOAT, width, height, 1, "depthtex1");
		this.depth2 = this.backend.createTexture(GpuFormat.D32_FLOAT, width, height, 1, "depthtex2");
		java.util.Arrays.fill(this.read, 0);
	}

	public long current(final int index) {
		Buffer buffer = this.buffers[index];
		return buffer.textures[this.read[index]];
	}

	public long other(final int index) {
		Buffer buffer = this.buffers[index];
		return buffer.textures[1 - this.read[index]];
	}

	public void flip(final int index) {
		this.read[index] ^= 1;
		this.flips[index]++;
	}

	/** Frame start: clear buffers marked for clearing (the side that will be read and written by the gbuffers). */
	public void beginFrame(final float[] fogColor) {
		java.util.Arrays.fill(this.flips, 0);
		for (Buffer buffer : this.buffers) {
			if (!buffer.clear || !buffer.used) {
				continue;
			}
			float[] c = buffer.clearColor;
			if (buffer.index == 0 && !this.hasExplicitClearColor0) {
				c = fogColor;
			}
			this.backend.clear(this.current(buffer.index), c[0], c[1], c[2], c[3]);
		}
		this.backend.clearDepth(this.depth, 1.0);
	}

	public boolean hasExplicitClearColor0;

	public void destroy() {
		for (Buffer buffer : this.buffers) {
			this.backend.release(buffer.textures[0]);
			this.backend.release(buffer.textures[1]);
		}
		this.backend.release(this.depth);
		this.backend.release(this.depth1);
		this.backend.release(this.depth2);
		this.backend.release(this.noise);
		this.backend.release(this.black);
		this.backend.release(this.white);
		this.backend.release(this.normalsDefault);
		this.destroyShadow();
	}

	static GpuFormat parseFormat(final String name) {
		return switch (name.toUpperCase()) {
			case "R8" -> GpuFormat.R8_UNORM;
			case "RG8" -> GpuFormat.RG8_UNORM;
			case "RGB8", "RGBA8", "RGBA" -> GpuFormat.RGBA8_UNORM;
			case "R8_SNORM" -> GpuFormat.R8_SNORM;
			case "RG8_SNORM" -> GpuFormat.RG8_SNORM;
			case "RGB8_SNORM", "RGBA8_SNORM" -> GpuFormat.RGBA8_SNORM;
			case "R16" -> GpuFormat.R16_UNORM;
			case "RG16" -> GpuFormat.RG16_UNORM;
			case "RGB16", "RGBA16" -> GpuFormat.RGBA16_UNORM;
			case "R16_SNORM" -> GpuFormat.R16_SNORM;
			case "RG16_SNORM" -> GpuFormat.RG16_SNORM;
			case "RGB16_SNORM", "RGBA16_SNORM" -> GpuFormat.RGBA16_SNORM;
			case "R16F" -> GpuFormat.R16_FLOAT;
			case "RG16F" -> GpuFormat.RG16_FLOAT;
			case "RGB16F", "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
			case "R32F" -> GpuFormat.R32_FLOAT;
			case "RG32F" -> GpuFormat.RG32_FLOAT;
			case "RGB32F", "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
			case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
			case "RGB10_A2", "RGB10A2" -> GpuFormat.RGB10A2_UNORM;
			case "R8I" -> GpuFormat.R8_SINT;
			case "R8UI" -> GpuFormat.R8_UINT;
			case "R16I" -> GpuFormat.R16_SINT;
			case "R16UI" -> GpuFormat.R16_UINT;
			case "R32I" -> GpuFormat.R32_SINT;
			case "R32UI" -> GpuFormat.R32_UINT;
			case "RG32UI" -> GpuFormat.RG32_UINT;
			case "RGBA32UI" -> GpuFormat.RGBA32_UINT;
			case "RGBA8UI" -> GpuFormat.RGBA8_UINT;
			case "RGBA16UI" -> GpuFormat.RGBA16_UINT;
			default -> GpuFormat.RGBA8_UNORM;
		};
	}

	static float[] parseVec4(final String text) {
		String inner = text.replaceAll("[a-zA-Z0-9_]*\\(", "").replace(")", "");
		String[] parts = inner.split(",");
		float[] v = new float[4];
		for (int i = 0; i < 4; i++) {
			String part = parts.length == 1 ? parts[0] : (i < parts.length ? parts[i] : "0");
			try {
				v[i] = Float.parseFloat(part.trim().replaceAll("[fF]$", ""));
			} catch (NumberFormatException e) {
				v[i] = 0.0F;
			}
		}
		return v;
	}

	public @Nullable Buffer buffer(final int index) {
		return index >= 0 && index < COLORTEX ? this.buffers[index] : null;
	}
}
