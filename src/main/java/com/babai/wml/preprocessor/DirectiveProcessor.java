package com.babai.wml.preprocessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;

import static com.babai.wml.cli.ANSIFormatter.colorify;
import static com.babai.wml.parser.ParseUtils.isWS;
import static com.babai.wml.parser.ParseUtils.peek;
import static com.babai.wml.parser.ParseUtils.skip;
import static com.babai.wml.tokenizer.Token.Kind.COMMENT;
import static com.babai.wml.tokenizer.Token.Kind.EOL;
import static com.babai.wml.tokenizer.Token.Kind.WHITESPACE;
import static com.babai.wml.utils.Colors.directiveColor;
import static com.babai.wml.utils.LogUtils.debugPrint;
import static com.babai.wml.utils.LogUtils.errorPrint;
import static com.babai.wml.utils.LogUtils.position;

public class DirectiveProcessor {
	private boolean skipElse = true;
	
	private TokenProcessor tokenProcessor = null;
	
	public DirectiveProcessor(TokenProcessor processor) {
		this.tokenProcessor = processor;
	}
	
	void handleDirective(Token directiveStart, Tokenizer itor, MacroDefTable defines, String pathUri) {
		boolean skipTrailingWS = true;
		var directiveHeader = DirectiveHeader.parse(directiveStart, pathUri);
		var directiveArgs = directiveHeader.args();
		
		if (directiveHeader.head().equals("#define")) {
			// Macro name
			String macroName = directiveArgs.getFirst();
			List<String> macroArgs = directiveArgs.subList(1, directiveArgs.size());

			skip(itor, EOL, WHITESPACE);

			// Macro deprecation messages
			boolean isDeprecated = false;
			int depreLevel = 0;
			String removalVersion = "";
			String depreMessage = "";
			while (peek(itor).isDirectiveName("#deprecated", true)) {
				debugPrint(() -> "Deprecated macro: " + macroName);
				Token t = itor.next();
				isDeprecated = true;
				var deprecationHeader = DirectiveHeader.parse(t, pathUri);
				var depreArgs = deprecationHeader.args();
				depreLevel = Integer.parseInt(depreArgs.getFirst());
				if (depreLevel == 2 || depreLevel == 3) {
					if (depreArgs.size() > 1) {
						removalVersion = depreArgs.get(1);
					}

					// Rest of args are actually the message in this case that got split
					// join back.
					if (depreArgs.size() > 2) {
						depreMessage = String.join(" ", depreArgs.subList(2, depreArgs.size()));
					}
				} else if (depreLevel == 1 || depreLevel == 4) {
					// Rest of args are actually the message in this case that got split
					// join back.
					if (depreArgs.size() > 1) {
						depreMessage = String.join(" ", depreArgs.subList(1, depreArgs.size()));
					}
				}

				skip(itor, EOL, WHITESPACE);
			}

			String doc = handleDocComment(itor);

			skip(itor, EOL, WHITESPACE);

			// defargs processing
			var macroDefaultArgs = new HashMap<String, String>();
			while (peek(itor).isDirectiveName("#arg", true)) {
				Token t = itor.next();
				String defArgName = DirectiveHeader.parse(t, pathUri).args().getFirst(); // arg NAME

				skip(itor, EOL);

				macroDefaultArgs.put(defArgName, consumeUntilEndDirective("#endarg", itor, Set.of(), pathUri));

				skip(itor, EOL, WHITESPACE);
			}

			// Body
			// Collect args in context, used in processToken macroExpansion
//			if (expandMacro) {
			Set<String> currentDefineArgs = new HashSet<>(macroArgs);
			currentDefineArgs.addAll(macroDefaultArgs.keySet());
//			}

			String body = consumeUntilEndDirective("#enddef", itor, currentDefineArgs, pathUri);
			var def = new MacroDef(macroName, body, macroArgs, macroDefaultArgs);

//			if (expandMacro) {
				currentDefineArgs.clear(); // clear arg context
//			}

			// Extra stuff
			def.setDocs(doc);
			def.setDeprecated(isDeprecated);
			def.setDeprecationLevel(depreLevel);
			def.setDeprecationRemovalVersion(removalVersion);
			def.setDeprecationMessage(depreMessage);

			debugPrint(() -> "defining macro " + def.coloredName());
			defines.addMacro(macroName, def, directiveStart.beginLine(), pathUri);
		} else if (directiveHeader.head().equals("#ifdef")) {
			// TODO complain if ifdef does not exactly has one arg (macroname)
			if (defines.hasMacro(directiveArgs.getFirst())) {
				skipElse = true;
			} else {
				// skip upto #else or #endif
				skipUntilEndDirective2("#else", "#endif", itor, pathUri);
				skipElse = false;
			}
		} else if (directiveHeader.head().equals("#ifndef")) {
			// TODO complain if ifndef does not exactly has one arg (macroname)
			if (defines.hasMacro(directiveArgs.getFirst())) {
				// skip upto #else or #endif
				skipUntilEndDirective2("#else", "#endif", itor, pathUri);
				skipElse = false;
			} else {
				skipElse = true;
			}
		} else if (directiveHeader.head().equals("#else")) {
			if (skipElse) {
				skipUntilEndDirective("#endif", itor, pathUri);
				skipElse = false;
			}
		} else if (directiveHeader.head().equals("#textdomain")) {
			// TODO might need scoped tracking, but not import right now
			String textdomain = directiveHeader.args().getFirst();
			debugPrint(() -> "Textdomain: " + textdomain);
		} else {
			skipTrailingWS = false;
		}
		
		if (skipTrailingWS) {
			// suppress empty whitespace & linebreaks after directive lines
			skip(itor, WHITESPACE);
			skip(itor, EOL);
		}
	}
	
