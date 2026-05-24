package com.babai.wml.tokenizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.babai.wml.utils.AIGenerated;
import com.babai.wml.utils.Position;


public final class Tokenizer {
	private final static Pattern linepattern = Pattern.compile("\\R");
	
	private enum State { NORMAL, LINE_COMMENT, WS };

	public static List<Token> tokenize(Path inputPath) throws IOException {
		return tokenize(Files.readString(inputPath));
	}

	public static List<Token> tokenize(String content) throws IOException {
		return tokenize(content.toCharArray());
	}

	public static List<Token> tokenize(char[] input) throws IOException {
		CharCursor r = new CharCursor(input);
		List<Token> tokens = new ArrayList<>();
		StringBuilder buff = new StringBuilder();
		State state = State.NORMAL;
		Position start = Position.start();

		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			switch (state) {
			case NORMAL -> {
				if (c == '#') {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					state = State.LINE_COMMENT;
				} else if (isWS(c)) {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					state = State.WS;
					buff.append(c);
				} else if (isEOL(c)) {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					handleEOLToken(tokens, c, r, start);
				} else if (c == '"') {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					finalizeAndAddToken(tokens, readQuoteToken(r), Token.Kind.QUOTED, start);
				} else if (c == '<') {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					String text = readAngleQuoteToken(r);
					if (text.isEmpty()) {
						buff.append(c);
					} else {
						finalizeAndAddToken(tokens, text, Token.Kind.ANGLE_QUOTED, start);
					}
				} else if (c == '{') {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					finalizeAndAddToken(tokens, readMacroToken(r), Token.Kind.MACRO, start);
				} else if (c == '[') {
					finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
					finalizeAndAddToken(tokens, readTagToken(r), Token.Kind.TAG, start);
				} else {
					buff.append(c);
				}
			}

			case LINE_COMMENT -> {
				if (isEOL(c)) {
					finalizeAndAddToken(tokens, buff, Token.Kind.COMMENT, start);
					handleEOLToken(tokens, c, r, start);
					state = State.NORMAL;
				} else {
					buff.append(c);
				}
			}

			case WS -> {
				if (!isWS(c)) {
					finalizeAndAddToken(tokens, buff, Token.Kind.WHITESPACE, start);
					if (c == '#') {
						state = State.LINE_COMMENT;
					} else {
						state = State.NORMAL;
					}
				}

				if (isEOL(c)) {
					handleEOLToken(tokens, c, r, start);
				} else if (c == '"') {
					finalizeAndAddToken(tokens, readQuoteToken(r), Token.Kind.QUOTED, start);
				} else if (c == '<') {
					finalizeAndAddToken(tokens, readAngleQuoteToken(r), Token.Kind.ANGLE_QUOTED, start);
				} else if (c == '{') {
					finalizeAndAddToken(tokens, readMacroToken(r), Token.Kind.MACRO, start);
				} else if (c == '[') {
					finalizeAndAddToken(tokens, readTagToken(r), Token.Kind.TAG, start);
				} else {
					if (c != '#') {
						buff.append(c);
					}
				}
			}
			}
		}

		if (ch == -1 && !buff.isEmpty()) {
			if (state == State.NORMAL) {
				finalizeAndAddToken(tokens, buff, Token.Kind.TEXT, start);
			} else if (state == State.LINE_COMMENT) {
				finalizeAndAddToken(tokens, buff, Token.Kind.COMMENT, start);
			} else if (state == State.WS) {
				finalizeAndAddToken(tokens, buff, Token.Kind.WHITESPACE, start);
			}
		}

