package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Turns preprocessed shaderpack GLSL (any version from 1.20 compatibility to 4.x) into Vulkan-flavoured GLSL 4.50
 * that shaderc accepts with relaxed Vulkan rules (loose uniforms are collected into a default uniform block).
 *
 * <p>Works on tokens of the comment-free source: top-level declarations are rewritten (attributes, varyings and
 * fragment outputs get explicit locations), compatibility built-ins ({@code gl_Vertex}, {@code gl_ModelViewMatrix},
 * {@code gl_FragData}, ...) are renamed to {@code _mcm_*} names that the {@link Environment} defines, and the pack's
 * {@code main} is wrapped so the environment can run code before and after it.
 */
public final class GlslTransformer {
	public enum Stage { VERTEX, FRAGMENT }

	/** What the surrounding pipeline provides: definitions for the compatibility built-ins and epilogue code. */
	public interface Environment {
		/** Declarations and {@code #define}s placed before the pack's code (inputs, uniform blocks, built-in macros). */
		String prelude(Stage stage, Set<String> usedIdentifiers);

		/** Statements run after the pack's main (e.g. depth range fix-up, alpha test). */
		String epilogue(Stage stage, Set<String> usedIdentifiers);

		/** Names of vertex attributes the environment provides itself (e.g. mc_Entity); their declarations are dropped. */
		default Set<String> providedAttributes() {
			return Set.of();
		}

		/** Uniforms the environment defines itself (Iris core-profile built-ins like modelViewMatrix); their declarations are dropped. */
		default Set<String> providedUniforms() {
			return Set.of();
		}
	}

	/** One varying with its location slot count. */
	public record Varying(String name, String type, int arraySize, int slots) {
	}

	public record Result(String source, Set<String> usedIdentifiers, List<Integer> outputLocations) {
	}

	/** Old sampler names and what they read (shadow/watershadow depend on each other; see {@link #canonicalSampler}). */
	private static final Map<String, String> SAMPLER_ALIASES = Map.ofEntries(
		Map.entry("texture", "gtexture"), Map.entry("tex", "gtexture"),
		Map.entry("gcolor", "colortex0"), Map.entry("gdepth", "colortex1"), Map.entry("gnormal", "colortex2"), Map.entry("composite", "colortex3"),
		Map.entry("gaux1", "colortex4"), Map.entry("gaux2", "colortex5"), Map.entry("gaux3", "colortex6"), Map.entry("gaux4", "colortex7"),
		Map.entry("gdepthtex", "depthtex0")
	);

	/** The texture a sampler uniform reads: aliases resolved; "shadow" is shadowtex1 when the program also has "watershadow". */
	public static String canonicalSampler(final String name, final boolean hasWaterShadow) {
		if (name.equals("shadow")) {
			return hasWaterShadow ? "shadowtex1" : "shadowtex0";
		}
		if (name.equals("watershadow")) {
			return "shadowtex0";
		}
		return SAMPLER_ALIASES.getOrDefault(name, name);
	}

