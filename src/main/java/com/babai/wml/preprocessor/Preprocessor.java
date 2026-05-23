package com.babai.wml.preprocessor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.parser.PathContext;
import com.babai.wml.tokenizer.Token;
import com.babai.wml.utils.Table;

import static com.babai.wml.utils.Colors.*;
import static com.babai.wml.utils.LogUtils.*;
import static com.babai.wml.cli.ANSIFormatter.colorify;
import static com.babai.wml.parser.ParseUtils.*;
import static com.babai.wml.tokenizer.Tokenizer.tokenize;
import static com.babai.wml.tokenizer.Token.Kind.*;

public class Preprocessor {
	private final static Pattern wspattern = Pattern.compile("\\s+");
	private final static Pattern atpattern = Pattern.compile("@");
	private final static Pattern eqlpattern = Pattern.compile("=");

	private boolean skipElse = true;
	private Table defines;
	private PathContext context;
	private Path currentPath = Path.of(".");
	private List<String> currentDefineArgs = new ArrayList<>();
	private List<MacroCall> macroCalls;
	private HashMap<String, String> fileExplanations = new LinkedHashMap<>();

	// format: macroName, positionString
	private HashSet<Pair<String, String>> nonexistentMacros = new HashSet<>();
	private boolean listFilesInInfo = false;

	// toplevel
	public Preprocessor(PathContext context) {
		this.context = context;
		this.defines =  Table.ofWithIndices(
			new Class<?>[]{Integer.class, String.class, String.class, Definition.class},
			new String[]{"Line", "URI", "Name", "Definition"},
			2  // index by Name column
		);
		this.macroCalls = new ArrayList<>();
	}

	// usually for child processes
	public Preprocessor(PathContext context, Table defines) {
		this.context = context;
		this.defines = defines;
		this.macroCalls = new ArrayList<>();
	}

	public Table getDefines() {
		return defines;
	}

	public void setDefines(Table t) {
		this.defines = t;
	}

	public List<MacroCall> getMacroCalls() {
		return macroCalls;
	}

	public HashMap<String, String> getFileExplanations() {
		return fileExplanations;
	}

	public void setListFilesInInfo(boolean listFilesInInfo) {
		this.listFilesInInfo = listFilesInInfo;
	}

	// Can handle both file or folder
	// TODO _initial & _final.cfg
	public String preprocess(Path path) {
		StringBuilder out = new StringBuilder();
		String coloredPath = colorify(path.toString(), filePathColor);
		if (Files.isDirectory(path)) {
			debugPrint("Including directory: " + coloredPath);
			Path main = path.resolve("_main.cfg");
			if (Files.exists(main)) {
				out.append(preprocessFile(main));
			} else {
				Predicate<? super Path> filter = entry ->
					Files.isDirectory(entry)
					|| entry.getFileName().toString().endsWith(".cfg");

				try (var stream = Files.list(path)) {
					stream
						.filter(filter)
						.sorted(Comparator
								.comparingInt((Path p) -> Files.isDirectory(p) ? 1 : 0)
								.thenComparing(Comparator.naturalOrder()))
						.forEach(p -> out.append(preprocess(p)));
				} catch (IOException e) {
					errorPrint("Cannot find " + path + ", skipping.");
				}
			}
		} else {
			out.append(preprocessFile(path));
		}

		// linebreak so outputs from different files are separated
		return out.toString() + "\n";
	}

	public String preprocessFile(Path path) {
		int prevMacroCount = this.defines.rowCount();
		String coloredPath = colorify(path.toAbsolutePath().toString(), filePathColor);
		this.currentPath = path;

		debugPrint("Preprocessing: " + coloredPath);

		String out = "";
		try {
			out = preprocessContent(Files.newBufferedReader(path));
			int newMacroCount = this.defines.rowCount() - prevMacroCount;

			String msg = "Preprocessed %s" + (newMacroCount > 0 ? ": " + newMacroCount + " macros" : "");
			if (listFilesInInfo) {
				coloredPath = colorify(context.relativize(path), filePathColor);
				infoPrint(msg.formatted(coloredPath));
			} else {
				debugPrint(msg.formatted(coloredPath));
			}
		} catch (IOException e) {
			errorPrint("Cannot find " + path + ", skipping.");
		}
		return out;
	}

