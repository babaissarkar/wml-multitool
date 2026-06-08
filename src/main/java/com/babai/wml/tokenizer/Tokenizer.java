package com.babai.wml.tokenizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import com.babai.wml.utils.Position;

import static com.babai.wml.parser.ParseUtils.*;

public final class Tokenizer {
	private enum State { NORMAL, LINE_COMMENT, WS };
	
	// Data extraction variables
	private static boolean enableExtraction = false;
	private static boolean extractBinPath = false;
	private static Set<Path> binaryPath = new HashSet<>();
	private static boolean extractTypeID = false;
	private static Set<String> unitTypes = new HashSet<>();
	private static boolean extractDefine = false;
	private static String mainDefine = "";
	private static boolean getNextTok;
	
	public static List<Token> tokenize(String content) throws IOException {
		return tokenize(content.toCharArray());
	}

	public static List<Token> tokenize(char[] input) throws IOException {
		CharCursor r = new CharCursor(input);
		List<Token> tokens = new ArrayList<>();
		State state = State.NORMAL;
		Position start = Position.start();
		boolean leading = true;
		int tokenStart = 0;

		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			int charIdx = r.position() - 1;
			switch (state) {
			case NORMAL -> {
				if (isWS(c) && leading) {
					finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
					state = State.WS;
					tokenStart = charIdx;
				} else {
					if (isEOL(c)) {
						leading = true;
						finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
						handleEOLToken(tokens, input, charIdx, c, r, start);
						tokenStart = r.position();
					} else {
						leading = false;
						if (c == '#') {
							finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
							state = State.LINE_COMMENT;
							tokenStart = r.position();
						} else if (c == '"') {
							finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
							handleQuoteToken(tokens, input, r, start);
							tokenStart = r.position();
						} else if (c == '<') {
							finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
							boolean angleToken = handleAngleQuoteToken(tokens, input, r, start);
							tokenStart = angleToken ? r.position() : charIdx;
						} else if (c == '{') {
							finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.TEXT, start);
							handleMacroToken(tokens, input, r, start);
							tokenStart = r.position();
						}
					}
				}
			}

			case LINE_COMMENT -> {
				if (isEOL(c)) {
					finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.COMMENT, start);
					handleEOLToken(tokens, input, charIdx, c, r, start);
					tokenStart = r.position();
					state = State.NORMAL;
					leading = true;
				}
			}

