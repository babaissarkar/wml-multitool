package com.babai.wml.tokenizer;

import java.io.Reader;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.PushbackReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import com.babai.wml.utils.AIGenerated;
import com.babai.wml.utils.Position;


public final class Tokenizer {
	private final static Pattern linepattern = Pattern.compile("\\R");
	
	public static ArrayList<Token> tokenize(Path inputPath) throws IOException {
		return tokenize(Files.newBufferedReader(inputPath));
	}
	
	public static ArrayList<Token> tokenize(String content) throws IOException {
		return tokenize(new BufferedReader(new StringReader(content)));
	}

	public static ArrayList<Token> tokenize(Reader reader) throws IOException {
		PushbackReader r = new PushbackReader(reader);
		ArrayList<Token> tokens = new ArrayList<>();
		StringBuilder buff = new StringBuilder();
		State state = State.NORMAL;
		Position start = Position.start();

		int ch;
		while((ch = r.read()) != -1) {
			char c = (char) ch;
			switch(state) {
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
			// file terminated in the middle of content, finish tokens
			// TODO maybe throw exception to warn about the issue.
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

	// TODO detect mismatched quotes
	// Note: this assumes that r is currently at the character '"' (dbl quote)
	// Note: we are skipping " from the token text itself, unless escaped by "".
	// Can be deduced from token kind if needed. 
	private static String readQuoteToken(PushbackReader r) throws IOException {
		char prevChar = '"';
		var buff = new StringBuilder();
		int ch;
		while((ch = r.read()) != -1) {
			char c = (char) ch;
			if (prevChar == '"' && c == '"') {
				if (buff.isEmpty()) {
					// trivial case: "" (empty string), do nothing, terminate
					return "";
				} else {
					// back to back quotes: "text""text", add only one "
					buff.append(c);
				}
			} else if (prevChar != '"' && c == '"') {
				// terminate quote token
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

	// Note: this assumes that r is currently at the character '<' (greater than)
	// Note: we are skipping << and >> from the token text itself, can be deduced from token kind
	// TODO throw exception if mismatched
	private static String readAngleQuoteToken(PushbackReader r) throws IOException {
		var buff = new StringBuilder();
		int ch = r.read();
		if (((char) ch) != '<') {
			r.unread(ch);
			// << not matched, char after < not another <, bail out
			return "";
		}

		while((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '>') {
				ch = r.read();
				if (((char) ch) == '>') {
					// >> detected, exit
					break;
				} else {
					r.unread(ch);
				}
				buff.append(c);
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}

	// Note: this assumes that r is currently at the character '{' (greater than)
	// Note: we are skipping { and } from the token text itself, can be deduced from token kind
	// TODO throw exception if mismatched or prematurely terminates
	// TODO what about { inside quotes? like {HELLO "some{weirdo"}
	private static String readMacroToken(PushbackReader r) throws IOException {
		var buff = new StringBuilder();
		int ch;
		int nlvl = 0; // nesting level

		while((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '{') {
				nlvl++;
				buff.append(c); // keep inner braces
			} else if (c == '}') {
				if (nlvl == 0) {
					break;
				} else {
					nlvl--;
					buff.append(c); // keep inner braces
				}
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}
	
	// Note: this assumes that r is currently at the character '['
	// Note: we are skipping [ and ] from the token text itself, can be deduced from token kind
	// TODO throw exception if mismatched or prematurely terminates
	// TODO edge cases...
	private static String readTagToken(PushbackReader r) throws IOException {
		var buff = new StringBuilder();
		int ch;
		while((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == ']') {
				break;
			} else {
				buff.append(c);
			}
		}
		return buff.toString();
	}

	private static void handleEOLToken(ArrayList<Token> tokens, char c, PushbackReader r, Position start) throws IOException {
		if (c == '\r') {
			char c2 = (char) r.read();
			if (c2 == '\n') {
				finalizeAndAddToken(tokens, "\r\n", Token.Kind.EOL, start);
			} else {
				r.unread(c2);
			}
		} else {
			finalizeAndAddToken(tokens, "" + c, Token.Kind.EOL, start);
		}
	}

	private static void finalizeAndAddToken(ArrayList<Token> tokens, String contents, Token.Kind kind, Position start) {
		if (!contents.isEmpty() || kind == Token.Kind.COMMENT) {
			tokens.add(new Token(contents, kind, start.line(), start.col()));

			// -1 is needed so trailing lines aren't lost
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

	private static void finalizeAndAddToken(ArrayList<Token> tokens, StringBuilder buff, Token.Kind kind, Position start) {
		finalizeAndAddToken(tokens, buff.toString(), kind, start);
		if (!buff.isEmpty()) {
			buff.delete(0, buff.length());
		}
	}

	@AIGenerated
	public static ArrayList<Token> mergeConcatenations(ArrayList<Token> tokens) {
		ArrayList<Token> result = new ArrayList<>();
		int i = 0;

		while (i < tokens.size()) {
			Token current = tokens.get(i);

			// Only TEXT or QUOTED can start a concat chain
			if (isConcatCandidate(current)) {

				StringBuilder merged = new StringBuilder(current.content());
				Token.Kind resultingKind = current.kind();
				Token previousOperand = current;

				int j = i + 1;

				while (j < tokens.size()) {

					int k = j;
					while (k < tokens.size() && tokens.get(k).kind() == Token.Kind.WHITESPACE) k++;

					if (k >= tokens.size() || !isPlus(tokens.get(k))) break;

					k++;

					while (k < tokens.size() && tokens.get(k).kind() == Token.Kind.WHITESPACE) k++;

					if (k >= tokens.size()) break;

					Token next = tokens.get(k);
					if (!isConcatCandidate(next)) break;

					// Pairwise spacing rule
					if (previousOperand.kind() == Token.Kind.TEXT
						&& next.kind() == Token.Kind.TEXT)
					{
						merged.append(" ");
					}

					merged.append(next.content());

					if (next.kind() == Token.Kind.QUOTED) {
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
		return t.kind() == Token.Kind.TEXT || t.kind() == Token.Kind.QUOTED;
	}

	private static boolean isPlus(Token t) {
		return t.kind() == Token.Kind.TEXT && t.content().equals("+");
	}

	private static boolean isEOL(char c) {
		return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
	}

	private static boolean isWS(char c) {
		return Character.isWhitespace(c) && !isEOL(c);
	}


	private enum State { NORMAL, LINE_COMMENT, WS };
}