	// Can only deal with a file
	public String preprocessContent(Reader reader) throws IOException {
		var buff = new StringBuilder();

		Token[] tokens = tokenize(reader).toArray(Token[]::new);
		int[] idx = {0};

		skip(tokens, idx, EOL);

		skip(tokens, idx, WHITESPACE);

		String textdomain;
		if (peek(tokens, idx).isDirectiveName("textdomain", true)) {
			Token t = next(tokens, idx);
			var directiveHeader = DirectiveHeader.parse(t, currentPath.toString());
			textdomain = directiveHeader.args()[0];
			debugPrint("Textdomain: " + textdomain);
		}

		fileExplanations.put(currentPath.toUri().toString(), handleDocComment(tokens, idx));

		for (; idx[0] < tokens.length; ) {
			Token t = next(tokens, idx);
			buff.append(processToken(tokens, idx, t, currentDefineArgs, true));
		}

		for (var pair : nonexistentMacros) {
			warningPrint(pair.second() + " undefined macro " + colorify(pair.first(), RED));
		}

		return buff.toString();
	}

	private String preprocessFragment(String fragment, List<String> args) {
		try {
			var buff = new StringBuilder();
			Token[] tokens = tokenize(fragment).toArray(Token[]::new);
			for (int i = 0; i < tokens.length; ) {
				Token t = tokens[i++];
				int[] idx = {i};
				boolean expand = !args.contains(t.content());
				buff.append(processToken(tokens, idx, t, args, expand));
				i = idx[0];
			}
			return buff.toString();
		} catch (IOException e) {
			return fragment; // shouldn't happen with StringReader
		}
	}

	private String handleDocComment(Token[] tokens, int[] idx) {
		skip(tokens, idx, EOL);

		skip(tokens, idx, WHITESPACE);

		var docBuff = new StringBuilder();
		while (peek(tokens, idx).isKind(COMMENT) && !peek(tokens, idx).isDirective()) {
			Token t = next(tokens, idx);
			if (t.isDirective()) break;
			docBuff.append(t.content().trim());
			if (peek(tokens, idx).isKind(EOL)) {
				t = next(tokens, idx);
				docBuff.append(t.content());
			}
			skip(tokens, idx, WHITESPACE);
		}
		return docBuff.toString().trim();
	}

	private String processToken(Token[] tokens, int[] idx, Token t, List<String> currentArgs, boolean expandMacro) {
		String content = t.content();
		if (t.isKind(COMMENT)) {
			if (t.isDirective()) {
				handleDirective(t, tokens, idx, currentPath.toUri().toString());
				// suppress empty whitespace & linebreaks after directive lines
				skip(tokens, idx, WHITESPACE);
				skip(tokens, idx, EOL);
			}

			return "";
		} else if (t.isKind(MACRO)) {
			// exapnd macro tokens
			if (expandMacro) {
				return expandMacro(t, currentArgs, context);
			} else {
				return t.raw();
			}
		} else if (t.isNotKind(ANGLE_QUOTED) && content.contains("{") && content.contains("}")) {
			// expand embedded macro block in other tokens
			String nestedSubst = preprocessFragment(content, currentArgs);
			if (nestedSubst.equals(content)) { // nth to subst, return raw
				return t.raw();
			} else {
				return Token.getRaw(nestedSubst, t.kind());
			}
		} else {
			return t.raw();
		}
	}

