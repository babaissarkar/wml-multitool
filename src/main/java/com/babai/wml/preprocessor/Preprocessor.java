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
import java.util.ListIterator;
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

public class Preprocessor {
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
		
		var itor = tokenize(reader).listIterator();
		
		skip(itor, Token.Kind.EOL);

		skip(itor, Token.Kind.WHITESPACE);
		
		String textdomain;
		if (peek(itor).isDirectiveName("textdomain", true)) {
			Token t = itor.next();
			var directiveHeader = DirectiveHeader.parse(t, currentPath.toString());
			textdomain = directiveHeader.args()[0];
			debugPrint("Textdomain: " + textdomain);
		}
		
		fileExplanations.put(currentPath.toUri().toString(), handleDocComment(itor));
		
		while (itor.hasNext()) {
			Token t = itor.next();
			buff.append(processToken(itor, t, currentDefineArgs, true));
		}
		
		for (var pair : nonexistentMacros) {
			warningPrint(pair.second() + " undefined macro " + colorify(pair.first(), RED));
		}
		
		return buff.toString();
	}
	
	private String preprocessFragment(String fragment, List<String> args) {
		try {
			var buff = new StringBuilder();
			var itor = tokenize(fragment).listIterator();
			while (itor.hasNext()) {
				Token t = itor.next();
				boolean expand = !args.contains(t.content());
				buff.append(processToken(itor, t, args, expand));
			}
			return buff.toString();
		} catch (IOException e) {
			return fragment; // shouldn't happen with StringReader
		}
	}
	
	private String handleDocComment(ListIterator<Token> itor) {
		skip(itor, Token.Kind.EOL);

		skip(itor, Token.Kind.WHITESPACE);

		var docBuff = new StringBuilder();
		while (peek(itor).kind() == Token.Kind.COMMENT && !peek(itor).isDirective()) {
			Token t = itor.next();
			if (t.isDirective()) break;
			docBuff.append(t.content().trim());
			if (peek(itor).kind() == Token.Kind.EOL) {
				t = itor.next();
				docBuff.append(t.content());
			}
			skip(itor, Token.Kind.WHITESPACE);
		}
		return docBuff.toString().trim();
	}

	private String processToken(ListIterator<Token> itor, Token t, List<String> currentArgs, boolean expandMacro) {
		String content = t.content();
		if (t.kind() == Token.Kind.COMMENT) {
			if (t.isDirective()) {
				handleDirective(t, itor, currentPath.toUri().toString());
				// suppress empty whitespace & linebreaks after directive lines
				skip(itor, Token.Kind.WHITESPACE);
				skip(itor, Token.Kind.EOL);
			}
			
			return "";
		} else if (t.kind() == Token.Kind.MACRO) {
			// exapnd macro tokens
			if (expandMacro) {
				return expandMacro(t, currentArgs, context);
			} else {
				return t.raw();
			}
		} else if (t.kind() != Token.Kind.ANGLE_QUOTED && content.contains("{") && content.contains("}")) {
			// expand embedded macro block in other tokens
			String nestedSubst = preprocessFragment(content, currentArgs);
			if (nestedSubst.equals(content)) { // nth to subst, return raw
				return t.raw();
			} else {
				return Token.getRaw(nestedSubst, t.kind());
			}
		} else if (t.kind() == Token.Kind.TAG) {
			// Embed token location via annotation to help with parse error stacktraces
			String[] nameAndLoc = t.content().split("@", 2);
			return "[" + nameAndLoc[0]
					+ "@" + context.relativize(currentPath) + ":" + t.beginLine()
					+ (nameAndLoc.length > 1 ? "@" + nameAndLoc[1] : "") + "]";
		} else {
			return t.raw();
		}
	}
	
	private String consumeUntilEndDirective(String directiveName, ListIterator<Token> itor) {
		StringBuilder body = new StringBuilder();
		if (!itor.hasNext()) return "";
		Token t = itor.next();
		while (!t.isDirectiveName(directiveName, false)) {
			if (!itor.hasNext()) {
				// terminated before define completed, error
				errorPrint("End directive "
						+ colorify(directiveName, directiveColor)
						+ " not found. Pos: " + position(t, currentPath.toString()));
				break;
			} else {
				// we don't want to expand any macro calls in body when consuming directive body,
				// but rather when that directive is called later on. (ie. lazy not eager behavior)
				body.append(processToken(itor, t, currentDefineArgs, false));
				if (!itor.hasNext()) return body.toString();
				t = itor.next();
			}
		}
		return body.toString();
	}
	
	private void skipUntilEndDirective(String endDir, ListIterator<Token> itor) {
		skipUntilEndDirective2(endDir, endDir, itor);
	}
	
	private void skipUntilEndDirective2(String endDir1, String endDir2, ListIterator<Token> itor) {
		if (!itor.hasNext()) return;
		Token t = itor.next();
		while (!(t.isDirectiveName(endDir1, false) || t.isDirectiveName(endDir2, false))) {
			if (!itor.hasNext()) {
				// terminated before define completed, error
				errorPrint("End directives "
						+ colorify(endDir1, directiveColor)
						+ " or "
						+ colorify(endDir2, directiveColor)
						+ " not found. Pos: " + position(t, currentPath.toString()));
				return;
			} else {
				if (!itor.hasNext()) return;
				t = itor.next();
			}
		}
		return;
	}
	
	private void handleDirective(Token directiveStart, ListIterator<Token> itor, String pathUri) {
		var directiveHeader = DirectiveHeader.parse(directiveStart, currentPath.toString());
		var directiveArgs = directiveHeader.args();

		if (directiveHeader.head().equals("define")) {
			// Macro name
			String macroName = directiveArgs[0];
			List<String> macroArgs = Arrays.asList(directiveArgs).subList(1, directiveArgs.length);
			
			skip(itor, Token.Kind.EOL, Token.Kind.WHITESPACE);
			
			// Macro deprecation messages
			boolean isDeprecated = false;
			int depreLevel = 0;
			String removalVersion = "";
			String depreMessage = "";
			while (peek(itor).isDirectiveName("deprecated", true)) {
				debugPrint("Deprecated macro: " + macroName);
				Token t = itor.next();
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
				
				skip(itor, Token.Kind.EOL, Token.Kind.WHITESPACE);
			}

			String doc = handleDocComment(itor);
			
			skip(itor, Token.Kind.EOL, Token.Kind.WHITESPACE);
			
			// defargs processing
			var macroDefaultArgs = new LinkedHashMap<String, String>();
			while (peek(itor).isDirectiveName("arg", true)) {
				Token t = itor.next();
				String defArgName = DirectiveHeader.parse(t, currentPath.toString()).args()[0]; // arg NAME
				
				skip(itor, Token.Kind.EOL);
				
				macroDefaultArgs.put(defArgName, consumeUntilEndDirective("endarg", itor));
				
				skip(itor, Token.Kind.EOL, Token.Kind.WHITESPACE);
			}
			
			// Body
			// Collect args in context, used in processToken macroExpansion
			currentDefineArgs.clear();
			currentDefineArgs.addAll(macroArgs);
			macroDefaultArgs.forEach((k, v) -> currentDefineArgs.add(k));
			
			var def = new Definition(macroName, consumeUntilEndDirective("enddef", itor), macroArgs, macroDefaultArgs);
			
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
				skipUntilEndDirective2("else", "endif", itor);
				skipElse = false;
			}
		} else if (directiveHeader.head().equals("ifndef")) {
			// TODO complain if ifndef does not exactly has one arg (macroname)
			boolean hasMacro = !defines.getRows("Name", directiveArgs[0]).isEmpty();
			if (hasMacro) {
				// skip upto #else or #endif
				skipUntilEndDirective2("else", "endif", itor);
				skipElse = false;
			} else {
				skipElse = true;
			}
		} else if (directiveHeader.head().equals("else")) {
			if (skipElse) {
				skipUntilEndDirective("endif", itor);
				skipElse = false;
			}
		}
	}
	
	private final static Pattern wspattern = Pattern.compile("\\s+");
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
						String[] keyVal = str.split("=", 2);
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
