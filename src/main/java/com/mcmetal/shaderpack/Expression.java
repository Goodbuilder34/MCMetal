package com.mcmetal.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * OptiFine custom uniform expressions ({@code uniform.float.x = clamp(sin(sunAngle * 6.28), 0.0, 1.0)}).
 * Values are double vectors (length 1 for scalars, up to 16 for matrices); booleans are 0/1.
 * Supports the OptiFine function set, including stateful {@code smooth()}, component access ({@code cameraPosition.y})
 * and the {@code vec2/vec3/vec4} constructors.
 */
public abstract class Expression {
	public abstract double[] eval(Function<String, double @Nullable []> vars);

	public double scalar(final Function<String, double @Nullable []> vars) {
		return this.eval(vars)[0];
	}

	public static Expression parse(final String text) {
		Parser parser = new Parser(text);
		Expression expression = parser.ternary();
		parser.skip();
		if (parser.pos != text.length()) {
			throw new PackException("Unexpected '" + text.substring(parser.pos) + "' in expression " + text);
		}
		return expression;
	}

	// ---------------------------------------------------------------------------------------------

	private static final class Constant extends Expression {
		final double[] value;

		Constant(final double value) {
			this.value = new double[]{value};
		}

		@Override
		public double[] eval(final Function<String, double @Nullable []> vars) {
			return this.value;
		}
	}

	private static final class Variable extends Expression {
		final String name;
		final int component;

		Variable(final String name, final int component) {
			this.name = name;
			this.component = component;
		}

		@Override
		public double[] eval(final Function<String, double @Nullable []> vars) {
			double[] value = vars.apply(this.name);
			if (value == null) {
				return new double[]{0.0};
			}
			if (this.component >= 0) {
				return new double[]{this.component < value.length ? value[this.component] : 0.0};
			}
			return value;
		}
	}

	private static final class Unary extends Expression {
		final char op;
		final Expression operand;

		Unary(final char op, final Expression operand) {
			this.op = op;
			this.operand = operand;
		}

		@Override
		public double[] eval(final Function<String, double @Nullable []> vars) {
			double[] v = this.operand.eval(vars).clone();
			for (int i = 0; i < v.length; i++) {
				v[i] = this.op == '-' ? -v[i] : (v[i] == 0.0 ? 1.0 : 0.0);
			}
			return v;
		}
	}

	private static final class Binary extends Expression {
		final String op;
		final Expression left;
		final Expression right;

		Binary(final String op, final Expression left, final Expression right) {
			this.op = op;
			this.left = left;
			this.right = right;
		}

		@Override
		public double[] eval(final Function<String, double @Nullable []> vars) {
			if (this.op.equals("&&")) {
				return new double[]{this.left.scalar(vars) != 0.0 && this.right.scalar(vars) != 0.0 ? 1 : 0};
			}
			if (this.op.equals("||")) {
				return new double[]{this.left.scalar(vars) != 0.0 || this.right.scalar(vars) != 0.0 ? 1 : 0};
			}
			double[] a = this.left.eval(vars);
			double[] b = this.right.eval(vars);
			int n = Math.max(a.length, b.length);
			double[] out = new double[n];
			for (int i = 0; i < n; i++) {
				double x = a[a.length == 1 ? 0 : Math.min(i, a.length - 1)];
				double y = b[b.length == 1 ? 0 : Math.min(i, b.length - 1)];
				out[i] = switch (this.op) {
					case "+" -> x + y;
					case "-" -> x - y;
					case "*" -> x * y;
					case "/" -> y == 0.0 ? 0.0 : x / y;
					case "%" -> y == 0.0 ? 0.0 : x % y;
					case "<" -> x < y ? 1 : 0;
					case ">" -> x > y ? 1 : 0;
					case "<=" -> x <= y ? 1 : 0;
					case ">=" -> x >= y ? 1 : 0;
					case "==" -> x == y ? 1 : 0;
					case "!=" -> x != y ? 1 : 0;
					default -> throw new IllegalStateException(this.op);
				};
			}
			if (this.op.equals("==") || this.op.equals("!=")) {
				// Vector equality compares all components.
				boolean all = true;
				for (double v : out) {
					all &= this.op.equals("==") ? v != 0.0 : true;
				}
				if (this.op.equals("==")) {
					return new double[]{all ? 1 : 0};
				}
				boolean any = false;
				for (double v : out) {
					any |= v != 0.0;
				}
				return new double[]{any ? 1 : 0};
			}
			return out;
		}
	}

	private static final class Call extends Expression {
		final String name;
		final List<Expression> args;
		// smooth() state
		double smoothValue = Double.NaN;
		long smoothTime;
		final Random random = new Random();

		Call(final String name, final List<Expression> args) {
			this.name = name;
			this.args = args;
		}