	private String consumeUntilEndDirective(String directiveName, Token[] tokens, int[] idx) {
		StringBuilder body = new StringBuilder();
		if (idx[0] >= tokens.length) return "";
		Token t = next(tokens, idx);
		while (!t.isDirectiveName(directiveName, false)) {
			if (idx[0] >= tokens.length) {
				// terminated before define completed, error
				errorPrint("End directive "
						+ colorify(directiveName, directiveColor)
						+ " not found. Pos: " + position(t, currentPath.toString()));
				break;
			} else {
				// we don't want to expand any macro calls in body when consuming directive body,
				// but rather when that directive is called later on. (ie. lazy not eager behavior)
				body.append(processToken(tokens, idx, t, currentDefineArgs, false));
				if (idx[0] >= tokens.length) return body.toString();
				t = next(tokens, idx);
			}
		}
		return body.toString();
	}

	private void skipUntilEndDirective(String endDir, Token[] tokens, int[] idx) {
		skipUntilEndDirective2(endDir, endDir, tokens, idx);
	}

	private void skipUntilEndDirective2(String endDir1, String endDir2, Token[] tokens, int[] idx) {
		if (idx[0] >= tokens.length) return;
		Token t = next(tokens, idx);
		while (!(t.isDirectiveName(endDir1, false) || t.isDirectiveName(endDir2, false))) {
			if (idx[0] >= tokens.length) {
				// terminated before define completed, error
				errorPrint("End directives "
						+ colorify(endDir1, directiveColor)
						+ " or "
						+ colorify(endDir2, directiveColor)
						+ " not found. Pos: " + position(t, currentPath.toString()));
				return;
			} else {
				if (idx[0] >= tokens.length) return;
				t = next(tokens, idx);
			}
		}
		return;
	}

	private void handleDirective(Token directiveStart, Token[] tokens, int[] idx, String pathUri) {
		var directiveHeader = DirectiveHeader.parse(directiveStart, currentPath.toString());
		var directiveArgs = directiveHeader.args();

		if (directiveHeader.head().equals("define")) {
			// Macro name
			String macroName = directiveArgs[0];
			List<String> macroArgs = Arrays.asList(directiveArgs).subList(1, directiveArgs.length);

			skip(tokens, idx, EOL, WHITESPACE);

			// Macro deprecation messages
			boolean isDeprecated = false;
			int depreLevel = 0;
			String removalVersion = "";
			String depreMessage = "";
			while (peek(tokens, idx).isDirectiveName("deprecated", true)) {
				debugPrint("Deprecated macro: " + macroName);
				Token t = next(tokens, idx);
				isDeprecated = true;
				var deprecationHeader = DirectiveHeader.parse(t, currentPath.toString());
				var depreArgs = deprecationHeader.args();
				depreLevel = Integer.parseInt(depreArgs[0]);
				if (depreLevel == 2 || depreLevel == 3) {
					if (depreArgs.length > 1) {
						removalVersion = depreArgs[1];
					}

					// Rest of args are actually the message in this case that got split
					// join back.
					if (depreArgs.length > 2) {
						depreMessage = String.join(" ", Arrays.copyOfRange(depreArgs, 2, depreArgs.length));
					}
				} else if (depreLevel == 1 || depreLevel == 4) {
					// Rest of args are actually the message in this case that got split
					// join back.
					if (depreArgs.length > 1) {
						depreMessage = String.join(" ", Arrays.copyOfRange(depreArgs, 1, depreArgs.length));
					}
				}

				skip(tokens, idx, EOL, WHITESPACE);
			}

			String doc = handleDocComment(tokens, idx);

			skip(tokens, idx, EOL, WHITESPACE);

			// defargs processing
			var macroDefaultArgs = new LinkedHashMap<String, String>();
			while (peek(tokens, idx).isDirectiveName("arg", true)) {
				Token t = next(tokens, idx);
				String defArgName = DirectiveHeader.parse(t, currentPath.toString()).args()[0]; // arg NAME

				skip(tokens, idx, EOL);

				macroDefaultArgs.put(defArgName, consumeUntilEndDirective("endarg", tokens, idx));

				skip(tokens, idx, EOL, WHITESPACE);
			}

			// Body
			// Collect args in context, used in processToken macroExpansion
			currentDefineArgs.clear();
			currentDefineArgs.addAll(macroArgs);
			macroDefaultArgs.forEach((k, v) -> currentDefineArgs.add(k));

			var def = new Definition(macroName, consumeUntilEndDirective("enddef", tokens, idx), macroArgs, macroDefaultArgs);

			currentDefineArgs.clear(); // clear arg context

			// Extra stuff
			def.setDocs(doc);
			def.setDeprecated(isDeprecated);
			def.setDeprecationLevel(depreLevel);
			def.setDeprecationRemovalVersion(removalVersion);
			def.setDeprecationMessage(depreMessage);

			debugPrint("defining macro " + def.coloredName());
			defines.addRow(directiveStart.beginLine(), pathUri, macroName, def);

		} else if (directiveHeader.head().equals("ifdef")) {
			// TODO complain if ifdef does not exactly has one arg (macroname)
			boolean hasMacro = !defines.getRows("Name", directiveArgs[0]).isEmpty();
			if (hasMacro) {
				skipElse = true;
			} else {
				// skip upto #else or #endif
				skipUntilEndDirective2("else", "endif", tokens, idx);
				skipElse = false;
			}
		} else if (directiveHeader.head().equals("ifndef")) {
			// TODO complain if ifndef does not exactly has one arg (macroname)
			boolean hasMacro = !defines.getRows("Name", directiveArgs[0]).isEmpty();
			if (hasMacro) {
				// skip upto #else or #endif
				skipUntilEndDirective2("else", "endif", tokens, idx);
				skipElse = false;
			} else {
				skipElse = true;
			}
		} else if (directiveHeader.head().equals("else")) {
			if (skipElse) {
				skipUntilEndDirective("endif", tokens, idx);
				skipElse = false;
			}
		}
	}

