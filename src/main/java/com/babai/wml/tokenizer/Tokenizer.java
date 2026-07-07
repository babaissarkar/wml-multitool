package com.babai.wml.tokenizer;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import com.babai.wml.utils.Position;

import static com.babai.wml.parser.ParseUtils.*;

public final class Tokenizer implements Iterator<Token> {
	private enum State { NORMAL, LINE_COMMENT, WS };

	private final CharCursor r;
	private Token nextToken;
	private final StringBuilder buff = new StringBuilder(256);
	private final Position start = Position.start();
	private State state = State.NORMAL;
	private boolean exhausted = false;

	// Data extraction variables
	private static boolean enableExtraction = false;
	private static boolean extractBinPath = false;
	private static Set<Path> binaryPath = new HashSet<>();
	private static boolean extractTypeID = false;
	private static Set<String> unitTypes = new HashSet<>();
	private static boolean extractDefine = false;
	private static String mainDefine = "";
	private static boolean getNextTok;
	private static StringBuilder lineBuff = new StringBuilder();
	
	static {
		binaryPath.add(Path.of("data/core"));
	}

	public Tokenizer(String content) {
		this(content.toCharArray());
	}

	public Tokenizer(char[] input) {
		lineBuff.setLength(0);
		this.r = new CharCursor(input);
	}

	@Override
	public boolean hasNext() {
		if (nextToken == null) {
			nextToken = readNextToken();
		}
		return nextToken != null;
	}

	public Token peek() {
		return hasNext() ? nextToken : Token.EMPTY;
	}

	@Override
	public Token next() {
		if (!hasNext()) {
			throw new NoSuchElementException("No more tokens available");
		}
		Token token = nextToken;
		nextToken = null;
		return token;
	}

	private Token readNextToken() {
		if (exhausted) return null;

		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			switch (state) {
			case NORMAL -> {
				if (isWS(c)) {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					state = State.WS;
					buff.append(c);
				} else if (isEOL(c)) {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					return readEOLToken(c, r, start);
				} else if (c == '#') {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					buff.append(c);
					state = State.LINE_COMMENT;
				} else if (c == '"') {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					return readQuoteToken(r, buff, start);
				} else if (c == '<') {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					Token token = readAngleQuoteToken(r, buff, start);
					if (token != null) return token;
				} else if (c == '{') {
					if (!buff.isEmpty()) {
						r.unread(c);
						return finalizeToken(buff, Token.Kind.TEXT, start);
					}
					return readMacroToken(r, buff, start);
				} else {
					buff.append(c);
				}
			}

			case LINE_COMMENT -> {
				if (isEOL(c)) {
					r.unread(c);
					state = State.NORMAL;
					return finalizeToken(buff, Token.Kind.COMMENT, start);
				}
				buff.append(c);
			}

			case WS -> {
				if (!isWS(c)) {
					r.unread(c);
					state = State.NORMAL;
					return finalizeToken(buff, Token.Kind.WHITESPACE, start);
				}
				buff.append(c);
			}
			}
		}

