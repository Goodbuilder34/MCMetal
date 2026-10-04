package com.mcmetal.metal;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.security.MessageDigest;

/**
 * FFM bindings for libmcmetal. Every native handle crosses the boundary as a plain {@code long}
 * (a pointer), so hot calls allocate nothing. Calls that never block are linked as critical.
 */
public final class Native {
	private static final String LIBRARY_RESOURCE = "/natives/macos-arm64/libmcmetal.dylib";
	private static final int ABI_VERSION = 3;

	public static final int PUSH_CONSTANT_BUFFER_INDEX = 16;
	public static final int MAX_UNIFORMS = PUSH_CONSTANT_BUFFER_INDEX;

	public static final int BIND_BUFFER = 1;
	public static final int BIND_TEXTURE_SAMPLER = 2;
	public static final int BIND_TEXTURE = 3;

	public static final int INFO_MAX_BUFFER_LENGTH = 0;
	public static final int INFO_WORKING_SET_SIZE = 1;
	public static final int INFO_GPU_FAMILY = 2;
	public static final int INFO_UNIFIED_MEMORY = 3;
	public static final int INFO_COUNT = 8;

	private static final ValueLayout.OfLong J = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
	private static final ValueLayout.OfFloat F = ValueLayout.JAVA_FLOAT;
	private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;

	private static boolean loaded;


	private Native() {
	}

	private static Binder fastBinder;
	private static Binder blockingBinder;