		return mergeConcatenations(tokens);
	}

	private static String readQuoteToken(CharCursor r) {
		char prevChar = '"';
		var buff = new StringBuilder();
		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (prevChar == '"' && c == '"') {
				if (buff.isEmpty()) {
					return "";
				} else {
					buff.append(c);
				}
			} else if (prevChar != '"' && c == '"') {
				int c2 = r.read();
				if (c2 == -1) break;
				char ch2 = (char) c2;
				r.unread(ch2);
				if (ch2 != '"') break;
			} else {
				buff.append(c);
			}
			prevChar = c;
		}
		return buff.toString();
	}

	private static String readAngleQuoteToken(CharCursor r) {
		var buff = new StringBuilder();
		int ch = r.read();
		if (ch == -1 || ((char) ch) != '<') {
			if (ch != -1) {
				r.unread((char) ch);
			}
			return "";
		}

		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '>') {
				ch = r.read();
				if (ch != -1 && ((char) ch) == '>') {
					break;
				} else {
					if (ch != -1) {
						r.unread((char) ch);
					}
				}
				buff.append(c);
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}

	private static String readMacroToken(CharCursor r) {
		var buff = new StringBuilder();
		int ch;
		int nlvl = 0;

		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '{') {
				nlvl++;
				buff.append(c);
			} else if (c == '}') {
				if (nlvl == 0) {
					break;
				} else {
					nlvl--;
					buff.append(c);
				}
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}

	private static String readTagToken(CharCursor r) {
		var buff = new StringBuilder();
		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == ']') {
				break;
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}

	private static void handleEOLToken(List<Token> tokens, char c, CharCursor r, Position start) {
		if (c == '\r') {
			int c2 = r.read();
			if (c2 != -1 && ((char) c2) == '\n') {
				finalizeAndAddToken(tokens, "\r\n", Token.Kind.EOL, start);
			} else {
				if (c2 != -1) {
					r.unread((char) c2);
				}
				finalizeAndAddToken(tokens, "\r", Token.Kind.EOL, start);
			}
		} else {
			finalizeAndAddToken(tokens, "" + c, Token.Kind.EOL, start);
		}
	}

	private static void finalizeAndAddToken(List<Token> tokens, String contents, Token.Kind kind, Position start) {
		if (!contents.isEmpty() || kind == Token.Kind.COMMENT) {
			tokens.add(new Token(contents, kind, start.line(), start.col()));
			String[] parts = linepattern.split(contents, -1);
			if (parts.length == 1) {
				start.forward(contents.length());
			} else {
				for (int i = 0; i < parts.length - 1; i++) {
					start.newline();
				}
				start.forward(parts[parts.length - 1].length());
			}
		}
	}

	private static void finalizeAndAddToken(List<Token> tokens, StringBuilder buff, Token.Kind kind, Position start) {
		finalizeAndAddToken(tokens, buff.toString(), kind, start);
		if (!buff.isEmpty()) {
			buff.delete(0, buff.length());
		}
	}

	@AIGenerated
	public static List<Token> mergeConcatenations(List<Token> tokens) {
		List<Token> result = new ArrayList<>();
		int i = 0;
		while (i < tokens.size()) {
			Token current = tokens.get(i);
			if (isConcatCandidate(current)) {
				StringBuilder merged = new StringBuilder(current.content());
				Token.Kind resultingKind = current.kind();
				Token previousOperand = current;
				int j = i + 1;
				while (j < tokens.size()) {
					int k = j;
					while (k < tokens.size() && tokens.get(k).isKind(Token.Kind.WHITESPACE)) k++;

					if (k >= tokens.size() || !isPlus(tokens.get(k))) break;
					k++;

					while (k < tokens.size() && tokens.get(k).isKind(Token.Kind.WHITESPACE)) k++;

					if (k >= tokens.size()) break;
					Token next = tokens.get(k);
					if (!isConcatCandidate(next)) break;

					// Pairwise spacing rule
					if (previousOperand.isKind(Token.Kind.TEXT)
						&& next.isKind(Token.Kind.TEXT))
					{
						merged.append(" ");
					}
					merged.append(next.content());

					if (next.isKind(Token.Kind.QUOTED)) {
						resultingKind = Token.Kind.QUOTED;
					}
					previousOperand = next;
					j = k + 1;
				}
				result.add(new Token(merged.toString(), resultingKind));
				i = j;
			} else {
				result.add(current);
				i++;
			}
		}
		return result;
	}

	private static boolean isConcatCandidate(Token t) {
		return t.isKind(Token.Kind.TEXT, Token.Kind.QUOTED);
	}

	private static boolean isPlus(Token t) {
		return t.isKind(Token.Kind.TEXT) && t.content().equals("+");
	}

	private static boolean isEOL(char c) {
		return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
	}

	private static boolean isWS(char c) {
		return Character.isWhitespace(c) && !isEOL(c);
	}

	private static final class CharCursor {
		private final char[] input;
		private int idx = 0;
		private int pushback = -1;

		private CharCursor(char[] input) { this.input = input; }

		private int read() {
			if (pushback != -1) {
				int c = pushback;
				pushback = -1;
				return c;
			}
			if (idx >= input.length) return -1;
			return input[idx++];
		}

		private void unread(char c) { this.pushback = c; }
	}
}
