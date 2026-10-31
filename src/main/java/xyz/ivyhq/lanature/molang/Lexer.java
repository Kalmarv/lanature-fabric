package xyz.ivyhq.lanature.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Molang tokens. Identifiers are lowercased (Molang is case-insensitive outside strings). */
final class Lexer {
	enum Kind { NUMBER, STRING, IDENT, OP, EOF }

	record Token(Kind kind, String text, double number, int pos) {
		boolean is(String op) {
			return (kind == Kind.OP || kind == Kind.IDENT) && text.equals(op);
		}
	}

	private static final String[] OPS = {"->", "??", "&&", "||", "==", "!=", "<=", ">=", "<", ">", "+", "-", "*", "/", "!", "?", ":", "=",
		"(", ")", "{", "}", "[", "]", ",", ";", "."};

	static List<Token> lex(String src) {
		List<Token> out = new ArrayList<>();
		int i = 0;
		int n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
			} else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
				int start = i;
				while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
					i++;
				}
				if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E') && i + 1 < n
					&& (Character.isDigit(src.charAt(i + 1)) || ((src.charAt(i + 1) == '-' || src.charAt(i + 1) == '+') && i + 2 < n && Character.isDigit(src.charAt(i + 2))))) {
					i += 2;
					while (i < n && Character.isDigit(src.charAt(i))) {
						i++;
					}
				}
				String num = src.substring(start, i);
				if (i < n && (src.charAt(i) == 'f' || src.charAt(i) == 'F')) {
					i++; // 1.0f
				}
				out.add(new Token(Kind.NUMBER, num, parseNumber(num), start));
			} else if (c == '\'') {
				int end = src.indexOf('\'', i + 1);
				if (end < 0) {
					throw new MolangException("unterminated string at " + i + " in " + src);
				}
				out.add(new Token(Kind.STRING, src.substring(i + 1, end), 0, i));
				i = end + 1;
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
					i++;
				}
				out.add(new Token(Kind.IDENT, src.substring(start, i).toLowerCase(Locale.ROOT), 0, start));
			} else {
				String op = null;
				for (String candidate : OPS) {
					if (src.startsWith(candidate, i)) {
						op = candidate;
						break;
					}
				}
				if (op == null) {
					throw new MolangException("unexpected '" + c + "' at " + i + " in " + src);
				}
				out.add(new Token(Kind.OP, op, 0, i));
				i += op.length();
			}
		}
		out.add(new Token(Kind.EOF, "", 0, n));
		return out;
	}

	private static double parseNumber(String s) {
		// "1." and "1.2.3" are seen in packs; take the longest valid prefix like Bedrock's parser does
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			int second = s.indexOf('.', s.indexOf('.') + 1);
			return second > 0 ? Double.parseDouble(s.substring(0, second)) : 0;
		}
	}
}
