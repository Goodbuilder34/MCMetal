package com.mcmetal.shaderpack.dev;

import com.mcmetal.shaderpack.Environments;
import com.mcmetal.shaderpack.GbuffersEnvironment;
import com.mcmetal.shaderpack.GlslTransformer;
import com.mcmetal.shaderpack.PackCompiler;
import com.mcmetal.shaderpack.PackException;
import com.mcmetal.shaderpack.PackProperties;
import com.mcmetal.shaderpack.Preprocessor;
import com.mcmetal.shaderpack.ProgramSet;
import com.mcmetal.shaderpack.ShaderPack;
import com.mcmetal.shaderpack.StandardMacros;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Development check: compiles every program of the given packs (all dimensions) to MSL without starting the game.
 * {@code ./gradlew packCompileTest --args="run/shaderpacks/BSL.zip [dumpDir]"}
 */
public final class PackCompileTest {
	static final int[] MAX_BLOCK = {0};

	private PackCompileTest() {
	}

	public static void main(final String[] args) throws Exception {
		Path dump = args.length > 1 ? Path.of(args[1]) : null;
		ShaderPack pack = ShaderPack.load(Path.of(args[0]));
		System.out.println(pack.name() + ": " + pack.options().all().size() + " options");
		Preprocessor propsPre = new Preprocessor(pack::raw);
		StandardMacros.defineStandard(propsPre);
		StandardMacros.defineOptions(propsPre, pack.options());
		PackProperties properties = PackProperties.parse(pack.raw("/shaders.properties"), propsPre, "/shaders.properties");
		System.out.println("  " + properties.all().size() + " properties, " + properties.customUniforms().size() + " custom uniforms");
		if (args.length > 2) {
			ProgramSet set = new ProgramSet(pack, "world0", properties);
			System.out.println(set.preprocess(args[2]).source());
			return;
		}
		int ok = 0;
		int failed = 0;
		for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
			ProgramSet set = new ProgramSet(pack, ProgramSet.folderFor(pack, dimension), properties);
			set.load();
			for (String error : set.errors()) {
				System.out.println("  PREPROCESS " + set.folder() + "/" + error);
				failed++;
			}
			for (ProgramSet.Program program : set.programs().values()) {
				try {
					compile(program, set, dump);
					ok++;
				} catch (PackException e) {
					failed++;
					String message = e.getMessage();
					System.out.println("  FAIL " + set.folder() + "/" + program.name() + ": " + (message.length() > 1500 ? message.substring(0, 1500) : message));
				}
			}
		}
		System.out.println("  compiled " + ok + ", failed " + failed + ", largest uniform block " + MAX_BLOCK[0] + " bytes");
	}

	static void compile(final ProgramSet.Program program, final ProgramSet set, final Path dump) throws Exception {
		boolean fullscreen = program.name().startsWith("composite") || program.name().startsWith("deferred") || program.name().startsWith("prepare")
			|| program.name().equals("final");
		GlslTransformer.Environment env;
		Map<String, Integer> uniforms = Map.of("Fog", 0, "Globals", 1, "Projection", 2, "Sampler2", 3, "TerrainUniform", 4, "ChunkSection", 5, "Sampler0", 6);
		if (fullscreen) {
			env = Environments.COMPOSITE;
		} else {
			Map<String, GbuffersEnvironment.Attribute> attributes = new LinkedHashMap<>();
			attributes.put("Position", new GbuffersEnvironment.Attribute("Position", 0, "vec3"));
			attributes.put("Color", new GbuffersEnvironment.Attribute("Color", 1, "vec4"));
			attributes.put("UV0", new GbuffersEnvironment.Attribute("UV0", 2, "vec2"));
			attributes.put("UV2", new GbuffersEnvironment.Attribute("UV2", 3, "ivec2"));
			env = new GbuffersEnvironment(new GbuffersEnvironment.VanillaLayout(attributes, uniforms), "_mcm_alpha > 0.1", Set.of());
		}
		Preprocessor.Result vs = program.vertex();
		Preprocessor.Result fs = program.fragment();
		Map<String, GlslTransformer.Varying> varyings = GlslTransformer.vertexOutputs(vs.source(), vs.version());
		Map<String, Integer> locations = GlslTransformer.assignLocations(varyings);
		List<Integer> drawBuffers = program.drawBuffers();
		int outputs = drawBuffers != null ? drawBuffers.size() : 8;
		int[] remap = new int[outputs];
		for (int i = 0; i < outputs; i++) {
			remap[i] = i;
		}
		GlslTransformer.Result tvs = new GlslTransformer(GlslTransformer.Stage.VERTEX, env, vs.version()).transform(vs.source(), locations, varyings, new int[0]);
		GlslTransformer.Result tfs = new GlslTransformer(GlslTransformer.Stage.FRAGMENT, env, fs.version()).transform(fs.source(), locations, varyings, remap);
		Map<String, Integer> textures = new HashMap<>();
		PackCompiler.BindingPlan plan = new PackCompiler.BindingPlan() {
			@Override
			public int texture(final String name) {
				return textures.computeIfAbsent(name, n -> 16 + textures.size());
			}

			@Override
			public int sampler(final String name, final boolean depthCompare) {
				return depthCompare ? 14 : 10;
			}

			@Override
			public PackCompiler.SamplerSpec samplerState(final String name, final boolean depthCompare) {
				if (name.equals("gtexture") || name.equals("lightmap")) {
					return null;
				}
				return depthCompare ? PackCompiler.SamplerSpec.COMPARE_LINEAR : PackCompiler.SamplerSpec.LINEAR_CLAMP;
			}

			@Override
			public int block(final String name) {
				Integer index = uniforms.get(name.substring("_mcmV_".length()));
				return index != null ? index : -1;
			}
		};
		String base = set.folder() + "_" + program.name();
		if (dump != null) {
			Files.createDirectories(dump);
			Files.writeString(dump.resolve(base + ".vert.glsl"), tvs.source());
			Files.writeString(dump.resolve(base + ".frag.glsl"), tfs.source());
		}
		PackCompiler.Stage v = PackCompiler.compile(base + ".vsh", tvs.source(), PackCompiler.ShaderStage.VERTEX, plan);
		PackCompiler.Stage f = PackCompiler.compile(base + ".fsh", tfs.source(), PackCompiler.ShaderStage.FRAGMENT, plan);
		int size = Math.max(v.defaults() != null ? v.defaults().size() : 0, f.defaults() != null ? f.defaults().size() : 0);
		MAX_BLOCK[0] = Math.max(MAX_BLOCK[0], size);
		if (dump != null) {
			Files.writeString(dump.resolve(base + ".vert.metal"), v.msl());
			Files.writeString(dump.resolve(base + ".frag.metal"), f.msl());
		}
	}
}