	private static final Map<String, String> BUILTINS = Map.ofEntries(
		Map.entry("gl_Vertex", "_mcm_Vertex"), Map.entry("gl_Normal", "_mcm_Normal"),
		Map.entry("gl_SecondaryColor", "_mcm_SecondaryColor"), Map.entry("gl_FogCoord", "_mcm_FogCoord"),
		Map.entry("gl_MultiTexCoord0", "_mcm_MultiTexCoord0"), Map.entry("gl_MultiTexCoord1", "_mcm_MultiTexCoord1"),
		Map.entry("gl_MultiTexCoord2", "_mcm_MultiTexCoord2"), Map.entry("gl_MultiTexCoord3", "_mcm_MultiTexCoord3"),
		Map.entry("gl_MultiTexCoord4", "_mcm_MultiTexCoord4"), Map.entry("gl_MultiTexCoord5", "_mcm_MultiTexCoord5"),
		Map.entry("gl_MultiTexCoord6", "_mcm_MultiTexCoord6"), Map.entry("gl_MultiTexCoord7", "_mcm_MultiTexCoord7"),
		Map.entry("gl_ModelViewMatrix", "_mcm_ModelViewMatrix"), Map.entry("gl_ProjectionMatrix", "_mcm_ProjectionMatrix"),
		Map.entry("gl_ModelViewProjectionMatrix", "_mcm_ModelViewProjectionMatrix"), Map.entry("gl_NormalMatrix", "_mcm_NormalMatrix"),
		Map.entry("gl_ModelViewMatrixInverse", "_mcm_ModelViewMatrixInverse"), Map.entry("gl_ProjectionMatrixInverse", "_mcm_ProjectionMatrixInverse"),
		Map.entry("gl_ModelViewProjectionMatrixInverse", "_mcm_ModelViewProjectionMatrixInverse"),
		Map.entry("gl_ModelViewMatrixTranspose", "_mcm_ModelViewMatrixTranspose"), Map.entry("gl_ProjectionMatrixTranspose", "_mcm_ProjectionMatrixTranspose"),
		Map.entry("gl_TextureMatrix", "_mcm_TextureMatrix"), Map.entry("gl_Fog", "_mcm_Fog"),
		Map.entry("gl_FogFragCoord", "_mcm_FogFragCoord"), Map.entry("gl_TexCoord", "_mcm_TexCoord"),
		Map.entry("gl_FrontColor", "_mcm_FrontColor"), Map.entry("gl_BackColor", "_mcm_BackColor"),
		Map.entry("gl_FrontSecondaryColor", "_mcm_FrontSecondaryColor"), Map.entry("gl_BackSecondaryColor", "_mcm_BackSecondaryColor"),
		Map.entry("gl_ClipVertex", "_mcm_ClipVertex"), Map.entry("gl_VertexID", "gl_VertexIndex"), Map.entry("gl_InstanceID", "gl_InstanceIndex"),
		Map.entry("gl_LightSource", "_mcm_LightSource"), Map.entry("gl_LightModel", "_mcm_LightModel"), Map.entry("gl_FrontMaterial", "_mcm_FrontMaterial")
	);

	/** Words that became keywords after GLSL 1.20 and appear as plain identifiers in old packs. */
	private static final Set<String> NEW_KEYWORDS = Set.of(
		"sample", "buffer", "shared", "patch", "subroutine", "precise", "coherent", "volatile", "restrict", "readonly", "writeonly",
		"filter", "input", "output", "common", "partition", "active", "resource", "superp", "half", "fixed", "unsigned", "texture",
		"smooth", "noperspective", "centroid", "invariant", "layout", "isampler2D", "usampler2D", "atomic_uint"
	);

	/** C++/MSL words that are fine GLSL identifiers but break the generated Metal source. */
	private static final Set<String> CPP_RESERVED = Set.of(
		"new", "delete", "class", "template", "this", "namespace", "using", "operator", "private", "public", "protected", "virtual", "friend",
		"typename", "explicit", "mutable", "catch", "throw", "try", "auto", "constexpr", "nullptr", "decltype", "alignas", "alignof", "char",
		"short", "long", "signed", "register", "static_cast", "dynamic_cast", "reinterpret_cast", "const_cast", "typeid", "union", "enum",
		"kernel", "vertex", "fragment", "device", "constant", "thread", "threadgroup", "and", "or", "xor", "bitand", "bitor", "compl",
		"and_eq", "or_eq", "xor_eq", "not_eq", "export", "extern", "goto", "wchar_t", "bool2", "float2", "float3", "float4", "half2", "half3",
		"half4", "int2", "int3", "int4", "uint2", "uint3", "uint4", "float2x2", "float3x3", "float4x4", "assert", "metal", "std", "size_t",
		"ptrdiff_t", "NAN", "INFINITY", "M_PI", "M_PI_F", "fabs", "rsqrt", "saturate", "select", "mad"
	);

	private static final Set<String> QUALIFIERS = Set.of(
		"flat", "smooth", "noperspective", "centroid", "invariant", "highp", "mediump", "lowp", "precise", "sample", "patch", "const"
	);

	/** Textures/compat functions that GLSL 4.50 core no longer has. */
	private static final String COMPAT_MACROS = """
		#define texture2D texture
		#define texture3D texture
		#define textureCube texture
		#define texture1D texture
		#define texture2DArray texture
		#define texture2DRect texture
		#define texture2DLod textureLod
		#define texture3DLod textureLod
		#define textureCubeLod textureLod
		#define texture2DLodEXT textureLod
		#define texture2DLodARB textureLod
		#define texture2DGrad textureGrad
		#define texture2DGradARB textureGrad
		#define texture2DGradEXT textureGrad
		#define texture3DGrad textureGrad
		#define texture2DProj textureProj
		#define texture2DProjLod textureProjLod
		#define texelFetch2D texelFetch
		#define texelFetch3D texelFetch
		#define shadow2D(s, c) vec4(texture(s, c))
		#define shadow2DLod(s, c, l) vec4(textureLod(s, c, l))
		#define shadow2DProj(s, c) vec4(textureProj(s, c))
		#define textureSize2D textureSize
		""";

