package com.babai.wml.experimental;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.parser.Parser;
import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;

import com.babai.wml.tokenizer.Token.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TokenizerTest {
	private static List<Token> tokenize(String text) {
		var tokenizer = new Tokenizer(text);
		var tokens = new ArrayList<Token>();
		while (tokenizer.hasNext()) {
			tokens.add(tokenizer.next());
		}
		return tokens;
	}
	
	@Test
	void testArgPosSimple() {
		String macro = "{TEST ARG1 ARG2 ARG3}";
		var info = ParseUtils.parseMacroCall(macro);
		assertEquals("TEST", info.first());
		var posList = info.second();
		assertEquals(3, posList.size());
		assertEquals(6, posList.get(0));
		assertEquals(11, posList.get(1));
		assertEquals(16, posList.get(2));
	}
	
	@Test
	void testArgPosParen() {
		String macro = "{TEST ARG1 ARG2 (LONG ARG3) ( ) ()}";
		var info = ParseUtils.parseMacroCall(macro);
		assertEquals("TEST", info.first());
		var posList = info.second();
		assertEquals(5, posList.size());
		assertEquals(6, posList.get(0));
		assertEquals(11, posList.get(1));
		assertEquals(16, posList.get(2));
		assertEquals(28, posList.get(3));
		assertEquals(32, posList.get(4));
	}
	
	@Test
	void testTokenPositions() throws IOException {
		String text = """
				#define TESTB A B
				#arg C
				Default#endarg
				"a={A}, b={B}, c={C}"#enddef
				#define TEST
				#arg A
				Unknown#endarg
				"Hello, {A}!"#enddef
				{TEST}
				{TESTB 2 3 C=4}
				""";
		var toks = tokenize(text);

		record Expectation(String content, Kind kind, int beginLine, int beginColumn) {}

		List<Expectation> expected = List.of(
				new Expectation("#define TESTB A B",        Kind.COMMENT, 1,  1),
				new Expectation("\n",                       Kind.EOL,     1,  18),
				new Expectation("#arg C",                   Kind.COMMENT, 2,  1),
				new Expectation("\n",                       Kind.EOL,     2,  7),
				new Expectation("Default",                  Kind.TEXT,    3,  1),
				new Expectation("#endarg",                  Kind.COMMENT, 3,  8),
				new Expectation("\n",                       Kind.EOL,     3,  15),
				new Expectation("\"a={A}, b={B}, c={C}\"",  Kind.QUOTED,  4,  1),
				new Expectation("#enddef",                  Kind.COMMENT, 4,  22),
				new Expectation("\n",                       Kind.EOL,     4,  29),
				new Expectation("#define TEST",             Kind.COMMENT, 5,  1),
				new Expectation("\n",                       Kind.EOL,     5,  13),
				new Expectation("#arg A",                   Kind.COMMENT, 6,  1),
				new Expectation("\n",                       Kind.EOL,     6,  7),
				new Expectation("Unknown",                  Kind.TEXT,    7,  1),
				new Expectation("#endarg",                  Kind.COMMENT, 7,  8),
				new Expectation("\n",                       Kind.EOL,     7,  15),
				new Expectation("\"Hello, {A}!\"",          Kind.QUOTED,  8,  1),
				new Expectation("#enddef",                  Kind.COMMENT, 8,  14),
				new Expectation("\n",                       Kind.EOL,     8,  21),
				new Expectation("TEST",                     Kind.MACRO,   9,  1),
				new Expectation("\n",                       Kind.EOL,     9,  5),
				new Expectation("TESTB 2 3 C=4",            Kind.MACRO,   10, 1),
				new Expectation("\n",                       Kind.EOL,     10, 14)
				);

		assertEquals(expected.size(), toks.size(),
				"Token count mismatch. Got: " + toks);

		for (int i = 0; i < expected.size(); i++) {
			Expectation exp = expected.get(i);
			Token tok = toks.get(i);
			String ctx = "Token[" + i + "] (" + exp.content() + ")";
			assertEquals(exp.content(),     tok.content(),     ctx + " content mismatch");
			assertEquals(exp.kind(),        tok.kind(),        ctx + " kind mismatch");
			assertEquals(exp.beginLine(),   tok.beginLine(),   ctx + " beginLine mismatch");
			assertEquals(exp.beginColumn(), tok.beginColumn(), ctx + " beginColumn mismatch");
		}
	}
	
	@Test
	void testParenQuotedSplit() {
		String text = "Hello\n    (How\n are) you \"Konrad the Second\"";
		var parts = ParseUtils.splitQuoted(text);
		System.out.println("Toks(quoted split): " + parts);
		assertEquals(4, parts.size());
		assertEquals("Hello", parts.get(0));
		assertEquals("How\n are", parts.get(1));
		assertEquals("you", parts.get(2));
		assertEquals("\"Konrad the Second\"", parts.get(3));
	}
	
	@ParameterizedTest
	@MethodSource("substituteCases")
	void testSubstitute(String template, Map<String, String> subst, String expected) {
		assertEquals(expected, ParseUtils.substitute(template, subst));
	}

	static Stream<Arguments> substituteCases() {
		return Stream.of(
			// no braces - early return
			arguments("no braces", Map.of(), "no braces"),
			// single substitution
			arguments("{A}", Map.of("A", "val"), "val"),
			// multiple substitutions
			arguments("{A} and {B}", Map.of("A", "x", "B", "y"), "x and y"),
			// unknown key - emitted verbatim
			arguments("{UNKNOWN}", Map.of(), "{UNKNOWN}"),
			// mixed known and unknown
			arguments("{A} {UNKNOWN}", Map.of("A", "val"), "val {UNKNOWN}"),
			// text around macros
			arguments("prefix {A} suffix", Map.of("A", "mid"), "prefix mid suffix"),
			// quoted string passthrough + comment preservation
			arguments("\"hello\" {A} # comment", Map.of("A", "val"), "\"hello\" val # comment"),
			// empty subst map, no macros
			arguments("plain text", Map.of(), "plain text")
			);
	}

	@Test
	void testCommentSplit() {
		String text = "Hello #Comment\nLine2";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(comment test): " + toks);
			assertEquals(5, toks.size());
			assertEquals("Hello", toks.get(0).content());
			assertEquals(1, toks.get(0).beginLine());
			assertEquals(" ", toks.get(1).content());
			assertEquals("#Comment", toks.get(2).content());
			assertEquals("\n", toks.get(3).content());
			assertEquals("Line2", toks.get(4).content());
			assertEquals(2, toks.get(4).beginLine());
		}
	}
	
	@Test
	void testSimpleKeyValPair() {
		String text = "key=value";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(keyval test): " + toks);
			assertEquals(1, toks.size());
			assertEquals("key=value", toks.get(0).content());
			assertEquals(Token.Kind.TEXT, toks.get(0).kind());
		}	
	}
	
	@Test
	void testSpacedKeyValPair() {
		String text = "key = value";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(spaced keyval): " + toks);
			assertEquals(5, toks.size());
			assertEquals("key", toks.get(0).content());
			assertEquals(Token.Kind.TEXT, toks.get(0).kind());
			assertEquals("value", toks.get(4).content());
			assertEquals(Token.Kind.TEXT, toks.get(4).kind());
		}	
	}

	@Test
	void testQuotedString() {
		String text = "key=\"value val\"\"ue2\nvalue3\"";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(quoted keyval): " + toks);
			assertEquals(2, toks.size());
			// checks "" -> " collapse, preservation of whitespace
			assertEquals("key=", toks.get(0).content());
			assertEquals("\"value val\"ue2\nvalue3\"", toks.get(1).content());
		}	
	}
	
	@Test
	void testAngledQuotedString() {
		String text = "key=<<value val\"ue2\nvalue3>>";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(angle quoted keyval): " + toks);
			assertEquals(2, toks.size());
			assertEquals("key=", toks.get(0).content());
			assertEquals("<<value val\"ue2\nvalue3>>", toks.get(1).content());
		}	
	}
	
	@Test
	void testMacroString() {
		String text = "key={MYMACRO ARG1 ARG2 ARG3=\"def\"}";
		{
			List<Token> toks = tokenize(text);
			System.out.println("Toks(macro keyval): " + toks);
			assertEquals(2, toks.size());
			// checks 1. "" -> " collapse, preservation of whitespace
			assertEquals("key=", toks.get(0).content());
			assertEquals("MYMACRO ARG1 ARG2 ARG3=\"def\"", toks.get(1).content());
		}	
	}
	
	@Test
	void testSingleAngle() {
		String text = "<hello>";
		{
			List<Token> toks = tokenize(text);
			assertEquals(1, toks.size());
			assertEquals(Token.Kind.TEXT, toks.get(0).kind());
			assertEquals("<hello>", toks.get(0).content());
		}
	}
	
	@Test
	void testUnbalancedAngle() {
		String text = "<hello";
		{
			List<Token> toks = tokenize(text);
			assertEquals(1, toks.size());
			assertEquals(Token.Kind.TEXT, toks.get(0).kind());
			assertEquals("<hello", toks.get(0).content());
		}
	}
	
	// FIXME since merging moved to ParserTokenizer, these will need updating.
	/*
	@Test
	void testQuotedConcatenation() {
		String text = "\"Hello \" + \"Hello\"";
		try {
			List<Token> toks = tokenize(text, true);
			System.out.println("Toks(quoted concat): " + toks);

			assertEquals(1, toks.size());
			assertEquals("Hello Hello", toks.get(0).content());
			assertEquals(Token.Kind.QUOTED, toks.get(0).kind());
		}
	}

	@Test
	void testUnquotedConcatenation() {
		String text = "foo + bar";
		try {
			List<Token> toks = tokenize(text, true);
			System.out.println("Toks(unquoted concat): " + toks);

			assertEquals(1, toks.size());
			assertEquals("foo bar", toks.get(0).content());
			assertEquals(Token.Kind.TEXT, toks.get(0).kind());
		}
	}

	@Test
	void testMixedConcatenation() {
		String text = "\"Hello\" + world";
		try {
			List<Token> toks = tokenize(text, true);
			System.out.println("Toks(mixed concat): " + toks);

			assertEquals(1, toks.size());
			assertEquals("Helloworld", toks.get(0).content());
			assertEquals(Token.Kind.QUOTED, toks.get(0).kind());
		}
	}

	@Test
	void testChainedConcatenation() {
		String text = "\"Journey\" + of + a + \"Frost Mage\"";
		try {
			List<Token> toks = tokenize(text, true);
			System.out.println("Toks(chained concat): " + toks);

			assertEquals(1, toks.size());
			assertEquals("Journeyof aFrost Mage", toks.get(0).content());
			assertEquals(Token.Kind.QUOTED, toks.get(0).kind());
		}
	}
	*/
	
	@Test
	void testSnippetTokenization() throws IOException {
		String text = """
			[binary_path]
				path=data/add-ons/Frost_Mage
			[/binary_path]""";
		var binaryPaths = new HashSet<String>();
		Parser p = new Parser();
		p.addQuery("binary_path/path", v -> binaryPaths.add(v));
		p.parse(text);
		assertEquals(1, binaryPaths.size());
	}
}