		private double a(final int i, final Function<String, double @Nullable []> vars) {
			return i < this.args.size() ? this.args.get(i).scalar(vars) : 0.0;
		}

		@Override
		public double[] eval(final Function<String, double @Nullable []> vars) {
			switch (this.name) {
				case "vec2", "vec3", "vec4" -> {
					int n = this.name.charAt(3) - '0';
					double[] out = new double[n];
					for (int i = 0; i < n; i++) {
						out[i] = this.args.size() == 1 ? this.a(0, vars) : this.a(i, vars);
					}
					return out;
				}
				case "if" -> {
					// if(cond, value, [cond, value, ...], else)
					int i = 0;
					for (; i + 1 < this.args.size(); i += 2) {
						if (this.args.get(i).scalar(vars) != 0.0) {
							return this.args.get(i + 1).eval(vars);
						}
					}
					return i < this.args.size() ? this.args.get(i).eval(vars) : new double[]{0.0};
				}
				case "smooth" -> {
					// smooth([id,] value, [fadeUp, [fadeDown]]): exponential smoothing in seconds.
					int offset = this.args.size() >= 4 || (this.args.size() == 2 && false) ? 1 : 0;
					if (this.args.size() == 4) {
						offset = 1;
					}
					double target = this.a(offset, vars);
					double up = this.args.size() > offset + 1 ? this.a(offset + 1, vars) : 1.0;
					double down = this.args.size() > offset + 2 ? this.a(offset + 2, vars) : up;
					long now = System.nanoTime();
					if (Double.isNaN(this.smoothValue)) {
						this.smoothValue = target;
					} else {
						double dt = (now - this.smoothTime) / 1e9;
						double time = target > this.smoothValue ? up : down;
						double k = time <= 0.0 ? 1.0 : 1.0 - Math.exp(-dt * Math.log(100.0) / time);
						this.smoothValue += (target - this.smoothValue) * k;
					}
					this.smoothTime = now;
					return new double[]{this.smoothValue};
				}
				case "in" -> {
					double x = this.a(0, vars);
					for (int i = 1; i < this.args.size(); i++) {
						if (this.a(i, vars) == x) {
							return new double[]{1};
						}
					}
					return new double[]{0};
				}
				case "between" -> {
					double x = this.a(0, vars);
					return new double[]{x >= this.a(1, vars) && x <= this.a(2, vars) ? 1 : 0};
				}
				case "equals" -> {
					return new double[]{Math.abs(this.a(0, vars) - this.a(1, vars)) <= this.a(2, vars) ? 1 : 0};
				}
				case "clamp" -> {
					return new double[]{Math.max(this.a(1, vars), Math.min(this.a(2, vars), this.a(0, vars)))};
				}
				case "min", "max" -> {
					double v = this.a(0, vars);
					for (int i = 1; i < this.args.size(); i++) {
						v = this.name.equals("min") ? Math.min(v, this.a(i, vars)) : Math.max(v, this.a(i, vars));
					}
					return new double[]{v};
				}
				case "random" -> {
					return new double[]{this.random.nextDouble()};
				}
				default -> {
					double x = this.a(0, vars);
					double y = this.a(1, vars);
					double v = switch (this.name) {
						case "sin" -> Math.sin(x);
						case "cos" -> Math.cos(x);
						case "tan" -> Math.tan(x);
						case "asin" -> Math.asin(x);
						case "acos" -> Math.acos(x);
						case "atan" -> Math.atan(x);
						case "atan2" -> Math.atan2(x, y);
						case "torad" -> Math.toRadians(x);
						case "todeg" -> Math.toDegrees(x);
						case "abs" -> Math.abs(x);
						case "floor" -> Math.floor(x);
						case "ceil" -> Math.ceil(x);
						case "exp" -> Math.exp(x);
						case "frac" -> x - Math.floor(x);
						case "log" -> Math.log(x);
						case "pow" -> Math.pow(x, y);
						case "round" -> Math.round(x);
						case "signum" -> Math.signum(x);
						case "sqrt" -> Math.sqrt(x);
						case "fmod" -> y == 0.0 ? 0.0 : x - y * Math.floor(x / y);
						case "fract" -> x - Math.floor(x);
						default -> throw new PackException("Unknown function " + this.name + "()");
					};
					return new double[]{v};
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------

	private static final Map<String, Integer> COMPONENTS = new HashMap<>(Map.of("x", 0, "y", 1, "z", 2, "w", 3, "r", 0, "g", 1, "b", 2, "a", 3));

	private static final class Parser {
		final String s;
		int pos;

		Parser(final String s) {
			this.s = s;
		}

		void skip() {
			while (this.pos < this.s.length() && Character.isWhitespace(this.s.charAt(this.pos))) {
				this.pos++;
			}
		}

		boolean eat(final String op) {
			this.skip();
			if (this.s.startsWith(op, this.pos)) {
				if (op.length() == 1 && this.pos + 1 < this.s.length()) {
					char next = this.s.charAt(this.pos + 1);
					char c = op.charAt(0);
					if ((c == '<' || c == '>' || c == '!' || c == '=') && next == '=') {
						return false;
					}
					if ((c == '&' || c == '|') && next == c) {
						return false;
					}
				}
				this.pos += op.length();
				return true;
			}
			return false;
		}

		Expression ternary() {
			Expression cond = this.or();
			if (this.eat("?")) {
				Expression a = this.ternary();
				if (!this.eat(":")) {
					throw new PackException("Expected ':' in " + this.s);
				}
				Expression b = this.ternary();
				return new Call("if", List.of(cond, a, b));
			}
			return cond;
		}

		Expression or() {
			Expression e = this.and();
			while (this.eat("||")) {
				e = new Binary("||", e, this.and());
			}
			return e;
		}

		Expression and() {
			Expression e = this.equality();
			while (this.eat("&&")) {
				e = new Binary("&&", e, this.equality());
			}
			return e;
		}

		Expression equality() {
			Expression e = this.relational();
			while (true) {
				if (this.eat("==")) {
					e = new Binary("==", e, this.relational());
				} else if (this.eat("!=")) {
					e = new Binary("!=", e, this.relational());
				} else {
					return e;
				}
			}
		}

		Expression relational() {
			Expression e = this.additive();
			while (true) {
				if (this.eat("<=")) {
					e = new Binary("<=", e, this.additive());
				} else if (this.eat(">=")) {
					e = new Binary(">=", e, this.additive());
				} else if (this.eat("<")) {
					e = new Binary("<", e, this.additive());
				} else if (this.eat(">")) {
					e = new Binary(">", e, this.additive());
				} else {
					return e;
				}
			}
		}

		Expression additive() {
			Expression e = this.multiplicative();
			while (true) {
				if (this.eat("+")) {
					e = new Binary("+", e, this.multiplicative());
				} else if (this.eat("-")) {
					e = new Binary("-", e, this.multiplicative());
				} else {
					return e;
				}
			}
		}

		Expression multiplicative() {
			Expression e = this.unary();
			while (true) {
				if (this.eat("*")) {
					e = new Binary("*", e, this.unary());
				} else if (this.eat("/")) {
					e = new Binary("/", e, this.unary());
				} else if (this.eat("%")) {
					e = new Binary("%", e, this.unary());
				} else {
					return e;
				}
			}
		}

		Expression unary() {
			if (this.eat("-")) {
				return new Unary('-', this.unary());
			}
			if (this.eat("!")) {
				return new Unary('!', this.unary());
			}
			if (this.eat("+")) {
				return this.unary();
			}
			return this.primary();
		}

		Expression primary() {
			this.skip();
			if (this.eat("(")) {
				Expression e = this.ternary();
				if (!this.eat(")")) {
					throw new PackException("Expected ')' in " + this.s);
				}
				return e;
			}
			int start = this.pos;
			if (this.pos < this.s.length() && (Character.isDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.')) {
				while (this.pos < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '.')) {
					this.pos++;
				}
				return new Constant(Preprocessor.ExpressionParser.parseNumber(this.s.substring(start, this.pos)));
			}
			while (this.pos < this.s.length() && (Character.isLetterOrDigit(this.s.charAt(this.pos)) || this.s.charAt(this.pos) == '_')) {
				this.pos++;
			}
			String name = this.s.substring(start, this.pos);
			if (name.isEmpty()) {
				throw new PackException("Unexpected '" + this.s.substring(start) + "' in " + this.s);
			}
			if (name.equals("true")) {
				return new Constant(1.0);
			}
			if (name.equals("false")) {
				return new Constant(0.0);
			}
			if (name.equals("pi")) {
				return new Constant(Math.PI);
			}
			if (this.eat("(")) {
				List<Expression> args = new ArrayList<>();
				if (!this.eat(")")) {
					do {
						args.add(this.ternary());
					} while (this.eat(","));
					if (!this.eat(")")) {
						throw new PackException("Expected ')' after arguments of " + name + " in " + this.s);
					}
				}
				return new Call(name, args);
			}
			int component = -1;
			this.skip();
			if (this.pos < this.s.length() && this.s.charAt(this.pos) == '.') {
				int save = this.pos;
				this.pos++;
				int cs = this.pos;
				while (this.pos < this.s.length() && Character.isLetter(this.s.charAt(this.pos))) {
					this.pos++;
				}
				Integer c = COMPONENTS.get(this.s.substring(cs, this.pos));
				if (c != null) {
					component = c;
				} else {
					this.pos = save;
				}
			}
			return new Variable(name, component);
		}
	}
}