		exhausted = true;
		if (buff.isEmpty()) return null;
		return switch (state) {
			case NORMAL -> finalizeToken(buff, Token.Kind.TEXT, start);
			case LINE_COMMENT -> finalizeToken(buff, Token.Kind.COMMENT, start);
			case WS -> finalizeToken(buff, Token.Kind.WHITESPACE, start);
		};
	}

	// Note: this assumes that r has just consumed the character '"'
	private Token readQuoteToken(CharCursor r, StringBuilder buff, Position start) {
		char prevChar = 0;
		buff.setLength(0);
		int ncount = 0;

		buff.append('"');
		int npos = 1;
		
		int ch;
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (prevChar == '"' && c == '"') {
				if (buff.length() == 1) {
					buff.append(c);
					npos++;
					break;
				}
				// else case: consecutive "" in middle of string: only 1 is added
			} else if (prevChar != '"' && c == '"') {
				buff.append(c);
				npos++;
				
				int c2 = r.peek();
				if (c2 == -1 || (char) c2 != '"') break;
			} else {
				if (isEOL(c)) {
					ncount++;
					npos = 0;
					if (c == '\r' && r.peek() == '\n') {
						r.read(); // consume \n of \r\n pair
					}
				} else {
					npos++;
				}
				buff.append(c);
			}
			prevChar = c;
		}

		Token token = finalizeToken(buff.toString(), Token.Kind.QUOTED, start, ncount, npos, false);
		buff.setLength(0);
		return token;
	}

	// Note: this assumes that r has just consumed the first '<' character
	private Token readAngleQuoteToken(CharCursor r, StringBuilder buff, Position start) {
		buff.setLength(0);
		int ncount = 0; int npos = 0;
		
		buff.append('<');
		int ch = r.read();
		if (ch == -1 || ((char) ch) != '<') {
			if (ch != -1) r.unread((char) ch);
			return null; // lone '<'
		}

		buff.append('<');
		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '>') {
				ch = r.read();
				if (ch != -1 && ((char) ch) == '>') {
					buff.append(c).append((char) ch);
					break;
				} else if (ch != -1) {
					r.unread((char) ch);
				}
				buff.append(c);
			} else {
				if (isEOL(c)) {
					ncount++;
					npos = 0;
					if (c == '\r' && r.peek() == '\n') r.read();
				} else {
					npos++;
				}
				buff.append(c);
			}
		}

		Token token = finalizeToken(buff.toString(), Token.Kind.ANGLE_QUOTED, start, ncount, npos, false);
		buff.setLength(0);
		return token;
	}

	// Note: this assumes that r has just consumed the character '{'
	// Note: we are skipping { and } from the token text itself, can be deduced from token kind
	private Token readMacroToken(CharCursor r, StringBuilder buff, Position start) {
		buff.setLength(0);
		int ch;
		int nlvl = 0;
		int ncount = 0; int npos = 0;
		boolean hasNested = false;

		while ((ch = r.read()) != -1) {
			char c = (char) ch;
			if (c == '{') {
				nlvl++;
				buff.append(c);
				npos++;
			} else if (c == '}') {
				if (nlvl == 0) {
					break;
				} else {
					nlvl--;
					hasNested = true; // at least one nested matching {} pair
					buff.append(c);
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
				buff.append(c);
			}
		}

		Token token = finalizeToken(buff.toString(), Token.Kind.MACRO, start, ncount, npos, hasNested);
		buff.setLength(0);
		return token;
	}

	private Token readEOLToken(char c, CharCursor r, Position start) {
		if (c == '\r') {
			int c2 = r.read();
			if (c2 != -1 && ((char) c2) == '\n') {
				return finalizeToken("\r\n", Token.Kind.EOL, start, 1, 0, false);
			}
			if (c2 != -1) {
				r.unread((char) c2);
			}
			return finalizeToken("\r", Token.Kind.EOL, start, 1, 0, false);
		}
		return finalizeToken(String.valueOf(c), Token.Kind.EOL, start, 1, 0, false);
	}

	private Token finalizeToken(StringBuilder buff, Token.Kind kind, Position start) {
		Token token = finalizeToken(buff.toString(), kind, start, 0, buff.length(), false);
		buff.setLength(0);
		return token;
	}

	private Token finalizeToken(String contents, Token.Kind kind, Position start, int ncount, int npos, boolean hasNested) {
		if (contents.isEmpty() && kind != Token.Kind.COMMENT) return null;

		if (kind != Token.Kind.WHITESPACE) {
			if (kind != Token.Kind.EOL) {
				extractData(contents);
			} else {
				commitBuff();
			}
		}

		Token token = new Token(contents, kind, start.line(), start.col(), hasNested);
		if (ncount == 0) {
			start.forward(npos);
		} else {
			for (int i = 0; i < ncount; i++) start.newline();
			start.forward(npos);
		}
		return token;
	}

	private static final class CharCursor {
		private final char[] input;
		private int idx = 0;
		private int pushback = -1;

		private CharCursor(char[] input) { this.input = input; }

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

	private static void commitBuff() {
		if (enableExtraction && extractTypeID && !lineBuff.isEmpty()) {
			String unitType = "";
			if (lineBuff.charAt(0) == '"' && lineBuff.charAt(lineBuff.length() - 1) == '"') {
				unitType = lineBuff.substring(1, lineBuff.length() - 1);
			} else {
				unitType = lineBuff.toString();
			}
			
			// extra condition: unittype must start with alphabetic
			if (!unitType.isEmpty() && Character.isAlphabetic(unitType.charAt(0))) {
				unitTypes.add(unitType);
			}
			extractTypeID = false;
			lineBuff.setLength(0);
		}
	}

	private static void extractData(String contents) {
		// define extraction is intentionally always enabled.
		// because it is needed by the preprocessor for [campaign] main define
		// autodetection, and users mostly need that.
		if (!enableExtraction) return;
		if (contents.isEmpty()) return;
		if (contents.charAt(0) != '['
			&& !(extractBinPath || extractTypeID || extractDefine)) return;

		if (contents.equals("[binary_path]")) {
			extractBinPath = true;
		} else if (contents.equals("[unit_type]")) {
			extractTypeID = true;
		} else if (contents.equals("[campaign]")) {
			extractDefine = true;
		} else if (extractBinPath) {
			if (contents.indexOf('/') >= 0) {
				int eqlPos = contents.indexOf('=');
				if (eqlPos >= 0) {
					String path = contents.substring(5).strip();
					if (path.charAt(0) == '"' && path.charAt(path.length() - 1) == '"') {
						path = path.substring(1, path.length() - 1);
					}
					if (!path.isEmpty()) {
						binaryPath.add(Path.of(path));
						extractBinPath = false;
					}
				} else if (!contents.isEmpty()) {
					String path = contents.strip();
					if (path.charAt(0) == '"' && path.charAt(path.length() - 1) == '"') {
						path = path.substring(1, path.length() - 1);
					}
					binaryPath.add(Path.of(path));
					extractBinPath = false;
				}
			}
		} else if (extractTypeID) {
			if (contents.startsWith("id") && lineBuff.isEmpty()) {
				int eqlPos = contents.indexOf('=');

				if (eqlPos >= 0) {
					String name = contents.substring(3);
					// avoid cases where the unittype id is a variable or empty
					if (!name.isEmpty() && name.charAt(0) != '$') {
						lineBuff.append(name);
					} else {
						getNextTok = true;
					}
				} else {
					getNextTok = true;
				}
			} else {
				if (!lineBuff.isEmpty()) {
					lineBuff.append(" ");
				}
				lineBuff.append(contents);
			}
		} else if (extractDefine) {
			int eqlPos = contents.indexOf('=');
			if (eqlPos >= 0 && contents.startsWith("define")) {
				String define = contents.substring(7);
				if (!define.isEmpty()) {
					mainDefine = define;
					extractDefine = false;
				} else {
					getNextTok = true;
				}
			}
		} else if (getNextTok) {
			if (extractTypeID) {
				// avoid cases where the unittype id is a variable or empty
				if (contents.charAt(0) != '$') {
					unitTypes.add(contents);
					extractTypeID = false;
				}
			} else if (extractDefine) {
				mainDefine = contents;
				extractDefine = false;
			}

			// separate '=' token, keep checking
			getNextTok = contents.charAt(0) == '=';
		}
	}

	public static void clearUnitTypes() {
		unitTypes.clear();
	}
}
