package xyz.ivyhq.lanature.molang;

import java.util.ArrayList;
import java.util.List;
import xyz.ivyhq.lanature.molang.Lexer.Kind;
import xyz.ivyhq.lanature.molang.Lexer.Token;
import xyz.ivyhq.lanature.molang.Node.Namespace;
import xyz.ivyhq.lanature.molang.Scope.Builtin;
import xyz.ivyhq.lanature.molang.Scope.Flow;

/**
 * Recursive-descent Molang parser. Precedence, lowest first: assignment, conditional ({@code ?:}), {@code ??},
 * {@code ||}, {@code &&}, equality, comparison, additive, multiplicative, unary, {@code ->}, primary.
 */
final class Parser {
	private final List<Token> tokens;
	private final String source;
	private int pos;

	private Parser(String source) {
		this.source = source;
		this.tokens = Lexer.lex(source);
	}

	static Node parse(String source) {
		Parser p = new Parser(source);
		Node n = p.program(false);
		if (p.peek().kind() != Kind.EOF) {
			throw p.error("unexpected '" + p.peek().text() + "'");
		}
		return n;
	}

	private Token peek() {
		return tokens.get(pos);
	}

	private Token next() {
		return tokens.get(pos++);
	}

	private boolean accept(String op) {
		if (peek().is(op) && peek().kind() == Kind.OP) {
			pos++;
			return true;
		}
		return false;
	}

	private void expect(String op) {
		if (!accept(op)) {
			throw error("expected '" + op + "' but found '" + peek().text() + "'");
		}
	}

	private MolangException error(String message) {
		return new MolangException(message + " at " + peek().pos() + " in: " + source);
	}

	/** Statements up to the end (or a closing brace). A single statement without ';' is a simple expression. */
	private Node program(boolean scoped) {
		List<Node> statements = new ArrayList<>();
		boolean complex = scoped;
		while (peek().kind() != Kind.EOF && !(peek().kind() == Kind.OP && peek().is("}"))) {
			if (accept(";")) {
				complex = true;
				continue;
			}
			statements.add(statement());
			if (accept(";")) {
				complex = true;
			} else if (peek().kind() != Kind.EOF && !(peek().kind() == Kind.OP && peek().is("}"))) {
				// Bedrock tolerates a missing ';' before a block-ending statement; treat it as a separator
				complex = true;
			}
		}
		if (!complex && statements.size() == 1) {
			return statements.getFirst();
		}
		return new Node.Block(statements, scoped);
	}

	private Node statement() {
		Token t = peek();
		if (t.kind() == Kind.IDENT) {
			switch (t.text()) {
				case "return" -> {
					pos++;
					boolean bare = peek().kind() == Kind.EOF || peek().is(";") || peek().is("}");
					return new Node.Return(bare ? null : expression());
				}
				case "break" -> {
					pos++;
					return new Node.Jump(Flow.BREAK);
				}
				case "continue" -> {
					pos++;
					return new Node.Jump(Flow.CONTINUE);
				}
				default -> {
				}
			}
		}
		return expression();
	}

	private Node block() {
		expect("{");
		Node body = program(true);
		expect("}");
		return body;
	}

	private Node expression() {
		Node left = conditional();
		if (peek().kind() == Kind.OP && peek().is("=")) {
			pos++;
			if (!(left instanceof Node.Var target)) {
				throw error("assignment to something that is not a variable");
			}
			return new Node.Assign(target, expression());
		}
		return left;
	}

	private Node conditional() {
		Node cond = coalesce();
		if (accept("?")) {
			Node then = branch();
			Node otherwise = accept(":") ? branch() : null;
			return new Node.Conditional(cond, then, otherwise);
		}
		return cond;
	}

	private Node branch() {
		if (peek().is("{") && peek().kind() == Kind.OP) {
			return block();
		}
		return conditionalAssign();
	}

	/** A branch may itself be an assignment ({@code c ? v.x = 1 : v.x = 2}) or a nested conditional. */
	private Node conditionalAssign() {
		Node left = conditional();
		if (peek().kind() == Kind.OP && peek().is("=")) {
			pos++;
			if (!(left instanceof Node.Var target)) {
				throw error("assignment to something that is not a variable");
			}
			return new Node.Assign(target, conditionalAssign());
		}
		return left;
	}

	private Node coalesce() {
		Node left = or();
		while (accept("??")) {
			left = new Node.Coalesce(left, or());
		}
		return left;
	}

	private Node or() {
		Node left = and();
		while (accept("||")) {
			left = new Node.Binary('|', left, and());
		}
		return left;
	}

	private Node and() {
		Node left = equality();
		while (accept("&&")) {
			left = new Node.Binary('&', left, equality());
		}
		return left;
	}

	private Node equality() {
		Node left = comparison();
		while (true) {
			if (accept("==")) {
				left = new Node.Equals(left, comparison(), false);
			} else if (accept("!=")) {
				left = new Node.Equals(left, comparison(), true);
			} else {
				return left;
			}
		}
	}