	private final Stage stage;
	private final Environment environment;
	private final int version;

	public GlslTransformer(final Stage stage, final Environment environment, final int version) {
		this.stage = stage;
		this.environment = environment;
		this.version = version;
	}

	// ---------------------------------------------------------------------------------------------
	// Lexing
	// ---------------------------------------------------------------------------------------------

	enum Kind { IDENT, NUMBER, PUNCT, SPACE }

	static final class Tok {
		final Kind kind;
		String text;

		Tok(final Kind kind, final String text) {
			this.kind = kind;
			this.text = text;
		}

		boolean is(final String s) {
			return this.text.equals(s);
		}

		@Override
		public String toString() {
			return this.text;
		}
	}

	static List<Tok> lex(final String source) {
		List<Tok> tokens = new ArrayList<>();
		String s = stripAllComments(source.replace("\r", ""));
		int n = s.length();
		int i = 0;
		while (i < n) {
			char c = s.charAt(i);
			int start = i;
			if (Character.isWhitespace(c)) {
				while (i < n && Character.isWhitespace(s.charAt(i))) {
					i++;
				}
				tokens.add(new Tok(Kind.SPACE, s.substring(start, i)));
			} else if (Preprocessor.isIdentStart(c)) {
				while (i < n && Preprocessor.isIdentPart(s.charAt(i))) {
					i++;
				}
				tokens.add(new Tok(Kind.IDENT, s.substring(start, i)));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
				while (i < n && (Preprocessor.isIdentPart(s.charAt(i)) || s.charAt(i) == '.'
					|| ((s.charAt(i) == '+' || s.charAt(i) == '-') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E') && !s.substring(start, i).startsWith("0x")))) {
					i++;
				}
				tokens.add(new Tok(Kind.NUMBER, s.substring(start, i)));
			} else {
				// Multi-character operators matter only for not confusing the declaration scan; keep them whole anyway.
				String[] ops = {"<<=", ">>=", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "^^", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="};
				String op = String.valueOf(c);
				for (String candidate : ops) {
					if (s.startsWith(candidate, i)) {
						op = candidate;
						break;
					}
				}
				tokens.add(new Tok(Kind.PUNCT, op));
				i += op.length();
			}
		}
		return tokens;
	}

	/** Removes // and /* *\/ comments from a whole source, keeping line breaks. */
	static String stripAllComments(final String source) {
		StringBuilder sb = new StringBuilder(source.length());
		int n = source.length();
		int i = 0;
		while (i < n) {
			char c = source.charAt(i);
			if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
				while (i < n && source.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
				int end = source.indexOf("*/", i + 2);
				int stop = end < 0 ? n : end + 2;
				for (int k = i; k < stop; k++) {
					if (source.charAt(k) == '\n') {
						sb.append('\n');
					}
				}
				sb.append(' ');
				i = stop;
			} else {
				sb.append(c);
				i++;
			}
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Top-level statements
	// ---------------------------------------------------------------------------------------------

	/** A top-level statement: a range of tokens. */
	record Statement(int start, int end) {
	}

	static List<Statement> statements(final List<Tok> tokens) {
		List<Statement> statements = new ArrayList<>();
		int depth = 0;
		int paren = 0;
		int start = 0;
		boolean declarationBlock = false;
		for (int i = 0; i < tokens.size(); i++) {
			Tok t = tokens.get(i);
			if (t.kind != Kind.PUNCT) {
				continue;
			}
			switch (t.text) {
				case "(" -> paren++;
				case ")" -> paren--;
				case "{" -> {
					if (depth == 0) {
						// An interface/uniform block ends at ';', a function or struct definition at '}'... except structs
						// ("struct S {...};" or "struct S {...} s;"), which also end at ';'.
						Tok first = firstNonSpace(tokens, start, i);
						declarationBlock = first != null && (first.is("uniform") || first.is("in") || first.is("out") || first.is("buffer")
							|| first.is("layout") || first.is("struct") || first.is("const") || first.is("readonly") || first.is("writeonly")
							|| first.is("coherent") || first.is("restrict") || first.is("shared"));
						if (!declarationBlock) {
							// "TypeName name[] = {...}" style initializers are not valid GLSL; treat any other '{' as a body.
							declarationBlock = false;
						}
					}
					depth++;
				}
				case "}" -> {
					depth--;
					if (depth == 0 && !declarationBlock) {
						statements.add(new Statement(start, i + 1));
						start = i + 1;
					}
				}
				case ";" -> {
					if (depth == 0 && paren == 0) {
						statements.add(new Statement(start, i + 1));
						start = i + 1;
						declarationBlock = false;
					}
				}
				default -> {
				}
			}
		}
		if (start < tokens.size()) {
			statements.add(new Statement(start, tokens.size()));
		}
		return statements;
	}

	static @Nullable Tok firstNonSpace(final List<Tok> tokens, final int from, final int to) {
		for (int i = from; i < to; i++) {
			if (tokens.get(i).kind != Kind.SPACE) {
				return tokens.get(i);
			}
		}
		return null;
	}

	/** Non-space tokens of a statement. */
	static List<Tok> solid(final List<Tok> tokens, final Statement statement) {
		List<Tok> out = new ArrayList<>();
		for (int i = statement.start(); i < statement.end(); i++) {
			if (tokens.get(i).kind != Kind.SPACE) {
				out.add(tokens.get(i));
			}
		}
		return out;
	}

	/** A parsed global variable declaration: qualifiers, type and declarators. */
	record Declaration(String storage, List<String> qualifiers, @Nullable String layout, String type, List<Declarator> declarators) {
	}

	record Declarator(String name, @Nullable String array, @Nullable String initializer) {
	}

	/** Parses "[layout(...)] [qualifiers] storage [qualifiers] type name[ [n] ] [= init], ...;", or returns null. */
	static @Nullable Declaration parseDeclaration(final List<Tok> s) {
		int i = 0;
		String layout = null;
		String storage = null;
		List<String> qualifiers = new ArrayList<>();
		while (i < s.size()) {
			Tok t = s.get(i);
			if (t.is("layout") && i + 1 < s.size() && s.get(i + 1).is("(")) {
				int close = i + 1;
				int depth = 0;
				StringBuilder sb = new StringBuilder();
				for (; close < s.size(); close++) {
					if (s.get(close).is("(")) {
						depth++;
					} else if (s.get(close).is(")")) {
						depth--;
						if (depth == 0) {
							break;
						}
					}
					if (close > i + 1) {
						sb.append(s.get(close).text).append(' ');
					}
				}
				layout = sb.toString().trim();
				i = close + 1;
				continue;
			}
			if (t.is("attribute") || t.is("varying") || t.is("in") || t.is("out") || t.is("uniform") || t.is("inout")) {
				if (storage != null) {
					return null;
				}
				storage = t.text;
				i++;
				continue;
			}
			if (QUALIFIERS.contains(t.text)) {
				qualifiers.add(t.text);
				i++;
				continue;
			}
			break;
		}
		if (storage == null || i >= s.size() || s.get(i).kind != Kind.IDENT) {
			return null;
		}
		// Interface blocks ("in Name { ... } inst;") are left alone.
		if (i + 1 < s.size() && s.get(i + 1).is("{")) {
			return null;
		}
		StringBuilder type = new StringBuilder(s.get(i).text);
		i++;
		// Array types like "float[4] name".
		while (i < s.size() && s.get(i).is("[")) {
			while (i < s.size() && !s.get(i).is("]")) {
				type.append(s.get(i).text);
				i++;
			}
			type.append("]");
			i++;
		}
		List<Declarator> declarators = new ArrayList<>();
		while (i < s.size()) {
			if (s.get(i).kind != Kind.IDENT) {
				return null;
			}
			String name = s.get(i).text;
			i++;
			String array = null;
			if (i < s.size() && s.get(i).is("[")) {
				StringBuilder sb = new StringBuilder();
				i++;
				while (i < s.size() && !s.get(i).is("]")) {
					sb.append(s.get(i).text);
					i++;
				}
				i++;
				array = sb.toString();
			}
			String init = null;
			if (i < s.size() && s.get(i).is("=")) {
				StringBuilder sb = new StringBuilder();
				i++;
				int depth = 0;
				while (i < s.size()) {
					Tok t = s.get(i);
					if (t.is("(") || t.is("[")) {
						depth++;
					} else if (t.is(")") || t.is("]")) {
						depth--;
					} else if (depth == 0 && (t.is(",") || t.is(";"))) {
						break;
					}
					sb.append(t.text).append(' ');
					i++;
				}
				init = sb.toString().trim();
			}
			declarators.add(new Declarator(name, array, init));
			if (i < s.size() && s.get(i).is(",")) {
				i++;
				continue;
			}
			break;
		}
		if (i >= s.size() || !s.get(i).is(";") || declarators.isEmpty()) {
			return null;
		}
		return new Declaration(storage, qualifiers, layout, type.toString(), declarators);
	}

	public static int slotsOf(final String type, final @Nullable String array) {
		int base = switch (type) {
			case "mat2", "mat2x2", "mat2x3", "mat2x4", "dmat2" -> 2;
			case "mat3", "mat3x2", "mat3x3", "mat3x4", "dmat3" -> 3;
			case "mat4", "mat4x2", "mat4x3", "mat4x4", "dmat4" -> 4;
			default -> 1;
		};
		if (array != null) {
			try {
				return base * Math.max(1, Integer.parseInt(array.trim()));
			} catch (NumberFormatException e) {
				return base * 8;
			}
		}
		return base;
	}

	// ---------------------------------------------------------------------------------------------
	// Analysis shared by both stages of a program
	// ---------------------------------------------------------------------------------------------

	/**
	 * Collects the varyings a vertex shader writes, keyed by name, so both stages can agree on locations.
	 * Built-in varyings ({@code gl_FrontColor}, {@code gl_TexCoord}, {@code gl_FogFragCoord}) are included when used.
	 */
	public static Map<String, Varying> vertexOutputs(final String preprocessedVertex, final int version) {
		List<Tok> tokens = lex(preprocessedVertex);
		Map<String, Varying> outputs = new TreeMap<>();
		for (Statement statement : statements(tokens)) {
			Declaration d = parseDeclaration(solid(tokens, statement));
			if (d == null || !(d.storage().equals("varying") || d.storage().equals("out"))) {
				continue;
			}
			for (Declarator declarator : d.declarators()) {
				outputs.put(declarator.name(), new Varying(declarator.name(), d.type(), arraySize(declarator.array()), slotsOf(d.type(), declarator.array())));
			}
		}
		Set<String> used = identifiers(tokens);
		if (used.contains("gl_FrontColor")) {
			outputs.put("_mcm_FrontColor", new Varying("_mcm_FrontColor", "vec4", 0, 1));
		}
		if (used.contains("gl_FrontSecondaryColor")) {
			outputs.put("_mcm_FrontSecondaryColor", new Varying("_mcm_FrontSecondaryColor", "vec4", 0, 1));
		}
		if (used.contains("gl_FogFragCoord")) {
			outputs.put("_mcm_FogFragCoord", new Varying("_mcm_FogFragCoord", "float", 0, 1));
		}
		if (used.contains("gl_TexCoord")) {
			int count = texCoordCount(tokens);
			outputs.put("_mcm_TexCoord", new Varying("_mcm_TexCoord", "vec4", count, count));
		}
		return outputs;
	}

	private static int arraySize(final @Nullable String array) {
		if (array == null) {
			return 0;
		}
		try {
			return Integer.parseInt(array.trim());
		} catch (NumberFormatException e) {
			return 8;
		}
	}

	private static int texCoordCount(final List<Tok> tokens) {
		int max = 0;
		for (int i = 0; i < tokens.size(); i++) {
			if (tokens.get(i).is("gl_TexCoord")) {
				int j = next(tokens, i);
				if (j >= 0 && tokens.get(j).is("[")) {
					int k = next(tokens, j);
					if (k >= 0 && tokens.get(k).kind == Kind.NUMBER) {
						max = Math.max(max, Integer.parseInt(tokens.get(k).text) + 1);
						continue;
					}
				}
				return 8;
			}
		}
		return Math.max(max, 1);
	}

	/** Assigns locations to varyings, in name order. */
	public static Map<String, Integer> assignLocations(final Map<String, Varying> varyings) {
		Map<String, Integer> locations = new HashMap<>();
		int next = 0;
		for (Varying varying : varyings.values()) {
			locations.put(varying.name(), next);
			next += varying.slots();
		}
		return locations;
	}

	static Set<String> identifiers(final List<Tok> tokens) {
		Set<String> set = new HashSet<>();
		for (Tok t : tokens) {
			if (t.kind == Kind.IDENT) {
				set.add(t.text);
			}
		}
		return set;
	}

	private static int next(final List<Tok> tokens, final int from) {
		for (int i = from + 1; i < tokens.size(); i++) {
			if (tokens.get(i).kind != Kind.SPACE) {
				return i;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------------------------------------
	// Transformation
	// ---------------------------------------------------------------------------------------------

	/**
	 * @param varyingLocations locations shared with the other stage (from the vertex shader's outputs)
	 * @param outputRemap      fragment only: attachment index for each output location (gl_FragData index), -1 or past
	 *                         the end to discard that output
	 */
	public Result transform(final String preprocessed, final Map<String, Integer> varyingLocations, final Map<String, Varying> varyings, final int[] outputRemap) {
		List<Tok> tokens = lex(preprocessed);
		Set<String> used = identifiers(tokens);
		boolean oldGlsl = this.version < 130;

		// A sampler named "texture" collides with the texture() function of GLSL 1.30+. Other aliases (gaux1, gcolor...)
		// stay as they are; the runtime binds them to the same texture as their canonical name.
		Map<String, String> renames = new HashMap<>();
		List<Statement> statements = statements(tokens);
		for (Statement statement : statements) {
			Declaration d = parseDeclaration(solid(tokens, statement));
			if (d != null && d.storage().equals("uniform") && d.type().startsWith("sampler")) {
				for (Declarator declarator : d.declarators()) {
					if (declarator.name().equals("texture")) {
						renames.put("texture", "gtexture");
					}
				}
			}
		}

		StringBuilder declarations = new StringBuilder();
		List<Integer> outputLocations = new ArrayList<>();
		Map<String, Integer> explicitOutputs = new LinkedHashMap<>();
		int nextOutput = 0;

		// Rewrite top-level declarations in place (blank out the original statement, emit a replacement).
		for (Statement statement : statements) {
			List<Tok> solid = solid(tokens, statement);
			Declaration d = parseDeclaration(solid);
			if (d == null) {
				continue;
			}
			String replacement = null;
			String storage = d.storage();
			if (storage.equals("uniform")) {
				// Initializers aren't allowed on uniforms in SPIR-V; the value comes from the uniform block anyway.
				boolean hasInit = d.declarators().stream().anyMatch(x -> x.initializer() != null);
				boolean provided = d.declarators().stream().anyMatch(x -> this.environment.providedUniforms().contains(x.name()));
				if (hasInit || provided) {
					StringBuilder sb = new StringBuilder();
					for (Declarator x : d.declarators()) {
						if (this.environment.providedUniforms().contains(x.name())) {
							continue;
						}
						sb.append("uniform ").append(d.type()).append(' ').append(x.name()).append(x.array() != null ? "[" + x.array() + "]" : "").append(";\n");
					}
					replacement = sb.toString();
				}
			} else if (this.stage == Stage.VERTEX && (storage.equals("attribute") || storage.equals("in"))) {
				StringBuilder sb = new StringBuilder();
				for (Declarator x : d.declarators()) {
					// Attributes are supplied by the environment as #defines or globals; drop the declaration.
					if (!this.environment.providedAttributes().contains(x.name())) {
						sb.append(d.type()).append(' ').append(x.name()).append(x.array() != null ? "[" + x.array() + "]" : "").append(";\n");
					}
				}
				replacement = sb.toString();
			} else if ((this.stage == Stage.VERTEX && (storage.equals("varying") || storage.equals("out")))
				|| (this.stage == Stage.FRAGMENT && (storage.equals("varying") || storage.equals("in")))) {
				boolean output = this.stage == Stage.VERTEX;
				StringBuilder sb = new StringBuilder();
				String interpolation = interpolation(d.qualifiers(), d.type());
				for (Declarator x : d.declarators()) {
					Integer location = varyingLocations.get(x.name());
					String array = x.array() != null ? "[" + x.array() + "]" : "";
					if (location == null) {
						// A fragment input the vertex shader never writes: just a zero global.
						sb.append(d.type()).append(' ').append(x.name()).append(array).append(";\n");
						continue;
					}
					Varying varying = varyings.get(x.name());
					String type = varying != null ? varying.type() : d.type();
					sb.append("layout(location = ").append(location).append(") ").append(interpolation)
						.append(output ? "out " : "in ").append(type).append(' ').append(x.name()).append(array).append(";\n");
				}
				replacement = sb.toString();
			} else if (this.stage == Stage.FRAGMENT && storage.equals("out")) {
				StringBuilder sb = new StringBuilder();
				for (Declarator x : d.declarators()) {
					int location = nextOutput;
					if (d.layout() != null) {
						java.util.regex.Matcher m = java.util.regex.Pattern.compile("location\\s*=\\s*(\\d+)").matcher(d.layout());
						if (m.find()) {
							location = Integer.parseInt(m.group(1));
						}
					}
					nextOutput = Math.max(nextOutput, location + 1);
					explicitOutputs.put(x.name(), location);
					int attachment = location < outputRemap.length ? outputRemap[location] : -1;
					if (attachment >= 0) {
						sb.append("layout(location = ").append(attachment).append(") out ").append(d.type()).append(' ').append(x.name()).append(";\n");
						outputLocations.add(location);
					} else {
						sb.append(d.type()).append(' ').append(x.name()).append(";\n");
					}
				}
				replacement = sb.toString();
			}
			if (replacement != null) {
				for (int i = statement.start(); i < statement.end(); i++) {
					Tok t = tokens.get(i);
					if (t.kind != Kind.SPACE) {
						t.text = "";
					}
				}
				tokens.get(statement.start()).text = replacement;
			}
		}

		// gl_FragData / gl_FragColor outputs.
		if (this.stage == Stage.FRAGMENT) {
			Set<Integer> fragData = new HashSet<>();
			for (int i = 0; i < tokens.size(); i++) {
				Tok t = tokens.get(i);
				if (t.is("gl_FragColor")) {
					t.text = "_mcm_FragData0";
					fragData.add(0);
				} else if (t.is("gl_FragData")) {
					int open = next(tokens, i);
					int index = open >= 0 ? next(tokens, open) : -1;
					int close = index >= 0 ? next(tokens, index) : -1;
					if (open >= 0 && tokens.get(open).is("[") && index >= 0 && tokens.get(index).kind == Kind.NUMBER && close >= 0 && tokens.get(close).is("]")) {
						int n = (int) Preprocessor.ExpressionParser.parseNumber(tokens.get(index).text);
						t.text = "_mcm_FragData" + n;
						tokens.get(open).text = "";
						tokens.get(index).text = "";
						tokens.get(close).text = "";
						fragData.add(n);
					} else {
						t.text = "_mcm_FragData";
						fragData.add(-1);
					}
				}
			}
			if (fragData.contains(-1)) {
				// Dynamic indexing: route through an array copied to the outputs afterwards.
				declarations.append("vec4 _mcm_FragData[8];\n");
				for (int n = 0; n < 8; n++) {
					fragData.add(n);
				}
			}
			for (int n : new java.util.TreeSet<>(fragData)) {
				if (n < 0) {
					continue;
				}
				int attachment = n < outputRemap.length ? outputRemap[n] : -1;
				if (attachment >= 0) {
					declarations.append("layout(location = ").append(attachment).append(") out vec4 _mcm_FragData").append(n).append(";\n");
					outputLocations.add(n);
				} else {
					declarations.append("vec4 _mcm_FragData").append(n).append(";\n");
				}
			}
		}

		// Built-in varyings.
		if (this.stage == Stage.VERTEX) {
			for (Varying v : varyings.values()) {
				if (v.name().startsWith("_mcm_")) {
					declarations.append("layout(location = ").append(varyingLocations.get(v.name())).append(") out ").append(v.type()).append(' ').append(v.name())
						.append(v.arraySize() > 0 ? "[" + v.arraySize() + "]" : "").append(";\n");
				}
			}
		} else {
			if (used.contains("gl_Color")) {
				declareFragmentBuiltin(declarations, varyingLocations, varyings, "_mcm_FrontColor", "vec4", "vec4(1.0)");
			}
			if (used.contains("gl_SecondaryColor")) {
				declareFragmentBuiltin(declarations, varyingLocations, varyings, "_mcm_FrontSecondaryColor", "vec4", "vec4(0.0)");
			}
			if (used.contains("gl_FogFragCoord")) {
				declareFragmentBuiltin(declarations, varyingLocations, varyings, "_mcm_FogFragCoord", "float", "0.0");
			}
			if (used.contains("gl_TexCoord")) {
				Varying v = varyings.get("_mcm_TexCoord");
				Integer location = varyingLocations.get("_mcm_TexCoord");
				int count = v != null ? v.arraySize() : 8;
				if (location != null) {
					declarations.append("layout(location = ").append(location).append(") in vec4 _mcm_TexCoord[").append(count).append("];\n");
				} else {
					declarations.append("vec4 _mcm_TexCoord[8];\n");
				}
			}
		}

		// Identifier renames.
		for (int i = 0; i < tokens.size(); i++) {
			Tok t = tokens.get(i);
			if (t.kind != Kind.IDENT) {
				continue;
			}
			String name = t.text;
			if (name.equals("main")) {
				t.text = "_mcm_main";
				continue;
			}
			String alias = renames.get(name);
			if (alias != null) {
				int n = next(tokens, i);
				// "texture" is also the texture function in GLSL 1.30+.
				if (!(name.equals("texture") && n >= 0 && tokens.get(n).is("("))) {
					t.text = alias;
				}
				continue;
			}
			if (this.stage == Stage.FRAGMENT && name.equals("gl_Color")) {
				t.text = "_mcm_FrontColor";
				continue;
			}
			if (this.stage == Stage.FRAGMENT && name.equals("gl_SecondaryColor")) {
				t.text = "_mcm_FrontSecondaryColor";
				continue;
			}
			if (this.stage == Stage.VERTEX && name.equals("gl_Color")) {
				t.text = "_mcm_Color";
				continue;
			}
			String builtin = BUILTINS.get(name);
			if (builtin != null) {
				t.text = builtin;
				continue;
			}
			if ((oldGlsl && NEW_KEYWORDS.contains(name) && !name.equals("texture")) || CPP_RESERVED.contains(name)) {
				t.text = "_mcm_kw_" + name;
			}
		}

		Set<String> usedAfter = identifiers(tokens);
		usedAfter.addAll(used);
		StringBuilder out = new StringBuilder(preprocessed.length() + 4096);
		out.append("#version 450\n");
		out.append(COMPAT_MACROS);
		out.append(this.environment.prelude(this.stage, usedAfter));
		out.append(declarations);
		if (this.stage == Stage.VERTEX && usedAfter.contains("_mcm_FrontColor")) {
			// gl_FrontColor defaults to the vertex color when a pack never writes it.
		}
		out.append("#line 1\n");
		for (Tok t : tokens) {
			out.append(t.text);
		}
		out.append("\n\nvoid main() {\n");
		if (this.stage == Stage.VERTEX && usedAfter.contains("_mcm_FrontColor")) {
			out.append("\t_mcm_FrontColor = vec4(1.0);\n");
		}
		out.append("\t_mcm_main();\n");
		if (this.stage == Stage.FRAGMENT && usedAfter.contains("_mcm_FragData") && declarations.indexOf("vec4 _mcm_FragData[8]") >= 0) {
			for (int n = 0; n < 8; n++) {
				out.append("\t_mcm_FragData").append(n).append(" = _mcm_FragData[").append(n).append("];\n");
			}
		}
		out.append(this.environment.epilogue(this.stage, usedAfter));
		out.append("}\n");
		return new Result(out.toString(), usedAfter, outputLocations);
	}

	private static void declareFragmentBuiltin(final StringBuilder sb, final Map<String, Integer> locations, final Map<String, Varying> varyings, final String name,
		final String type, final String fallback) {
		Integer location = locations.get(name);
		if (location != null && varyings.containsKey(name)) {
			sb.append("layout(location = ").append(location).append(") in ").append(type).append(' ').append(name).append(";\n");
		} else {
			sb.append(type).append(' ').append(name).append(" = ").append(fallback).append(";\n");
		}
	}

	private static String interpolation(final List<String> qualifiers, final String type) {
		StringBuilder sb = new StringBuilder();
		boolean integer = type.startsWith("int") || type.startsWith("uint") || type.startsWith("ivec") || type.startsWith("uvec") || type.equals("bool");
		if (qualifiers.contains("flat") || integer) {
			sb.append("flat ");
		} else if (qualifiers.contains("noperspective")) {
			sb.append("noperspective ");
		} else if (qualifiers.contains("centroid")) {
			sb.append("centroid ");
		}
		return sb.toString();
	}
}