	private Token next(Token[] tokens, int[] idx) { return tokens[idx[0]++]; }
	private Token peek(Token[] tokens, int[] idx) { return idx[0] < tokens.length ? tokens[idx[0]] : Token.Empty; }
	private void skip(Token[] tokens, int[] idx, Token.Kind... kinds) {
		while (idx[0] < tokens.length && peek(tokens, idx).isKind(kinds)) idx[0]++;
	}

	private boolean isPath(String str) {
		return str.contains("/") && !wspattern.matcher(str).find();
	}

	// TODO This might need to be recursive, like after expansion
	// if macro exists after expansion, expand again and so on until no macro calls remain.
	private String expandMacro(Token macroCall, List<String> possibleArgs, PathContext context) {
		if (isPath(macroCall.content())) {
			// TODO possibleArgs should be zero in this case, otherwise error.
			return handleInclusion(macroCall, context);
		} else {
			return expandMacroCall(macroCall, possibleArgs);
		}
	}

	private String handleInclusion(Token macroCall, PathContext context) {
		Path p = context.resolve(macroCall.content(), currentPath);

		if (!Files.isDirectory(p) && !p.toString().endsWith(".cfg")) return "";

		String coloredPath = colorify(p.toString(), filePathColor);

		debugPrint("Trying to include: " + coloredPath);

		if (!Files.exists(p)) {
			warningPrint(coloredPath + " does not exist");
			return "";
		}

		String msg = "Including: %s";
		if (listFilesInInfo) {
			coloredPath = colorify(context.relativize(p), filePathColor);
			infoPrint(msg.formatted(coloredPath));
		} else {
			debugPrint(msg.formatted(coloredPath));
		}

		return preprocess(p);
	}