	public static synchronized void load() throws IOException {
		if (loaded) {
			return;
		}
		Path library = extractLibrary();
		SymbolLookup lookup = SymbolLookup.libraryLookup(library, Arena.global());
		Linker linker = Linker.nativeLinker();
		Binder fast = new Binder(linker, lookup, true);
		Binder blocking = new Binder(linker, lookup, false);

		MethodHandle abiVersion = fast.bind("mcm_abi_version", FunctionDescriptor.of(I));
		int version;
		try {
			version = (int) abiVersion.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
		if (version != ABI_VERSION) {
			throw new IOException("libmcmetal ABI mismatch: expected " + ABI_VERSION + ", got " + version);
		}

		fastBinder = fast;
		blockingBinder = blocking;
		loaded = true;
	}

	/**
	 * The bound native functions, initialized on first use after {@link #load}. They have to be {@code static final}:
	 * HotSpot only constant-folds final method handles, which lets it inline each downcall; through a mutable field
	 * every call goes through the generic LambdaForm path, which showed up as ~14% of the render thread.
	 */
	private static final class H {
		static final MethodHandle release = fastBinder.bind("mcm_release", FunctionDescriptor.ofVoid(J));
		static final MethodHandle setLabel = fastBinder.bind("mcm_set_label", FunctionDescriptor.ofVoid(J, J));
		static final MethodHandle deviceCreate = blockingBinder.bind("mcm_device_create", FunctionDescriptor.of(J, J, I, J));
		static final MethodHandle deviceDestroy = blockingBinder.bind("mcm_device_destroy", FunctionDescriptor.ofVoid(J));
		static final MethodHandle bufferCreate = fastBinder.bind("mcm_buffer_create", FunctionDescriptor.of(J, J, J));
		static final MethodHandle bufferContents = fastBinder.bind("mcm_buffer_contents", FunctionDescriptor.of(J, J));
		static final MethodHandle textureCreate = fastBinder.bind("mcm_texture_create", FunctionDescriptor.of(J, J, I, I, I, I, I, I));
		static final MethodHandle textureViewCreate = fastBinder.bind("mcm_texture_view_create", FunctionDescriptor.of(J, J, I, I, I, I));
		static final MethodHandle textureBufferCreate = fastBinder.bind("mcm_texture_buffer_create", FunctionDescriptor.of(J, J, J, I, I, I));
		static final MethodHandle samplerCreate = fastBinder.bind("mcm_sampler_create", FunctionDescriptor.of(J, J, I, I, I, I, I, I, F));
			// Shader compilation can take milliseconds; keep it off the critical path so GC can proceed.
		static final MethodHandle libraryCreate = blockingBinder.bind("mcm_library_create", FunctionDescriptor.of(J, J, J, J, I));
		static final MethodHandle functionCreate = blockingBinder.bind("mcm_function_create", FunctionDescriptor.of(J, J, J));
		static final MethodHandle pipelineCreate = blockingBinder.bind("mcm_pipeline_create", FunctionDescriptor.of(J, J, J, J, J, I, J, J, I));
		static final MethodHandle pipelineDestroy = fastBinder.bind("mcm_pipeline_destroy", FunctionDescriptor.ofVoid(J));
		static final MethodHandle blitCopyBuffer = fastBinder.bind("mcm_blit_copy_buffer", FunctionDescriptor.ofVoid(J, J, J, J, J, J));
		static final MethodHandle blitBufferToTexture = fastBinder.bind("mcm_blit_buffer_to_texture", FunctionDescriptor.ofVoid(J, J, J, I, I, J, I, I, I, I, I, I));
		static final MethodHandle blitTextureToBuffer = fastBinder.bind("mcm_blit_texture_to_buffer", FunctionDescriptor.ofVoid(J, J, I, I, I, I, I, I, J, J, I, I));
		static final MethodHandle blitTextureToTexture = fastBinder.bind("mcm_blit_texture_to_texture", FunctionDescriptor.ofVoid(J, J, J, I, I, I, I, I, I, I));
		static final MethodHandle clearTexture = fastBinder.bind("mcm_clear_texture", FunctionDescriptor.ofVoid(J, J, I, I, F, F, F, F, D));
		static final MethodHandle clearRegion = fastBinder.bind("mcm_clear_region", FunctionDescriptor.ofVoid(J, J, J, I, I, I, I, I, F, F, F, F, D));
		static final MethodHandle beginPass = fastBinder.bind("mcm_begin_pass", FunctionDescriptor.ofVoid(J, J, J, J, I, J, I, D, I, I, I, I, J));
		static final MethodHandle endPass = fastBinder.bind("mcm_end_pass", FunctionDescriptor.ofVoid(J));
		static final MethodHandle passSetPipeline = fastBinder.bind("mcm_pass_set_pipeline", FunctionDescriptor.ofVoid(J, J));
		static final MethodHandle passBind = fastBinder.bind("mcm_pass_bind", FunctionDescriptor.ofVoid(J, J, I));
		static final MethodHandle passSetBytes = fastBinder.bind("mcm_pass_set_bytes", FunctionDescriptor.ofVoid(J, I, J, I));
		static final MethodHandle passSetVertexBuffer = fastBinder.bind("mcm_pass_set_vertex_buffer", FunctionDescriptor.ofVoid(J, I, J, J));
		static final MethodHandle passSetIndexBuffer = fastBinder.bind("mcm_pass_set_index_buffer", FunctionDescriptor.ofVoid(J, J, I));
		static final MethodHandle passSetScissor = fastBinder.bind("mcm_pass_set_scissor", FunctionDescriptor.ofVoid(J, I, I, I, I));
		static final MethodHandle passDraw = fastBinder.bind("mcm_pass_draw", FunctionDescriptor.ofVoid(J, I, I, I, I));
		static final MethodHandle passDrawIndexed = fastBinder.bind("mcm_pass_draw_indexed", FunctionDescriptor.ofVoid(J, I, I, I, I, I));
		static final MethodHandle passMultiDrawIndexed = fastBinder.bind("mcm_pass_multi_draw_indexed", FunctionDescriptor.ofVoid(J, J, I, I, I));
		static final MethodHandle passMultiDraw = fastBinder.bind("mcm_pass_multi_draw", FunctionDescriptor.ofVoid(J, J, I, I, I));
		static final MethodHandle passDrawIndexedIndirect = fastBinder.bind("mcm_pass_draw_indexed_indirect", FunctionDescriptor.ofVoid(J, J, J, I));
		static final MethodHandle passDrawIndirect = fastBinder.bind("mcm_pass_draw_indirect", FunctionDescriptor.ofVoid(J, J, J, I));
		static final MethodHandle pushDebugGroup = fastBinder.bind("mcm_push_debug_group", FunctionDescriptor.ofVoid(J, J));
		static final MethodHandle popDebugGroup = fastBinder.bind("mcm_pop_debug_group", FunctionDescriptor.ofVoid(J));
		static final MethodHandle submit = fastBinder.bind("mcm_submit", FunctionDescriptor.of(J, J));
		static final MethodHandle await = blockingBinder.bind("mcm_wait", FunctionDescriptor.of(I, J, J, J));
		static final MethodHandle completed = fastBinder.bind("mcm_completed", FunctionDescriptor.of(J, J));
		static final MethodHandle gpuTime = fastBinder.bind("mcm_gpu_time", FunctionDescriptor.ofVoid(J, J));
		static final MethodHandle surfaceCreate = blockingBinder.bind("mcm_surface_create", FunctionDescriptor.of(J, J, J));
		static final MethodHandle surfaceConfigure = blockingBinder.bind("mcm_surface_configure", FunctionDescriptor.ofVoid(J, I, I, I, F));
			// nextDrawable blocks until the compositor hands back a drawable (this is the frame pacing point).
		static final MethodHandle surfaceBlit = blockingBinder.bind("mcm_surface_blit", FunctionDescriptor.of(I, J, J, J, I, I, I));
		static final MethodHandle surfaceDestroy = blockingBinder.bind("mcm_surface_destroy", FunctionDescriptor.ofVoid(J));
		static final MethodHandle surfaceSetLimiter = blockingBinder.bind("mcm_surface_set_limiter", FunctionDescriptor.ofVoid(J, I));
		// Sleeps when the frame limiter is on, so it must not be a critical (GC-blocking) call.
		static final MethodHandle frameBegin = blockingBinder.bind("mcm_frame_begin", FunctionDescriptor.ofVoid(J, J));
	}

	private static Path extractLibrary() throws IOException {
		String override = System.getProperty("mcmetal.library");
		if (override != null) {
			return Path.of(override);
		}
		byte[] bytes;
		try (InputStream in = Native.class.getResourceAsStream(LIBRARY_RESOURCE)) {
			if (in == null) {
				throw new IOException("Missing " + LIBRARY_RESOURCE + " in mod jar");
			}
			bytes = in.readAllBytes();
		}
		// Content-addressed so several game instances can share the file without racing on it.
		String hash;
		try {
			hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		} catch (Exception e) {
			throw new IOException(e);
		}
		Path dir = Path.of(System.getProperty("java.io.tmpdir"), "mcmetal");
		Files.createDirectories(dir);
		Path target = dir.resolve("libmcmetal-" + hash + ".dylib");
		if (!Files.exists(target)) {
			Path temp = Files.createTempFile(dir, "libmcmetal", ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return target;
	}

	private record Binder(Linker linker, SymbolLookup lookup, boolean critical) {
		MethodHandle bind(String name, FunctionDescriptor descriptor) {
			MemorySegment symbol = this.lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("libmcmetal is missing " + name));
			return this.critical
				? this.linker.downcallHandle(symbol, descriptor, Linker.Option.critical(false))
				: this.linker.downcallHandle(symbol, descriptor);
		}
	}

	static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException e) {
			throw e;
		}
		if (t instanceof Error e) {
			throw e;
		}
		throw new IllegalStateException(t);
	}

