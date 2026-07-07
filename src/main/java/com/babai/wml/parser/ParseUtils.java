package com.babai.wml.parser;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.ListIterator;

import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;

public final class ParseUtils {

	private ParseUtils() {};

	public static boolean isPlus(Token t) {
		return t.isKind(Token.Kind.TEXT) && t.content().equals("+");
	}

	public static boolean isEOL(char c) {
		return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
	}

	public static boolean isWS(char c) {
		return Character.isWhitespace(c) && !isEOL(c);
	}

	public static Token peek(ListIterator<Token> it) {
		if (!it.hasNext()) {
			return Token.EMPTY;
		}

		Token t = it.next();
		it.previous();
		return t;
	}

	public static void skip(ListIterator<Token> itor, Token.Kind skipKind) {
		while (itor.hasNext() && ParseUtils.peek(itor).kind() == skipKind) {
			itor.next();
		}
	}

	public static void skip(ListIterator<Token> itor, Token.Kind skipKind, Token.Kind skipKind2) {
		while (itor.hasNext() && (ParseUtils.peek(itor).kind() == skipKind || ParseUtils.peek(itor).kind() == skipKind2))
		{
			itor.next();
		}
	}


	public static Token peek(Tokenizer tokenizer) {
		return tokenizer.peek();
	}

	public static void skip(Tokenizer tokenizer, Token.Kind skipKind) {
		while (tokenizer.hasNext() && tokenizer.peek().kind() == skipKind) {
			tokenizer.next();
		}
	}

	public static void skip(Tokenizer tokenizer, Token.Kind skipKind, Token.Kind skipKind2) {
		while (tokenizer.hasNext() && (tokenizer.peek().kind() == skipKind || tokenizer.peek().kind() == skipKind2)) {
			tokenizer.next();
		}
	}

	public record Pair<T, V>(T first, V second) {};

	public static Pair<String, List<Integer>> parseMacroCall(String macro) {
		var pos = new ArrayList<Integer>();

		int i = 0;

		if (macro.length() < 2
			|| ((macro.charAt(i) != '{') && macro.charAt(macro.length() - 1) != '}'))
		{
			return new Pair<>("", pos); // not a macro call or invalid size
		}
		i++; // skip initial {

		while (i < macro.length() - 1 && isWS(macro.charAt(i))) i++; // skip WS after {

		// Macro Name
		int nameStart = i;
		while (i < macro.length() - 1 && !isWS(macro.charAt(i))) i++;
		int nameEnd = i;

		// Macro args
		boolean inParen = false;
		while (i < macro.length() - 1) { // -1 because last one would always be '}'
			while (i < macro.length() - 1 && isWS(macro.charAt(i))) i++; // skip separator WS
			pos.add(i); // mark arg start

			if(macro.charAt(i) == '(') {
				inParen = true;
				i++;
			}

			if (inParen) {
				while (i < macro.length() - 1 && macro.charAt(i) != ')') i++;
				if (macro.charAt(i) == ')') i++;
				inParen = false;
			} else {
				while (i < macro.length() - 1 && !isWS(macro.charAt(i))) i++; // skip arg
			}
		}

		return new Pair<>(macro.substring(nameStart, nameEnd), pos);
	}

	public static List<String> splitQuoted(String token) {
		List<String> parts = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		char[] chars = token.toCharArray();

		int i = 0;
		int nlvl = 0; // () nesting level

		while (i < chars.length) {
			sb.setLength(0);

			// Token boundary starts at the next non-whitespace character.
			while (i < chars.length && Character.isWhitespace(chars[i])) i++; // skip WS
			if (i >= chars.length) break;

			// Read one token. Whitespace ends the token unless we're inside a quoted span.
			while (i < chars.length && !Character.isWhitespace(chars[i])) {
				char c = chars[i];
				if (c == '"') {
					// Keep quote characters in the resulting token to preserve existing call-site behavior.
					// Example: KEY="a b" should stay as one token (not split at inner whitespace).
					sb.append(c);
					i++;
					while (i < chars.length) {
						char q = chars[i];
						sb.append(q);
						i++;
						if (q == '"') break;
					}
				} else if (c == '(' && sb.isEmpty()) {
					// Parenthesized value at token start acts as quoting against whitespace.
					// Keep the body but strip the outer parentheses: (a b) -> a b
					nlvl++;
					i++;
					while (i < chars.length) {
						if (chars[i] == '(') nlvl++;
						if (chars[i] == ')') nlvl--;

						if (nlvl == 0) break;

						sb.append(chars[i]);
						i++;
					}
					// Skip closing ')' if present; tolerate malformed input by leaving as-is.
					if (i < chars.length && chars[i] == ')') i++;
				} else if (c == '{' && sb.isEmpty()) {
					// Macro start '{' at token start acts as quoting against whitespace.
					sb.append(c);
					i++;
					while (i < chars.length && chars[i] != '}') {
						sb.append(chars[i]);
						i++;
					}
					// append terminating '}'
					sb.append(chars[i]);
					i++;
				} else {
					// Regular unquoted token content.
					sb.append(c);
					i++;
				}
			}

			// Commit parsed token fragment.
			parts.add(sb.toString());
		}

		return parts;
	}

	public static String csvEscape(String s) {
		if (s == null) return "";

		boolean needsQuotes =
				s.contains(",") ||
				s.contains("\"") ||
				s.contains("\n") ||
				s.contains("\r");

		if (!needsQuotes) return s;

		return "\"" + s.replace("\"", "\"\"") + "\"";
	}

	public static String substitute(String template, Map<String, String> subst) {
		if (template.indexOf('{') == -1) return template;

		String out = template;
		for (var e : subst.entrySet()) {
			String key = "{" + e.getKey() + "}";
			out = out.replace(key, e.getValue());
		}
		return out;
	}
}
