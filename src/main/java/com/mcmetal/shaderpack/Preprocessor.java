package com.mcmetal.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A C-style preprocessor for shaderpack GLSL: {@code #include}, object- and function-like macros, and conditional
 * compilation. Comments in active code are kept, because packs put directives in them ({@code /* DRAWBUFFERS:01 *}{@code /}
 * and {@code const int colortex0Format = ...}); comments in inactive branches are dropped with the code.
 *
 * <p>{@code #version} and {@code #extension} lines are reported (see {@link Result}) and removed. {@code #if}
 * expressions are evaluated with doubles, which tolerates the float comparisons some packs use.
 */
public final class Preprocessor {
	public record Macro(@Nullable List<String> params, String body) {
	}

	public record Result(String source, int version, String profile, List<String> extensions) {
	}

	private final Function<String, @Nullable String> files;
	private final Map<String, Macro> macros = new HashMap<>();
	private final StringBuilder out = new StringBuilder();
	private final List<String> extensions = new ArrayList<>();
	private int version = 110;
	private String profile = "";
	private int includeDepth;
	private boolean propertiesMode;

	/** @param files resolves an absolute pack path (like {@code /lib/settings.glsl}) to its contents, or null. */
	public Preprocessor(final Function<String, @Nullable String> files) {
		this.files = files;
	}

	/**
	 * Properties files ({@code shaders.properties} etc.) only get conditional compilation: no macro expansion, and
	 * {@code #} lines that aren't directives are ordinary comments.
	 */
	public Preprocessor propertiesMode() {
		this.propertiesMode = true;
		return this;
	}

	public void define(final String name, final String value) {
		this.macros.put(name, new Macro(null, value));
	}

	public boolean isDefined(final String name) {
		return this.macros.containsKey(name);
	}

	public @Nullable String macroValue(final String name) {
		Macro macro = this.macros.get(name);
		return macro != null ? macro.body() : null;
	}

	public Result process(final String path) {
		String source = this.files.apply(path);
		if (source == null) {
			throw new PackException("Missing file " + path);
		}
		this.processFile(path, source);
		return new Result(this.out.toString(), this.version, this.profile, this.extensions);
	}

	public Result processSource(final String path, final String source) {
		this.processFile(path, source);
		return new Result(this.out.toString(), this.version, this.profile, this.extensions);
	}

	// ---------------------------------------------------------------------------------------------
	// Lines and directives
	// ---------------------------------------------------------------------------------------------

	private static final class Cond {
		final boolean parentActive;
		boolean active;
		boolean taken;
		boolean sawElse;

		Cond(final boolean parentActive, final boolean active) {
			this.parentActive = parentActive;
			this.active = active;
			this.taken = active;
		}
	}

	private void processFile(final String path, final String source) {
		if (++this.includeDepth > 64) {
			throw new PackException("Include depth exceeded at " + path + " (recursive #include?)");
		}
		List<String> lines = joinContinuations(source);
		Deque<Cond> conds = new ArrayDeque<>();
		boolean inBlockComment = false;
		for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
			String line = lines.get(lineNo);
			boolean active = conds.isEmpty() || conds.peek().active;
			if (!inBlockComment) {
				String trimmed = line.stripLeading();
				if (trimmed.startsWith("#")) {
					this.directive(path, lineNo + 1, stripComments(trimmed.substring(1)).trim(), conds, active);
					continue;
				}
			}
			if (!active) {
				// Track block comments even in dead code so a "/*" there can't swallow live lines.
				inBlockComment = scanComments(line, inBlockComment);
				continue;
			}
			boolean wasInComment = inBlockComment;
			inBlockComment = scanComments(line, inBlockComment);
			this.out.append(this.expandLine(line, wasInComment)).append('\n');
		}
		if (!conds.isEmpty()) {
			throw new PackException(path + ": unterminated #if");
		}
		this.includeDepth--;
	}

	private void directive(final String path, final int line, final String text, final Deque<Cond> conds, final boolean active) {
		int space = 0;
		while (space < text.length() && Character.isLetter(text.charAt(space))) {
			space++;
		}
		String name = text.substring(0, space);
		String rest = text.substring(space).trim();
		switch (name) {
			case "ifdef" -> conds.push(new Cond(active, active && this.macros.containsKey(firstWord(rest))));
			case "ifndef" -> conds.push(new Cond(active, active && !this.macros.containsKey(firstWord(rest))));
			case "if" -> conds.push(new Cond(active, active && this.evaluate(path, line, rest)));
			case "elif" -> {
				Cond cond = requireCond(conds, path, line, name);
				if (cond.taken || !cond.parentActive) {
					cond.active = false;
				} else {
					cond.active = this.evaluate(path, line, rest);
					cond.taken = cond.active;
				}
			}
			case "else" -> {
				Cond cond = requireCond(conds, path, line, name);
				if (cond.sawElse) {
					throw new PackException(path + ":" + line + ": #else after #else");
				}
				cond.sawElse = true;
				cond.active = cond.parentActive && !cond.taken;
				cond.taken = true;
			}
			case "endif" -> {
				requireCond(conds, path, line, name);
				conds.pop();
			}
			default -> {
				if (!active) {
					return;
				}
				switch (name) {
					case "define" -> this.defineDirective(path, line, rest);
					case "undef" -> this.macros.remove(firstWord(rest));
					case "include" -> this.include(path, line, rest);
					case "version" -> {
						if (this.propertiesMode) {
							return;
						}
						String[] parts = rest.split("\\s+");
						try {
							this.version = Integer.parseInt(parts[0]);
						} catch (NumberFormatException e) {
							throw new PackException(path + ":" + line + ": bad #version " + rest);
						}
						this.profile = parts.length > 1 ? parts[1] : "";
					}
					case "extension" -> this.extensions.add(rest);
					case "error" -> {
						if (!this.propertiesMode) {
							throw new PackException(path + ":" + line + ": #error " + rest);
						}
					}
					case "line", "pragma", "" -> {
					}
					default -> {
						if (!this.propertiesMode) {
							throw new PackException(path + ":" + line + ": unknown directive #" + name);
						}
					}
				}
			}
		}
	}

	private static Cond requireCond(final Deque<Cond> conds, final String path, final int line, final String name) {
		if (conds.isEmpty()) {
			throw new PackException(path + ":" + line + ": #" + name + " without #if");
		}
		return conds.peek();
	}

	private void include(final String path, final int line, final String rest) {
		String target;
		if (rest.startsWith("\"") && rest.lastIndexOf('"') > 0) {
			target = rest.substring(1, rest.lastIndexOf('"'));
		} else if (rest.startsWith("<") && rest.indexOf('>') > 0) {
			target = rest.substring(1, rest.indexOf('>'));
		} else {
			throw new PackException(path + ":" + line + ": bad #include " + rest);
		}
		String resolved = resolve(path, target);
		String source = this.files.apply(resolved);
		if (source == null) {
			throw new PackException(path + ":" + line + ": #include not found: " + target + " (" + resolved + ")");
		}
		this.processFile(resolved, source);
	}

	/** Resolves an include relative to the including file; a leading slash means the pack's shaders root. */
	public static String resolve(final String from, final String target) {
		String joined;
		if (target.startsWith("/")) {
			joined = target;
		} else {
			int slash = from.lastIndexOf('/');
			joined = (slash >= 0 ? from.substring(0, slash + 1) : "/") + target;
		}
		Deque<String> parts = new ArrayDeque<>();
		for (String part : joined.split("/")) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.equals("..")) {
				if (!parts.isEmpty()) {
					parts.removeLast();
				}
			} else {
				parts.addLast(part);
			}
		}
		return "/" + String.join("/", parts);
	}

	private void defineDirective(final String path, final int line, final String rest) {
		int i = 0;
		while (i < rest.length() && isIdentPart(rest.charAt(i))) {
			i++;
		}
		String name = rest.substring(0, i);
		if (name.isEmpty()) {
			throw new PackException(path + ":" + line + ": bad #define");
		}
		if (i < rest.length() && rest.charAt(i) == '(') {
			int close = rest.indexOf(')', i);
			if (close < 0) {
				throw new PackException(path + ":" + line + ": bad macro parameters");
			}
			List<String> params = new ArrayList<>();
			for (String param : rest.substring(i + 1, close).split(",")) {
				if (!param.isBlank()) {
					params.add(param.trim());
				}
			}
			this.macros.put(name, new Macro(params, rest.substring(close + 1).trim()));
		} else {
			this.macros.put(name, new Macro(null, rest.substring(i).trim()));
		}
	}

	private boolean evaluate(final String path, final int line, final String expression) {
		String expanded = this.expandForIf(expression);
		try {
			return new ExpressionParser(expanded).parseAll() != 0.0;
		} catch (RuntimeException e) {
			throw new PackException(path + ":" + line + ": can't evaluate #if " + expression + " (" + expanded + "): " + e.getMessage());
		}
	}

	/** Replaces {@code defined X} first (so its operand isn't expanded), then expands macros; leftovers become 0. */
	private String expandForIf(final String expression) {
		StringBuilder sb = new StringBuilder();
		List<Token> tokens = tokenize(expression);
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			if (t.ident && t.text.equals("defined")) {
				int j = i + 1;
				while (j < tokens.size() && tokens.get(j).space) {
					j++;
				}
				boolean paren = j < tokens.size() && tokens.get(j).text.equals("(");
				if (paren) {
					j++;
					while (j < tokens.size() && tokens.get(j).space) {
						j++;
					}
				}
				if (j >= tokens.size() || !tokens.get(j).ident) {
					throw new PackException("bad 'defined' in #if " + expression);
				}
				sb.append(this.macros.containsKey(tokens.get(j).text) ? " 1 " : " 0 ");
				if (paren) {
					j++;
					while (j < tokens.size() && tokens.get(j).space) {
						j++;
					}
				}
				i = j;
				continue;
			}
			sb.append(t.text);
		}
		String expanded = this.expand(sb.toString(), new HashSet<>());
		StringBuilder result = new StringBuilder();
		for (Token t : tokenize(expanded)) {
			if (t.ident) {
				result.append(t.text.equals("true") ? " 1 " : " 0 ");
			} else {
				result.append(t.text);
			}
		}
		return result.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Macro expansion
	// ---------------------------------------------------------------------------------------------

	private String expandLine(final String line, final boolean startsInComment) {
		if (this.propertiesMode) {
			return line;
		}
		// Expand only the code parts of the line; comments pass through untouched.
		StringBuilder sb = new StringBuilder();
		StringBuilder code = new StringBuilder();
		boolean inComment = startsInComment;
		int i = 0;
		while (i < line.length()) {
			if (inComment) {
				int end = line.indexOf("*/", i);
				if (end < 0) {
					sb.append(line, i, line.length());
					return sb.toString();
				}
				sb.append(line, i, end + 2);
				i = end + 2;
				inComment = false;
				continue;
			}
			char c = line.charAt(i);
			if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
				sb.append(this.expand(code.toString(), new HashSet<>()));
				code.setLength(0);
				sb.append(line, i, line.length());
				return sb.toString();
			}
			if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
				sb.append(this.expand(code.toString(), new HashSet<>()));
				code.setLength(0);
				inComment = true;
				sb.append("/*");
				i += 2;
				continue;
			}
			code.append(c);
			i++;
		}
		sb.append(this.expand(code.toString(), new HashSet<>()));
		return sb.toString();
	}

	private String expand(final String text, final Set<String> disabled) {
		if (this.macros.isEmpty()) {
			return text;
		}
		List<Token> tokens = tokenize(text);
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < tokens.size(); i++) {
			Token t = tokens.get(i);
			Macro macro = t.ident && !disabled.contains(t.text) ? this.macros.get(t.text) : null;
			if (macro == null) {
				sb.append(t.text);
				continue;
			}
			if (macro.params() == null) {
				disabled.add(t.text);
				sb.append(this.expand(macro.body(), disabled));
				disabled.remove(t.text);
				continue;
			}
			// Function-like: only an invocation if followed by '('.
			int j = i + 1;
			while (j < tokens.size() && tokens.get(j).space) {
				j++;
			}
			if (j >= tokens.size() || !tokens.get(j).text.equals("(")) {
				sb.append(t.text);
				continue;
			}
			List<String> args = new ArrayList<>();
			StringBuilder arg = new StringBuilder();
			int depth = 0;
			int k = j + 1;
			for (; k < tokens.size(); k++) {
				String s = tokens.get(k).text;
				if (s.equals("(")) {
					depth++;
				} else if (s.equals(")")) {
					if (depth == 0) {
						break;
					}
					depth--;
				} else if (s.equals(",") && depth == 0) {
					args.add(arg.toString().trim());
					arg.setLength(0);
					continue;
				}
				arg.append(s);
			}
			if (k >= tokens.size()) {
				// Unbalanced (arguments continue on another line): leave as is.
				sb.append(t.text);
				continue;
			}
			args.add(arg.toString().trim());
			if (macro.params().isEmpty() && args.size() == 1 && args.get(0).isEmpty()) {
				args.clear();
			}
			Map<String, String> bound = new HashMap<>();
			for (int p = 0; p < macro.params().size(); p++) {
				String raw = p < args.size() ? args.get(p) : "";
				bound.put(macro.params().get(p), this.expand(raw, disabled));
			}
			StringBuilder body = new StringBuilder();
			List<Token> bodyTokens = tokenize(macro.body());
			for (int b = 0; b < bodyTokens.size(); b++) {
				Token bt = bodyTokens.get(b);
				if (bt.text.equals("##")) {
					// Token pasting: drop surrounding whitespace.
					while (body.length() > 0 && Character.isWhitespace(body.charAt(body.length() - 1))) {
						body.setLength(body.length() - 1);
					}
					b++;
					while (b < bodyTokens.size() && bodyTokens.get(b).space) {
						b++;
					}
					if (b < bodyTokens.size()) {
						Token next = bodyTokens.get(b);
						String value = next.ident && bound.containsKey(next.text) ? bound.get(next.text) : next.text;
						body.append(value);
					}
					continue;
				}
				body.append(bt.ident && bound.containsKey(bt.text) ? bound.get(bt.text) : bt.text);
			}
			disabled.add(t.text);
			sb.append(this.expand(body.toString(), disabled));
			disabled.remove(t.text);
			i = k;
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// Lexing helpers
	// ---------------------------------------------------------------------------------------------

	record Token(String text, boolean ident, boolean space) {
	}

	static List<Token> tokenize(final String text) {
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		int n = text.length();
		while (i < n) {
			char c = text.charAt(i);
			int start = i;
			if (Character.isWhitespace(c)) {
				while (i < n && Character.isWhitespace(text.charAt(i))) {
					i++;
				}
				tokens.add(new Token(text.substring(start, i), false, true));
			} else if (isIdentStart(c)) {
				while (i < n && isIdentPart(text.charAt(i))) {
					i++;
				}
				tokens.add(new Token(text.substring(start, i), true, false));
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
				while (i < n && (isIdentPart(text.charAt(i)) || text.charAt(i) == '.'
					|| ((text.charAt(i) == '+' || text.charAt(i) == '-') && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E')))) {
					i++;
				}
				tokens.add(new Token(text.substring(start, i), false, false));
			} else if (c == '#' && i + 1 < n && text.charAt(i + 1) == '#') {
				tokens.add(new Token("##", false, false));
				i += 2;
			} else {
				tokens.add(new Token(String.valueOf(c), false, false));
				i++;
			}
		}
		return tokens;
	}

	static boolean isIdentStart(final char c) {
		return Character.isLetter(c) || c == '_';
	}

	static boolean isIdentPart(final char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static String firstWord(final String text) {
		int i = 0;
		while (i < text.length() && isIdentPart(text.charAt(i))) {
			i++;
		}
		return text.substring(0, i);
	}

	private static List<String> joinContinuations(final String source) {
		String[] raw = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		List<String> lines = new ArrayList<>(raw.length);
		StringBuilder pending = null;
		for (String line : raw) {
			if (line.endsWith("\\")) {
				if (pending == null) {
					pending = new StringBuilder();
				}
				pending.append(line, 0, line.length() - 1);
				continue;
			}
			if (pending != null) {
				pending.append(line);
				lines.add(pending.toString());
				pending = null;
			} else {
				lines.add(line);
			}
		}
		if (pending != null) {
			lines.add(pending.toString());
		}
		return lines;
	}

	/** Returns whether a block comment is still open at the end of the line. */
	private static boolean scanComments(final String line, final boolean inComment) {
		boolean in = inComment;
		int i = 0;
		while (i < line.length()) {
			if (in) {
				int end = line.indexOf("*/", i);
				if (end < 0) {
					return true;
				}
				in = false;
				i = end + 2;
			} else {
				int slash = line.indexOf('/', i);
				if (slash < 0 || slash + 1 >= line.length()) {
					return false;
				}
				char next = line.charAt(slash + 1);
				if (next == '/') {
					return false;
				}
				if (next == '*') {
					in = true;
					i = slash + 2;
				} else {
					i = slash + 1;
				}
			}
		}
		return in;
	}

	static String stripComments(final String text) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			if (text.startsWith("//", i)) {
				break;
			}
			if (text.startsWith("/*", i)) {
				int end = text.indexOf("*/", i + 2);
				if (end < 0) {
					break;
				}
				sb.append(' ');
				i = end + 2;
				continue;
			}
			sb.append(text.charAt(i++));
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------------------------------------
	// #if expression evaluation (doubles; comparisons and logic yield 0/1)
	// ---------------------------------------------------------------------------------------------

	static final class ExpressionParser {
		private final String s;
		private int pos;

		ExpressionParser(final String s) {
			this.s = s;
		}

		double parseAll() {
			double value = this.ternary();
			this.skip();
			if (this.pos != this.s.length()) {
				throw new IllegalStateException("unexpected '" + this.s.substring(this.pos) + "'");
			}
			return value;
		}

		private void skip() {
			while (this.pos < this.s.length() && Character.isWhitespace(this.s.charAt(this.pos))) {
				this.pos++;
			}
		}

		private boolean eat(final String op) {
			this.skip();
			if (this.s.startsWith(op, this.pos)) {
				// Don't split "<<" into "<" or "&&" into "&" etc.
				if (op.length() == 1 && this.pos + 1 < this.s.length()) {
					char next = this.s.charAt(this.pos + 1);
					char c = op.charAt(0);
					if ((c == '<' || c == '>') && (next == c || next == '=')) {
						return false;
					}
					if ((c == '&' || c == '|') && next == c) {
						return false;
					}
					if ((c == '=' || c == '!') && next == '=') {
						return false;
					}
				}
				this.pos += op.length();
				return true;
			}
			return false;
		}

		private double ternary() {
			double cond = this.or();
			if (this.eat("?")) {
				double a = this.ternary();
				if (!this.eat(":")) {
					throw new IllegalStateException("expected ':'");
				}
				double b = this.ternary();
				return cond != 0.0 ? a : b;
			}
			return cond;
		}

		private double or() {
			double v = this.and();
			while (this.eat("||")) {
				double r = this.and();
				v = (v != 0.0 || r != 0.0) ? 1.0 : 0.0;
			}
			return v;
		}

		private double and() {
			double v = this.bitOr();
			while (this.eat("&&")) {
				double r = this.bitOr();
				v = (v != 0.0 && r != 0.0) ? 1.0 : 0.0;
			}
			return v;
		}

		private double bitOr() {
			double v = this.bitXor();
			while (this.eat("|")) {
				v = (long) v | (long) this.bitXor();
			}
			return v;
		}

		private double bitXor() {
			double v = this.bitAnd();
			while (this.eat("^")) {
				v = (long) v ^ (long) this.bitAnd();
			}
			return v;
		}

		private double bitAnd() {
			double v = this.equality();
			while (this.eat("&")) {
				v = (long) v & (long) this.equality();
			}
			return v;
		}

		private double equality() {
			double v = this.relational();
			while (true) {
				if (this.eat("==")) {
					v = v == this.relational() ? 1 : 0;
				} else if (this.eat("!=")) {
					v = v != this.relational() ? 1 : 0;
				} else {
					return v;
				}
			}
		}

		private double relational() {
			double v = this.shift();
			while (true) {
				if (this.eat("<=")) {
					v = v <= this.shift() ? 1 : 0;
				} else if (this.eat(">=")) {
					v = v >= this.shift() ? 1 : 0;
				} else if (this.eat("<")) {
					v = v < this.shift() ? 1 : 0;
				} else if (this.eat(">")) {
					v = v > this.shift() ? 1 : 0;
				} else {
					return v;
				}
			}
		}

		private double shift() {
			double v = this.additive();
			while (true) {
				if (this.eat("<<")) {
					v = (long) v << (long) this.additive();
				} else if (this.eat(">>")) {
					v = (long) v >> (long) this.additive();
				} else {
					return v;
				}
			}
		}

		private double additive() {
			double v = this.multiplicative();
			while (true) {
				if (this.eat("+")) {
					v += this.multiplicative();
				} else if (this.eat("-")) {
					v -= this.multiplicative();
				} else {
					return v;
				}
			}
		}

		private double multiplicative() {
			double v = this.unary();
			while (true) {
				if (this.eat("*")) {
					v *= this.unary();
				} else if (this.eat("/")) {
					v /= this.unary();
				} else if (this.eat("%")) {
					v %= this.unary();
				} else {
					return v;
				}
			}
		}

		private double unary() {
			if (this.eat("!")) {
				return this.unary() == 0.0 ? 1 : 0;
			}
			if (this.eat("-")) {
				return -this.unary();
			}
			if (this.eat("+")) {
				return this.unary();
			}
			if (this.eat("~")) {
				return ~(long) this.unary();
			}
			return this.primary();
		}

		private double primary() {
			this.skip();
			if (this.eat("(")) {
				double v = this.ternary();
				if (!this.eat(")")) {
					throw new IllegalStateException("expected ')'");
				}
				return v;
			}
			int start = this.pos;
			while (this.pos < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.')) {
				this.pos++;
			}
			String number = this.s.substring(start, this.pos);
			if (number.isEmpty()) {
				throw new IllegalStateException("expected a number at '" + this.s.substring(start) + "'");
			}
			return parseNumber(number);
		}

		static double parseNumber(final String text) {
			String t = text;
			if (t.startsWith("0x") || t.startsWith("0X")) {
				return Long.parseLong(t.substring(2).replaceAll("[uUlL]+$", ""), 16);
			}
			t = t.replaceAll("[uUlLfF]+$", "");
			if (t.endsWith("lf") || t.endsWith("LF")) {
				t = t.substring(0, t.length() - 2);
			}
			return Double.parseDouble(t);
		}
	}
}