	/** Allocates a NUL-terminated UTF-8 string in {@code arena} and returns its address (0 for null). */
	static long cString(Arena arena, String value) {
		return value == null ? 0L : arena.allocateFrom(value).address();
	}

	// -----------------------------------------------------------------------------------------

	public static void release(long object) {
		if (object == 0L) {
			return;
		}
		try {
			H.release.invokeExact(object);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void setLabel(long resource, String label) {
		try (Arena arena = Arena.ofConfined()) {
			H.setLabel.invokeExact(resource, cString(arena, label));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public record DeviceProperties(long context, String name, long[] info) {
	}

	public static DeviceProperties deviceCreate() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment name = arena.allocate(256);
			MemorySegment info = arena.allocate(MemoryLayout.sequenceLayout(INFO_COUNT, J));
			long context = (long) H.deviceCreate.invokeExact(name.address(), 256, info.address());
			return new DeviceProperties(context, name.getString(0), info.toArray(J));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void deviceDestroy(long context) {
		try {
			H.deviceDestroy.invokeExact(context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long bufferCreate(long context, long length) {
		try {
			return (long) H.bufferCreate.invokeExact(context, length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long bufferContents(long buffer) {
		try {
			return (long) H.bufferContents.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long textureCreate(long context, int format, int width, int height, int layers, int mips, boolean cubemap) {
		try {
			return (long) H.textureCreate.invokeExact(context, format, width, height, layers, mips, cubemap ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long textureViewCreate(long texture, int format, int baseMip, int mips, boolean cubemap) {
		try {
			return (long) H.textureViewCreate.invokeExact(texture, format, baseMip, mips, cubemap ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long textureBufferCreate(long context, long buffer, int format, int width, int bytesPerRow) {
		try {
			return (long) H.textureBufferCreate.invokeExact(context, buffer, format, width, bytesPerRow);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long samplerCreate(long context, boolean repeatU, boolean repeatV, boolean linearMin, boolean linearMag, int mipMode, int maxAnisotropy, float maxLod) {
		try {
			return (long) H.samplerCreate.invokeExact(context, repeatU ? 1 : 0, repeatV ? 1 : 0, linearMin ? 1 : 0, linearMag ? 1 : 0, mipMode, maxAnisotropy, maxLod);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Returns the library handle, or throws with the Metal compiler's message. */
	public static long libraryCreate(long context, String source) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment error = arena.allocate(4096);
			long library = (long) H.libraryCreate.invokeExact(context, cString(arena, source), error.address(), 4096);
			if (library == 0L) {
				throw new IllegalStateException(error.getString(0));
			}
			return library;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long functionCreate(long library, String name) {
		try (Arena arena = Arena.ofConfined()) {
			return (long) H.functionCreate.invokeExact(library, cString(arena, name));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long pipelineCreate(long context, long vertexFunction, long fragmentFunction, int[] descriptor, String label) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment desc = arena.allocateFrom(I, descriptor);
			MemorySegment error = arena.allocate(4096);
			long pipeline = (long) H.pipelineCreate.invokeExact(
				context, vertexFunction, fragmentFunction, desc.address(), descriptor.length, cString(arena, label), error.address(), 4096
			);
			if (pipeline == 0L) {
				throw new IllegalStateException(error.getString(0));
			}
			return pipeline;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void pipelineDestroy(long pipeline) {
		try {
			H.pipelineDestroy.invokeExact(pipeline);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void blitCopyBuffer(long context, long src, long srcOffset, long dst, long dstOffset, long length) {
		try {
			H.blitCopyBuffer.invokeExact(context, src, srcOffset, dst, dstOffset, length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void blitBufferToTexture(
		long context, long buffer, long offset, int bytesPerRow, int bytesPerImage, long texture, int slice, int mip, int x, int y, int width, int height
	) {
		try {
			H.blitBufferToTexture.invokeExact(context, buffer, offset, bytesPerRow, bytesPerImage, texture, slice, mip, x, y, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void blitTextureToBuffer(
		long context, long texture, int slice, int mip, int x, int y, int width, int height, long buffer, long offset, int bytesPerRow, int bytesPerImage
	) {
		try {
			H.blitTextureToBuffer.invokeExact(context, texture, slice, mip, x, y, width, height, buffer, offset, bytesPerRow, bytesPerImage);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void blitTextureToTexture(long context, long src, long dst, int mip, int srcX, int srcY, int dstX, int dstY, int width, int height) {
		try {
			H.blitTextureToTexture.invokeExact(context, src, dst, mip, srcX, srcY, dstX, dstY, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void clearTexture(long context, long texture, int mip, int slice, float r, float g, float b, float a, double depth) {
		try {
			H.clearTexture.invokeExact(context, texture, mip, slice, r, g, b, a, depth);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void clearRegion(
		long context, long color, long depthTexture, int mip, int x, int y, int width, int height, float r, float g, float b, float a, double depth
	) {
		try {
			H.clearRegion.invokeExact(context, color, depthTexture, mip, x, y, width, height, r, g, b, a, depth);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void beginPass(
		long context, long colorTextures, long clearFlags, long clearColors, int colorCount, long depthTexture, boolean clearDepth, double depth,
		int x, int y, int width, int height, long label
	) {
		try {
			H.beginPass.invokeExact(context, colorTextures, clearFlags, clearColors, colorCount, depthTexture, clearDepth ? 1 : 0, depth, x, y, width, height, label);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void endPass(long context) {
		try {
			H.endPass.invokeExact(context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passSetPipeline(long context, long pipeline) {
		try {
			H.passSetPipeline.invokeExact(context, pipeline);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passBind(long context, long entries, int count) {
		try {
			H.passBind.invokeExact(context, entries, count);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passSetBytes(long context, int index, long data, int length) {
		try {
			H.passSetBytes.invokeExact(context, index, data, length);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passSetVertexBuffer(long context, int slot, long buffer, long offset) {
		try {
			H.passSetVertexBuffer.invokeExact(context, slot, buffer, offset);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passSetIndexBuffer(long context, long buffer, int indexSize) {
		try {
			H.passSetIndexBuffer.invokeExact(context, buffer, indexSize);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passSetScissor(long context, int x, int y, int width, int height) {
		try {
			H.passSetScissor.invokeExact(context, x, y, width, height);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passDraw(long context, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		try {
			H.passDraw.invokeExact(context, vertexCount, instanceCount, firstVertex, firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passDrawIndexed(long context, int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		try {
			H.passDrawIndexed.invokeExact(context, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passMultiDrawIndexed(long context, long params, int drawCount, int instanceCount, int firstInstance) {
		try {
			H.passMultiDrawIndexed.invokeExact(context, params, drawCount, instanceCount, firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passMultiDraw(long context, long params, int drawCount, int instanceCount, int firstInstance) {
		try {
			H.passMultiDraw.invokeExact(context, params, drawCount, instanceCount, firstInstance);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passDrawIndexedIndirect(long context, long buffer, long offset, int drawCount) {
		try {
			H.passDrawIndexedIndirect.invokeExact(context, buffer, offset, drawCount);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void passDrawIndirect(long context, long buffer, long offset, int drawCount) {
		try {
			H.passDrawIndirect.invokeExact(context, buffer, offset, drawCount);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void pushDebugGroup(long context, String label) {
		try (Arena arena = Arena.ofConfined()) {
			H.pushDebugGroup.invokeExact(context, cString(arena, label));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void popDebugGroup(long context) {
		try {
			H.popDebugGroup.invokeExact(context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long submit(long context) {
		try {
			return (long) H.submit.invokeExact(context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static boolean await(long context, long value, long timeoutNs) {
		try {
			return (int) H.await.invokeExact(context, value, timeoutNs) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long completed(long context) {
		try {
			return (long) H.completed.invokeExact(context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/**
	 * Returns {total GPU ns, command buffers, presents shown, passes begun, passes merged, uploads hoisted, clears merged,
	 * summed frame-start-to-screen ns, frames in that sum, limiter misses} since startup.
	 */
	public static long[] gpuTime(long context) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(J, 10);
			H.gpuTime.invokeExact(context, out.address());
			return out.toArray(J);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void surfaceSetLimiter(long surface, boolean enabled) {
		try {
			H.surfaceSetLimiter.invokeExact(surface, enabled ? 1 : 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void frameBegin(long surface, long context) {
		try {
			H.frameBegin.invokeExact(surface, context);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long surfaceCreate(long context, long metalLayer) {
		try {
			return (long) H.surfaceCreate.invokeExact(context, metalLayer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void surfaceConfigure(long surface, int width, int height, boolean vsync, float refreshHz) {
		try {
			H.surfaceConfigure.invokeExact(surface, width, height, vsync ? 1 : 0, refreshHz);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static boolean surfaceBlit(long surface, long context, long texture, int mip, int width, int height) {
		try {
			return (int) H.surfaceBlit.invokeExact(surface, context, texture, mip, width, height) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void surfaceDestroy(long surface) {
		try {
			H.surfaceDestroy.invokeExact(surface);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
