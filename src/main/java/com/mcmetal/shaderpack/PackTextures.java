package com.mcmetal.shaderpack;

import com.mcmetal.metal.PackBackend;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.GpuFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Loads image files from a pack (noise and custom textures) into Metal textures. */
public final class PackTextures {
	private PackTextures() {
	}

	public static long load(final PackBackend backend, final byte[] png, final String label) throws IOException {
		try (NativeImage image = NativeImage.read(png)) {
			int w = image.getWidth();
			int h = image.getHeight();
			ByteBuffer data = MemoryUtil.memAlloc(w * h * 4);
			try {
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int argb = image.getPixel(x, y);
						int i = (y * w + x) * 4;
						data.put(i, (byte) (argb >> 16));
						data.put(i + 1, (byte) (argb >> 8));
						data.put(i + 2, (byte) argb);
						data.put(i + 3, (byte) (argb >>> 24));
					}
				}
				long texture = backend.createTexture(GpuFormat.RGBA8_UNORM, w, h, 1, label);
				backend.upload(texture, data, w, h, 4);
				return texture;
			} finally {
				MemoryUtil.memFree(data);
			}
		}
	}
}