	private Node comparison() {
		Node left = additive();
		while (true) {
			if (accept("<=")) {
				left = new Node.Binary('l', left, additive());
			} else if (accept(">=")) {
				left = new Node.Binary('g', left, additive());
			} else if (accept("<")) {
				left = new Node.Binary('<', left, additive());
			} else if (accept(">")) {
				left = new Node.Binary('>', left, additive());
			} else {
				return left;
			}
		}
	}

	private Node additive() {
		Node left = multiplicative();
		while (true) {
			if (accept("+")) {
				left = new Node.Binary('+', left, multiplicative());
			} else if (accept("-")) {
				left = new Node.Binary('-', left, multiplicative());
			} else {
				return left;
			}
		}
	}

	private Node multiplicative() {
		Node left = unary();
		while (true) {
			if (accept("*")) {
				left = new Node.Binary('*', left, unary());
			} else if (accept("/")) {
				left = new Node.Binary('/', left, unary());
			} else {
				return left;
			}
		}
	}

	private Node unary() {
		if (accept("!")) {
			return new Node.Not(unary());
		}
		if (accept("-")) {
			return new Node.Neg(unary());
		}
		if (accept("+")) {
			return unary();
		}
		return arrow();
	}

	private Node arrow() {
		Node left = primary();
		if (accept("->")) {
			return new Node.Arrow(left, arrow());
		}
		return left;
	}

	private Node primary() {
		Token t = next();
		switch (t.kind()) {
			case NUMBER -> {
				return new Node.Constant(t.number());
			}
			case STRING -> {
				return new Node.Text(t.text());
			}
			case OP -> {
				if (t.is("(")) {
					Node inner = expression();
					expect(")");
					return inner;
				}
				if (t.is("{")) {
					pos--;
					return block();
				}
				pos--;
				throw error("unexpected '" + t.text() + "'");
			}
			case IDENT -> {
				return identifier(t.text());
			}
			default -> throw error("unexpected end");
		}
	}

	private Node identifier(String first) {
		switch (first) {
			case "true" -> {
				return new Node.Constant(1);
			}
			case "false" -> {
				return new Node.Constant(0);
			}
			case "this" -> {
				return new Node.This();
			}
			case "break" -> {
				return new Node.Jump(Flow.BREAK); // as a conditional branch: c ? break
			}
			case "continue" -> {
				return new Node.Jump(Flow.CONTINUE);
			}
			case "return" -> {
				boolean bare = peek().kind() == Kind.EOF || peek().is(";") || peek().is("}") || peek().is(":");
				return new Node.Return(bare ? null : conditional());
			}
			case "loop" -> {
				expect("(");
				Node count = expression();
				expect(",");
				Node body = branch();
				expect(")");
				return new Node.Loop(count, body);
			}
			case "for_each" -> {
				expect("(");
				Node var = expression();
				if (!(var instanceof Node.Var v)) {
					throw error("for_each needs a variable");
				}
				expect(",");
				Node collection = expression();
				expect(",");
				Node body = branch();
				expect(")");
				return new Node.ForEach(v, collection, body);
			}
			default -> {
			}
		}
		List<String> path = new ArrayList<>();
		while (accept(".")) {
			Token seg = next();
			if (seg.kind() != Kind.IDENT) {
				throw error("expected a name after '.'");
			}
			path.add(seg.text());
		}
		String ns = first;
		switch (ns) {
			case "v", "variable" -> {
				return variable(Namespace.VARIABLE, path);
			}
			case "t", "temp" -> {
				return variable(Namespace.TEMP, path);
			}
			case "c", "context" -> {
				return variable(Namespace.CONTEXT, path);
			}
			case "q", "query" -> {
				return call("query." + String.join(".", path), Functions.query(String.join(".", path)));
			}
			case "math" -> {
				String name = String.join(".", path);
				if (name.equals("pi")) {
					return new Node.Constant(Math.PI);
				}
				Node.Function fn = Functions.math(name);
				if (fn == null) {
					throw error("unknown function math." + name);
				}
				return call("math." + name, fn);
			}
			case "geometry", "texture", "material", "array" -> {
				Node index = null;
				if (accept("[")) {
					index = expression();
					expect("]");
				}
				return new Node.Resource(ns, String.join(".", path), index);
			}
			default -> throw error("unknown name '" + first + (path.isEmpty() ? "" : "." + String.join(".", path)) + "'");
		}
	}

	private Node variable(Namespace ns, List<String> path) {
		if (path.isEmpty()) {
			throw error("variable without a name");
		}
		if (ns == Namespace.VARIABLE && path.size() == 1) {
			Builtin b = Builtin.byVariable(path.getFirst());
			if (b != null) {
				return new Node.BuiltinVar(b);
			}
		}
		return new Node.Var(ns, path);
	}

	private Node call(String name, Node.Function fn) {
		List<Node> args = new ArrayList<>();
		if (accept("(")) {
			if (!accept(")")) {
				do {
					args.add(expression());
				} while (accept(","));
				expect(")");
			}
		}
		return new Node.Call(name, fn, args.toArray(Node[]::new));
	}
}