			case WS -> {
				if (!isWS(c)) {
					finalizeAndAddToken(tokens, input, tokenStart, charIdx, Token.Kind.WHITESPACE, start);
					if (c == '#') {
						state = State.LINE_COMMENT;
						tokenStart = r.position();
					} else {
						state = State.NORMAL;
						tokenStart = charIdx;
					}
				}

				if (isEOL(c)) {
					handleEOLToken(tokens, input, charIdx, c, r, start);
					tokenStart = r.position();
					leading = true;
				} else if (c == '"') {
					handleQuoteToken(tokens, input, r, start);
					tokenStart = r.position();
				} else if (c == '<') {
					boolean angleToken = handleAngleQuoteToken(tokens, input, r, start);
					tokenStart = angleToken ? r.position() : charIdx;
				} else if (c == '{') {
					handleMacroToken(tokens, input, r, start);
					tokenStart = r.position();
				}
			}
			}
		}

		if (ch == -1 && tokenStart < input.length) {
			if (state == State.NORMAL) {
				finalizeAndAddToken(tokens, input, tokenStart, input.length, Token.Kind.TEXT, start);
			} else if (state == State.LINE_COMMENT) {
				finalizeAndAddToken(tokens, input, tokenStart, input.length, Token.Kind.COMMENT, start);
			} else if (state == State.WS) {
				finalizeAndAddToken(tokens, input, tokenStart, input.length, Token.Kind.WHITESPACE, start);
			}
		}

		return tokens;
	}

	// Note: this assumes that the starting '"' has already been consumed.
	// Note: we are skipping starting and ending " from the token text itself, can be deduced from token kind
	private static void handleQuoteToken(List<Token> tokens, char[] input, CharCursor r, Position start) {
		char prevChar = '"';
		StringBuilder buff = new StringBuilder(64);
		int ncount = 0; int npos = 0;
		
		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (prevChar == '"' && c == '"') {
				if (buff.isEmpty()) {
					return;
				} else {
					buff.append(c);
					npos++;
				}
			} else if (prevChar != '"' && c == '"') {
				int c2 = r.read();
				if (c2 == -1) break;
				char ch2 = (char) c2;
				r.unread(ch2);
				if (ch2 != '"') break;
			} else {
				if (isEOL(c)) {
					ncount++;
					npos = 0;
					if (c == '\r' && r.peek() == '\n') {
						r.read();
					}
				} else {
					npos++;
				}
				buff.append(c);
			}
			prevChar = c;
		}
		
		finalizeAndAddToken(tokens, toCharArray(buff), 0, buff.length(), Token.Kind.QUOTED, start, ncount, npos, false);
	}

	// Note: this assumes that the first '<' has already been consumed.
	// Note: we are skipping << and >> from the token text itself, can be deduced from token kind
	private static boolean handleAngleQuoteToken(List<Token> tokens, char[] input, CharCursor r, Position start) {
		int ncount = 0; int npos = 0;
		
		int ch = r.read();
		if (ch == -1 || ((char) ch) != '<') {
			if (ch != -1) r.unread((char) ch);
			return false;
		}

		int tokenStart = r.position();
		int tokenEnd = tokenStart;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '>') {
				ch = r.read();
				if (ch != -1 && ((char) ch) == '>') {
					tokenEnd = r.position() - 2;
					break;
				} else {
					if (ch != -1) r.unread((char) ch);
				}
			} else {
				if (isEOL(c)) {
					ncount++;
					npos = 0;
					if (c == '\r' && r.peek() == '\n') r.read();
				} else {
					npos++;
				}
			}
			tokenEnd = r.position();
		}
		
		finalizeAndAddToken(tokens, input, tokenStart, tokenEnd, Token.Kind.ANGLE_QUOTED, start, ncount, npos, false);
		return true;
	}

	// Note: this assumes that the starting '{' has already been consumed.
	// Note: we are skipping { and } from the token text itself, can be deduced from token kind
	private static void handleMacroToken(List<Token> tokens, char[] input, CharCursor r, Position start) {
		int tokenStart = r.position();
		int tokenEnd = tokenStart;
		int ch;
		int nlvl = 0;
		int ncount = 0; int npos = 0;
		boolean hasNested = false;

		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '{') {
				nlvl++;
				npos++;
			} else if (c == '}') {
				if (nlvl == 0) {
					tokenEnd = r.position() - 1;
					break;
				} else {
					nlvl--;
					hasNested = true; // at least one nested matching {} pair
					npos++;
				}
			} else {
				if (isEOL(c)) {
					ncount++;
					npos = 0;
					if (c == '\r' && r.peek() == '\n') r.read();
				} else {
					npos++;
				}
			}
			tokenEnd = r.position();
		}
		
		finalizeAndAddToken(tokens, input, tokenStart, tokenEnd, Token.Kind.MACRO, start, ncount, npos, hasNested);
	}

	private static void handleEOLToken(List<Token> tokens, char[] input, int eolStart, char c, CharCursor r, Position start) {
		if (c == '\r') {
			int c2 = r.read();
			if (c2 != -1 && ((char) c2) == '\n') {
				finalizeAndAddToken(tokens, input, eolStart, r.position(), Token.Kind.EOL, start, 1, 0, false);
			} else {
				if (c2 != -1) {
					r.unread((char) c2);
				}
				finalizeAndAddToken(tokens, input, eolStart, eolStart + 1, Token.Kind.EOL, start, 1, 0, false);
			}
		} else {
			finalizeAndAddToken(tokens, input, eolStart, eolStart + 1, Token.Kind.EOL, start, 1, 0, false);
		}
	}

	private static void finalizeAndAddToken(List<Token> tokens, char[] input, int tokenStart, int tokenEnd, Token.Kind kind, Position start) {
		finalizeAndAddToken(tokens, input, tokenStart, tokenEnd, kind, start, 0, tokenEnd - tokenStart, false);
	}

	private static void finalizeAndAddToken(List<Token> tokens, char[] input, int tokenStart, int tokenEnd, Token.Kind kind, Position start, int ncount, int npos, boolean hasNested) {
		if (tokenEnd < tokenStart) tokenEnd = tokenStart;
		if (tokenEnd > tokenStart || kind == Token.Kind.COMMENT) {
			extractData(input, tokenStart, tokenEnd);
			
			tokens.add(new Token(input, tokenStart, tokenEnd, kind, start.line(), start.col(), hasNested));
			if (ncount == 0) {
				start.forward(npos);
			} else {
				for (int i = 0; i < ncount; i++) start.newline();
				start.forward(npos);
			}
		}
	}

	private static char[] toCharArray(StringBuilder buff) {
		char[] chars = new char[buff.length()];
		buff.getChars(0, buff.length(), chars, 0);
		return chars;
	}

	private static final class CharCursor {
		private final char[] input;
		private int idx = 0;
		private int pushback = -1;

		private CharCursor(char[] input) { this.input = input; }

		private int position() { return pushback != -1 ? idx - 1 : idx; }

		private int peek() {
			if (pushback != -1) return pushback;
			if (idx >= input.length) return -1;
			return input[idx];  // don't advance idx
		}

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
	
	// Data Extraction
	
	public static void enableExtraction(boolean enabled) {
		enableExtraction = enabled;
	}

	public static Set<Path> getBinaryPaths() {
		return binaryPath;
	}

	public static Set<String> getUnitTypes() {
		return unitTypes;
	}
	
	public static String getMainDefine() {
		return mainDefine;
	}
	
	private static void extractData(char[] contents, int start, int end) {
		// define extraction is intentionally always enabled.
		// because it is needed by the preprocessor for [campaign] main define
		// autodetection, and users mostly need that.
		if (!enableExtraction) return;
		if (end <= start) return;
		if (contents[start] != '['
			&& !(extractBinPath || extractTypeID || extractDefine)) return;
		
		if (spanEquals(contents, start, end, "[binary_path]")) {
			extractBinPath = true;
		} else if (spanEquals(contents, start, end, "[unit_type]")) {
			extractTypeID = true;
		} else if (spanEquals(contents, start, end, "[campaign]")) {
			extractDefine = true;
		} else if (extractBinPath) {
			if (indexOf(contents, start, end, '/') >= 0) {
				int eqlPos = indexOf(contents, start, end, '=');
				if (eqlPos >= 0) {
					String path = new String(contents, Math.min(start + 5, end), end - Math.min(start + 5, end));
					if (!path.isEmpty()) {
						binaryPath.add(Path.of(path));
						extractBinPath = false;
					}
				} else {
					binaryPath.add(Path.of(new String(contents, start, end - start)));
					extractBinPath = false;
				}
			}
		} else if (extractTypeID) {
			int eqlPos = indexOf(contents, start, end, '=');
			if (eqlPos >= 0 && spanStartsWith(contents, start, end, "id")) {
				int nameStart = Math.min(start + 3, end);
				// avoid cases where the unittype id is a variable or empty
				if (nameStart < end && contents[nameStart] != '$') {
					unitTypes.add(new String(contents, nameStart, end - nameStart));
					extractTypeID = false;
				} else {
					getNextTok = true;
				}
			}
		} else if (extractDefine) {
			int eqlPos = indexOf(contents, start, end, '=');
			if (eqlPos >= 0 && spanStartsWith(contents, start, end, "define")) {
				int defineStart = Math.min(start + 7, end);
				if (defineStart < end) {
					mainDefine = new String(contents, defineStart, end - defineStart);
					extractDefine = false;
				} else {
					getNextTok = true;
				}
			}
		} else if (getNextTok && end > start) {
			if (extractTypeID) {
				// avoid cases where the unittype id is a variable or empty
				if (contents[start] != '$') {
					unitTypes.add(new String(contents, start, end - start));
					extractTypeID = false;
				}
			} else if (extractDefine) {
				mainDefine = new String(contents, start, end - start);
				extractDefine = false;
			}
			getNextTok = false;
		}
	}

	private static boolean spanEquals(char[] buf, int start, int end, String value) {
		if (value.length() != end - start) return false;
		for (int i = 0; i < value.length(); i++) {
			if (buf[start + i] != value.charAt(i)) return false;
		}
		return true;
	}

	private static boolean spanStartsWith(char[] buf, int start, int end, String value) {
		if (value.length() > end - start) return false;
		for (int i = 0; i < value.length(); i++) {
			if (buf[start + i] != value.charAt(i)) return false;
		}
		return true;
	}

	private static int indexOf(char[] buf, int start, int end, char value) {
		for (int i = start; i < end; i++) {
			if (buf[i] == value) return i;
		}
		return -1;
	}
}
