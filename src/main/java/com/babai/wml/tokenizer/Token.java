package com.babai.wml.tokenizer;

public final record Token(char[] buf, int start, int end, Kind kind, int beginLine, int beginColumn, boolean nested) {
	private static final char[] EMPTY_BUF = new char[0];
	public final static Token EMPTY = new Token(EMPTY_BUF, 0, 0, Kind.EOF, 0, 0, false);
	
	public Token(String content, Kind kind) {
		this(content.toCharArray(), 0, content.length(), kind, 0, 0, false);
	}

	public Token(String content, Kind kind, int beginLine, int beginColumn, boolean nested) {
		this(content.toCharArray(), 0, content.length(), kind, beginLine, beginColumn, nested);
	}

	public Token(char[] buf, int start, int end, Kind kind) {
		this(buf, start, end, kind, 0, 0, false);
	}

	public Token(char[] buf, int start, int end, Kind kind, int beginLine, int beginColumn, boolean nested) {
		if (buf == null) throw new NullPointerException("buf");
		if (start < 0 || end < start || end > buf.length) {
			throw new IndexOutOfBoundsException("Invalid token span [" + start + ", " + end + ") for buffer length " + buf.length);
		}
		this.buf = buf;
		this.start = start;
		this.end = end;
		this.kind = kind;
		this.beginLine = beginLine;
		this.beginColumn = beginColumn;
		this.nested = nested;
	}
	
	public String content() {
		return materialize();
	}

	public String materialize() {
		return new String(buf, start, length());
	}

	public int length() {
		return end - start;
	}

	public boolean isEmpty() {
		return length() == 0;
	}

	public char charAt(int index) {
		if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
		return buf[start + index];
	}

	public boolean spanEquals(String value) {
		if (value == null || value.length() != length()) return false;
		for (int i = 0; i < value.length(); i++) {
			if (buf[start + i] != value.charAt(i)) return false;
		}
		return true;
	}

	public boolean spanStartsWith(String value) {
		if (value == null || value.length() > length()) return false;
		for (int i = 0; i < value.length(); i++) {
			if (buf[start + i] != value.charAt(i)) return false;
		}
		return true;
	}

	public boolean spanStripLeadingStartsWith(String value) {
		int offset = start;
		while (offset < end && Character.isWhitespace(buf[offset])) offset++;
		if (value == null || value.length() > end - offset) return false;
		for (int i = 0; i < value.length(); i++) {
			if (buf[offset + i] != value.charAt(i)) return false;
		}
		return true;
	}
	
	// TODO multiline tokens
	public int endLine() { return beginLine(); }
	// FIXME correction for escaped stuff, like {}
	public int endColumn() { return beginColumn() + length(); } 
	
	public boolean isDirective() {
		if (kind != Token.Kind.COMMENT) return false;
		
		if (isEmpty()) return false;
		
		String[] directives = {
			"define",
			"arg",
			"undef",
			"ifdef",
			"ifndef",
			"ifhave",
			"ifnhave",
			"ifver",
			"ifnver",
			"else",
			"error",
			"warning",
			"deprecated",
			"textdomain",
			"wmlscope",
			"wmllint"
		};

		for (String d : directives) {
			if (spanStripLeadingStartsWith(d)) {
				return true;
			}
		}
		return false;
	}
	
	public boolean isDirectiveName(String directiveName, boolean hasArg) {
		if (kind != Token.Kind.COMMENT) return false;
		
		if (isEmpty()) return false;
		
		return hasArg ? spanStartsWith(directiveName) : spanEquals(directiveName);
		// TODO throw error if hasArg = false & content.startsWith(directiveName) passes.
	}
	
	public void raw(StringBuilder buff) {
		writeRaw(buf, start, end, kind, buff);
	}
	
	public static void writeRaw(String content, Token.Kind kind, StringBuilder buff) {
		if (buff == null) return;
		writeRaw(content.toCharArray(), 0, content.length(), kind, buff);
	}

	public static void writeRaw(char[] buf, int start, int end, Token.Kind kind, StringBuilder buff) {
		if(buff == null) return;
		
		switch (kind) {
			case TAG_START -> appendWrapped(buff, '[', buf, start, end, ']');
			case TAG_END -> {
				buff.append("[/");
				buff.append(buf, start, end - start);
				buff.append(']');
			}
			case QUOTED -> appendWrapped(buff, '"', buf, start, end, '"');
			case ANGLE_QUOTED -> {
				buff.append("<<");
				buff.append(buf, start, end - start);
				buff.append(">>");
			}
			case MACRO -> appendWrapped(buff, '{', buf, start, end, '}');
			case COMMENT -> {
				buff.append('#');
				buff.append(buf, start, end - start);
			}
			case EOF -> throw new UnsupportedOperationException("EOF token has no raw value");
			default -> buff.append(buf, start, end - start);
		};
	}

	private static void appendWrapped(StringBuilder buff, char open, char[] buf, int start, int end, char close) {
		buff.append(open);
		buff.append(buf, start, end - start);
		buff.append(close);
	}

	public enum Kind {
		TEXT, COMMENT, EOL, WHITESPACE, QUOTED, ANGLE_QUOTED, MACRO, EOF,
		VAL, EQL, TAG_START, TAG_END
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Token [content=");
		builder.append(materialize());
		builder.append(", kind=");
		builder.append(kind);
		builder.append(", beginLine=");
		builder.append(beginLine);
		builder.append(", beginColumn=");
		builder.append(beginColumn);
		builder.append("]");
		return builder.toString();
	}

	public boolean isKind(Kind...kinds) {
		for (var k : kinds) {
			if (kind == k) return true;
		}
		return false;
	}
	
	public boolean isNotKind(Kind...kinds) {
		return !isKind(kinds);
	}
}
