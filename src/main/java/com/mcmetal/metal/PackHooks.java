package com.mcmetal.metal;

import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Lets the shaderpack runtime take over parts of the game's rendering inside the Metal backend: render passes that
 * target the game's main framebuffer are redirected to the pack's buffers, and pipelines drawn in them are replaced
 * by the pack's programs. Installed with {@link #install}; null means the game renders normally.
 */
public interface PackHooks {
	/** Replacement attachments for a redirected pass. */
	record Redirect(long[] colors, long depth, int width, int height) {
		public Redirect(final long[] colors, final long depth) {
			this(colors, depth, 0, 0);
		}
	}

	/**
	 * Called for every render pass. Returns the attachments to use instead, or null to leave the pass alone.
	 * @param color the first color attachment's texture (or null), @param depth the depth attachment's texture (or null)
	 */
	@Nullable Redirect redirect(@Nullable MetalTexture color, @Nullable MetalTexture depth);

	/** A clear of a game texture; return true when handled (the original clear is then skipped). */
	boolean clearColor(MetalTexture texture, Vector4fc color);

	boolean clearDepth(MetalTexture texture, double depth);

	/** The pack pipeline replacing a game pipeline in a redirected pass, or null to skip its draws. */
	@Nullable MetalRenderPipeline substitute(MetalRenderPipeline vanilla);

	/**
	 * The color attachments a substituted pipeline renders to, when they differ per program (more buffers in use than
	 * a pass can attach); null keeps the pass's attachments.
	 */
	default long @Nullable [] attachments(final MetalRenderPipeline pipeline) {
		return null;
	}

	/** Binds the pack's own resources (textures, uniform blocks) after a substituted pipeline is set. */
	void bindPackResources(MetalRenderPipeline pipeline);

	static @Nullable PackHooks current() {
		return Holder.hooks;
	}

	static void install(final @Nullable PackHooks hooks) {
		Holder.hooks = hooks;
	}

	final class Holder {
		static @Nullable PackHooks hooks;

		private Holder() {
		}
	}
}