	private String expandMacroCall(Token macroCall, List<String> possibleArgs) {
		final String content = macroCall.content();
		var parts = ParseUtils.splitQuoted(content);
		String macroName = parts.get(0);
		List<MacroArg> args = new ArrayList<>();
		HashMap<String, String> defArgs = new LinkedHashMap<>();

		// ---------------------------------------

		List<Table.Row> rows = defines.getRows("Name", macroName);
		Definition def = null;
		if (!rows.isEmpty()) {
			nonexistentMacros.removeIf(m -> m.first().equals(macroName));
			def = (Definition) rows.get(0).getColumn("Definition").getValue();
		}

		if (def != null) {

			// Process macro call arguments
			int lastPos = 0;
			for (int i = 1; i < parts.size(); i++) {
				String str = parts.get(i);

				// Mandatory positional args
				if (i-1 < def.getArgCount()) {
					//FIXME multiline arguments, also this should be done in splitQuoted
					lastPos = content.indexOf(str, lastPos + 1);
					int argStart = macroCall.beginColumn() + lastPos;
					int argEnd = argStart + str.length();
					int argLine = macroCall.beginLine() - 1; //TODO args may start on a different line. why -1?
					String argStr = preprocessFragment(str, List.of());
					// Properly quote multiline args
					if (argStr.contains("\n")) {
						argStr = "(" + argStr + ")";
					}
					args.add(new MacroArg(argStr, argLine, argStart, argEnd));
				} else {
					// Optional keyword args
					if (str.contains("=")) {
						String[] keyVal = eqlpattern.split(str, 2);
						if (def.getDefArgs().containsKey(keyVal[0])) {
							//FIXME eliminate stripMatchingQuotes later
							//we want to pass the value verbatim, but this is dropping quotes
							//hint: multiple preprocessing passes can accidentally collapse
							// "" -> " in some case, gotta handle those carefully
							defArgs.put(keyVal[0], stripMatchingQuotes(keyVal[1]));
						} else {
							//TODO error: invalid defarg passed
						}
					} else {
						//TODO error: more defargs passed than needed
					}
				}
			}

			macroCalls.add(new MacroCall(
					macroName,
					macroCall.beginLine(),
					macroCall.endLine(),
					macroCall.beginColumn(),
					macroCall.endColumn(),
					args,
					currentPath.toUri().toString()));

			String argsString = Definition.argsAsString2(args, defArgs);
			debugPrint("expanding macro " + def.coloredName()
				+ (!argsString.isEmpty() ? " with " + colorify(argsString, macroArgColor) : ""));

			var argsList = new ArrayList<String>();
			argsList.addAll(def.getArgs());
			def.getDefArgs().keySet().forEach(k -> argsList.add(k));

			try {
				String out = def.getValue();

				// substitute args
				if (out.contains("{")) {
					out = def.expand(args, defArgs);
				}
				// substitute macros
				if (out.contains("{")) {
					out = preprocessFragment(out, argsList);
				}
				return out;
			} catch(IllegalArgumentException e) {
				errorPrint("Error expanding macro " + def.coloredName()
					+ " in "
					+ colorify(currentPath.toString(), filePathColor)
					+ ": " + e.getMessage());
				return macroCall.raw();
			}

		// Nested arg processing
		} else if (possibleArgs.contains(macroName)) {
			// FIXME: do nothing for now. may need checks later.
			return macroCall.raw();
		} else {
			nonexistentMacros.add(new Pair<>(macroName, position(macroCall, currentPath.toString())));
			return macroCall.raw();
		}
	}

	private String stripMatchingQuotes(String argVal) {
		// Keyword args are parsed from raw macro text and may carry wrapper quotes
		// (e.g. KEY="value"). Keep inner content and drop only a matching outer pair.
		if (argVal != null && argVal.length() >= 2 && argVal.startsWith("\"") && argVal.endsWith("\"")) {
			return argVal.substring(1, argVal.length() - 1);
		}
		return argVal;
	}

	private record Pair<F, S>(F first, S second) {};

	private record DirectiveHeader(String head, String[] args) {
		// processDirectiveNameAndArgs
		public static DirectiveHeader parse(Token token, String pathStr) {
			if (!token.isDirective()) {
				errorPrint("Unknown directive found at " + position(token, pathStr));
			}

			String[] parts = wspattern.split(token.content());
			String name = parts[0];
			String[] args = new String[parts.length - 1];
			for (int i = 1; i < parts.length; i++) {
				args[i-1] = parts[i];
			}

			return new DirectiveHeader(name, args);
		}
	}
}