	public String handleDocComment(Tokenizer itor) {
		skip(itor, EOL);

		skip(itor, WHITESPACE);

		var docBuff = new StringBuilder();
		while (peek(itor).isKind(COMMENT) && !peek(itor).isDirective()) {
			Token t = itor.next();
			if (t.isDirective()) break;
			docBuff.append(t.content().substring(1).trim());
			if (peek(itor).isKind(EOL)) {
				t = itor.next();
				docBuff.append(t.content());
			}
			skip(itor, WHITESPACE);
		}
		return docBuff.toString().trim();
	}
	
	private String consumeUntilEndDirective(String directiveName, Tokenizer itor, Set<String> args, String pathUri) {
		if (!itor.hasNext()) return "";
		
		StringBuilder body = new StringBuilder();
		Token t = itor.next();
		while (!t.isDirectiveName(directiveName, false)) {
			if (!itor.hasNext()) {
				final int line = t.beginLine();
				final int col = t.beginColumn();
				// terminated before define completed, error
				errorPrint(() ->
					"End directive "
					+ colorify(directiveName, directiveColor)
					+ " not found. Pos: " + position(line, col, pathUri));
				break;
			} else {
				// we don't want to expand any macro calls in body when consuming directive body,
				// but rather when that directive is called later on. (ie. lazy not eager behavior)
				tokenProcessor.processToken(itor, t, body, args, false);
				if (!itor.hasNext()) return body.toString();
				t = itor.next();
			}
		}
		return body.toString();
	}

	private void skipUntilEndDirective(String endDir, Tokenizer itor, String pathUri) {
		skipUntilEndDirective2(endDir, endDir, itor, pathUri);
	}

	private void skipUntilEndDirective2(String endDir1, String endDir2, Tokenizer itor, String pathUri) {
		if (!itor.hasNext()) return;
		Token t = itor.next();
		while (!(t.isDirectiveName(endDir1, false) || t.isDirectiveName(endDir2, false))) {
			if (!itor.hasNext()) {
				final int line = t.beginLine();
				final int col = t.beginColumn();
				// terminated before define completed, error
				errorPrint(() ->
					"End directives "
					+ colorify(endDir1, directiveColor)
					+ " or "
					+ colorify(endDir2, directiveColor)
					+ " not found. Pos: " + position(line, col, pathUri));
				return;
			} else {
				if (!itor.hasNext()) return;
				t = itor.next();
			}
		}
		return;
	}
	
	private record DirectiveHeader(String head, List<String> args) {
		// processDirectiveNameAndArgs
		public static DirectiveHeader parse(Token token, String pathStr) {
			if (!token.isDirective()) {
				final int line = token.beginLine();
				final int col = token.beginColumn();
				errorPrint(() -> "Unknown directive found at " + position(line, col, pathStr));
			}

			String content = token.content();
			int len = content.length();

			// find end of first word
			int i = 0;
			while (i < len && !isWS(content.charAt(i))) i++;
			String name = content.substring(0, i);

			// collect args
			List<String> argList = new ArrayList<>();
			while (i < len) {
				while (i < len && isWS(content.charAt(i))) i++; // skip whitespace
				int start = i;
				while (i < len && !isWS(content.charAt(i))) i++; // scan word
				if (start < i) argList.add(content.substring(start, i));
			}

			return new DirectiveHeader(name, argList);
		}
	}
}
