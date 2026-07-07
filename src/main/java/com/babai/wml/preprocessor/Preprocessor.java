package com.babai.wml.preprocessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.parser.PathContext;
import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;
import com.babai.wml.utils.MacroTable;

import static com.babai.wml.utils.Colors.*;
import static com.babai.wml.utils.LogUtils.*;
import static com.babai.wml.cli.ANSIFormatter.colorify;
import static com.babai.wml.parser.ParseUtils.*;
import static com.babai.wml.tokenizer.Tokenizer.tokenize;
import static com.babai.wml.tokenizer.Token.Kind.*;

public class Preprocessor {
	private boolean skipElse = true;
	private boolean listFilesInInfo = false;
	private boolean expandMacro = true;
	
	private MacroTable defines;
	private PathContext context;
	
	private Path currentPath = Path.of(".");
	private String currentPathUri;
	
	private HashSet<String> currentDefineArgs = new HashSet<>();
	private HashMap<String, String> fileExplanations = new HashMap<>();
	
	// connected macrocall refs
	private HashMap<String, HashSet<MacroCall>> macroCallsByName = new HashMap<>();
	private HashMap<String, HashSet<MacroCall>> macroCallsByUri = new HashMap<>();

	// TODO(Warning): this doesn't respect scope: a macro can be unavailable for a short span until
	// it gets defined somewhere later. Perhaps each file can have a copy or something better.
	// Currently: one warning per file if it stays undefined in whole file.
	private HashSet<String> nonexistentMacros = new HashSet<>();
	private HashMap<String, String> unitTypes = new HashMap<>();

	// toplevel
	public Preprocessor(PathContext context) {
		this.context = context;
		this.defines = new MacroTable();
	}

	// usually for child processes
	public Preprocessor(PathContext context, MacroTable defines) {
		this.context = context;
		this.defines = defines;
	}

	public MacroTable getDefines() {
		return defines;
	}

	public void setDefines(MacroTable t) {
		this.defines = t;
	}
	
	public void clearMacroCalls() {
		macroCallsByName.clear();
		macroCallsByUri.clear();
	}
	
	public void clearMacroCallsByUri(String uri) {
		macroCallsByUri.remove(uri);
		macroCallsByName.forEach((k, v) -> v.removeIf(m -> m.uri().equals(uri)));
	}
	
	public HashSet<MacroCall> getMacroCallsByName(String name) {
		return macroCallsByName.getOrDefault(name, new HashSet<>());
	}
	
	public HashSet<MacroCall> getMacroCallsByUri(String uri) {
		return macroCallsByUri.getOrDefault(uri, new HashSet<>());
	}
	
	public HashMap<String, String> getUnitTypes() {
		return unitTypes;
	}

	public HashMap<String, String> getFileExplanations() {
		return fileExplanations;
	}

	public void setListFilesInInfo(boolean listFilesInInfo) {
		this.listFilesInInfo = listFilesInInfo;
	}
	
	public String preprocess(Path path) {
		var out = expandMacro ? new StringBuilder() : null;
		preprocess(path, out);
		return out != null ? out.toString() : "";
	}

	// Can handle both file or folder
	private void preprocess(Path path, StringBuilder out) {
		if (Files.isDirectory(path)) {
			debugPrint(() -> "Including directory: " + colorify(path.toString(), filePathColor));
			
			// _initial.cfg
			Path initial = path.resolve("_initial.cfg");
			if (Files.exists(initial)) {
				preprocessFile(initial, out);
			}
			
			Path main = path.resolve("_main.cfg");
			if (Files.exists(main)) {
				preprocessFile(main, out);
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
						.forEach(p -> preprocess(p, out));
				} catch (IOException e) {
					errorPrint(() -> "Cannot find " + path + ", skipping.");
				}
			}
			
			// _final.cfg
			Path fin = path.resolve("_final.cfg");
			if (Files.exists(fin)) {
				preprocessFile(fin, out);
			}
		} else {
			preprocessFile(path, out);
		}
	}

	public void preprocessFile(Path path, StringBuilder buff) {
		int prevMacroCount = this.defines.size();
		var oldPath = this.currentPath;
		var oldPathUri = this.currentPathUri;
		
		this.currentPath = path;
		this.currentPathUri = path.toUri().toString();

		debugPrint(() -> "Preprocessing: " + colorify(this.currentPathUri, filePathColor));

		try {
			preprocessContent(Files.readString(path), buff);
			
			int newMacroCount = this.defines.size() - prevMacroCount;

			Supplier<String> logMsg = () -> {
				String msg = "Preprocessed %s" + (newMacroCount > 0 ? ": " + newMacroCount + " macros" : "");
				String coloredPath = colorify(context.relativize(path), filePathColor);
				return msg.formatted(coloredPath);
			};
			
			if (listFilesInInfo) {
				infoPrint(logMsg);
			} else {
				debugPrint(logMsg);
			}
			
			this.currentPath = oldPath;
			this.currentPathUri = oldPathUri;
		} catch (IOException e) {
			errorPrint(() -> "Cannot find " + path + ", skipping.");
		}
		
		for (var ut : Tokenizer.getUnitTypes()) {
			unitTypes.put(ut, path.toUri().toString());
		}
		Tokenizer.clearUnitTypes();
		
		nonexistentMacros.forEach(k -> warningPrint(() -> "Undefined macro " + colorify(k, RED) + " in " + currentPathUri));
	}
	
	public String preprocessString(String content) throws IOException {
		var buff = new StringBuilder();
		preprocessContent(content, buff);
		
		nonexistentMacros.forEach(k -> warningPrint(() -> "Undefined macro " + colorify(k, RED)));
		return buff.toString();
	}

	// Can only deal with a string
	private void preprocessContent(String content, StringBuilder buff) throws IOException {
		var itor = tokenize(content).listIterator();
		
		// add [campaign]define= definition, usually found in _main.cfg
		// TODO support line number, don't allow redefinition
		String mdef = Tokenizer.getMainDefine();
		if (!mdef.isEmpty()) {
			defines.addMacro(mdef, new Definition(mdef, "true"), 0, currentPathUri);
		}

		skip(itor, EOL);

		skip(itor, WHITESPACE);

		String textdomain;
		if (peek(itor).isDirectiveName("#textdomain", true)) {
			Token t = itor.next();
			var directiveHeader = DirectiveHeader.parse(t, currentPathUri);
			textdomain = directiveHeader.args().getFirst();
			debugPrint(() -> "Textdomain: " + textdomain);
		}

		fileExplanations.put(currentPathUri, handleDocComment(itor));

		while (itor.hasNext()) {
			Token t = itor.next();
			processToken(itor, t, buff, currentDefineArgs, true);
		}
	}

	private boolean hasMacroBlock(String content) {
		int len = content.length();
		boolean sawOpen = false;
		for (int i = 0; i < len; i++) {
			char c = content.charAt(i);
			if (c == '{') {
				sawOpen = true;
			} else if (c == '}' && sawOpen) {
				return true;
			}
		}
		return false;
	}

	private String preprocessFragment(String fragment, HashSet<String> args) {
		if (!hasMacroBlock(fragment)) return fragment;
		try {
			var buff = new StringBuilder();
			var itor = tokenize(fragment).listIterator();
			while (itor.hasNext()) {
				Token t = itor.next();
				boolean expand = !args.contains(t.content());
				processToken(itor, t, buff, args, expand);
			}
			return buff.toString();
		} catch (IOException e) {
			return fragment; // shouldn't happen
		}
	}

	private String handleDocComment(ListIterator<Token> itor) {
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

	private void processToken(ListIterator<Token> itor, Token t, StringBuilder buff, HashSet<String> currentArgs, boolean expandMacro) {
		if (t.isKind(COMMENT)) {
			if (t.isDirective()) {
				handleDirective(t, itor, currentPathUri);
			}
		} else if (t.isKind(MACRO)) {
			// exapnd macro tokens
			if (expandMacro) {
				expandMacro(t, currentArgs, context, buff);
			} else {
				t.raw(buff);
			}
		} else if (expandMacro && t.isNotKind(ANGLE_QUOTED) && t.nested()) {
			// expand embedded macro block in other tokens
			String content = t.content();
			String nestedSubst = preprocessFragment(content, currentArgs);
			if (nestedSubst.equals(content)) { // nth to subst, return raw
				t.raw(buff);
			} else {
				buff.append(nestedSubst);
			}
		} else {
			t.raw(buff);
		}
	}

	private String consumeUntilEndDirective(String directiveName, ListIterator<Token> itor) {
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
					+ " not found. Pos: " + position(line, col, currentPathUri));
				break;
			} else {
				// we don't want to expand any macro calls in body when consuming directive body,
				// but rather when that directive is called later on. (ie. lazy not eager behavior)
				processToken(itor, t, body, currentDefineArgs, false);
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
				final int line = t.beginLine();
				final int col = t.beginColumn();
				// terminated before define completed, error
				errorPrint(() ->
					"End directives "
					+ colorify(endDir1, directiveColor)
					+ " or "
					+ colorify(endDir2, directiveColor)
					+ " not found. Pos: " + position(line, col, currentPathUri));
				return;
			} else {
				if (!itor.hasNext()) return;
				t = itor.next();
			}
		}
		return;
	}

	private void handleDirective(Token directiveStart, ListIterator<Token> itor, String pathUri) {
		boolean skipTrailingWS = true;
		var directiveHeader = DirectiveHeader.parse(directiveStart, currentPathUri);
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
				var deprecationHeader = DirectiveHeader.parse(t, currentPathUri);
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
				String defArgName = DirectiveHeader.parse(t, currentPathUri).args().getFirst(); // arg NAME

				skip(itor, EOL);

				macroDefaultArgs.put(defArgName, consumeUntilEndDirective("#endarg", itor));

				skip(itor, EOL, WHITESPACE);
			}

			// Body
			// Collect args in context, used in processToken macroExpansion
			if (expandMacro) {
				currentDefineArgs.clear();
				currentDefineArgs.addAll(macroArgs);
				macroDefaultArgs.forEach((k, v) -> currentDefineArgs.add(k));
			}

			String body = consumeUntilEndDirective("#enddef", itor);
			var def = new Definition(macroName, body, macroArgs, macroDefaultArgs);

				currentDefineArgs.clear(); // clear arg context

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
				skipUntilEndDirective2("#else", "#endif", itor);
				skipElse = false;
			}
		} else if (directiveHeader.head().equals("#ifndef")) {
			// TODO complain if ifndef does not exactly has one arg (macroname)
			if (defines.hasMacro(directiveArgs.getFirst())) {
				// skip upto #else or #endif
				skipUntilEndDirective2("#else", "#endif", itor);
				skipElse = false;
			} else {
				skipElse = true;
			}
		} else if (directiveHeader.head().equals("#else")) {
			if (skipElse) {
				skipUntilEndDirective("#endif", itor);
				skipElse = false;
			}
		} else {
			skipTrailingWS = false;
		}
		
		if (skipTrailingWS) {
			// suppress empty whitespace & linebreaks after directive lines
			skip(itor, WHITESPACE);
			skip(itor, EOL);
		}
	}

	private boolean isPath(String str) {
		boolean hasSlash = false;
		for (int i = 0; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '/') hasSlash = true;
			else if (Character.isWhitespace(c)) return false;
		}
		return hasSlash;
	}

	// TODO This might need to be recursive, like after expansion
	// if macro exists after expansion, expand again and so on until no macro calls remain.
	private void expandMacro(Token macroCall, HashSet<String> possibleArgs, PathContext context, StringBuilder buff) {
		if (isPath(macroCall.content())) {
			// TODO possibleArgs should be zero in this case, otherwise error.
			handleInclusion(macroCall.content(), context, buff);
		} else if (expandMacro) {
			expandMacroCall(macroCall, possibleArgs, buff);
		} else {
			var info = ParseUtils.parseMacroCall("{" + macroCall.content() + "}");
			var mdef = defines.getMacro(info.first());
			if (mdef != null) {
				var mcall = new MacroCall(
						info.first(),
						macroCall.beginLine()-1,
						macroCall.beginColumn()-1,
						info.second(),
						currentPathUri);
				macroCallsByName.computeIfAbsent(info.first(), k -> new HashSet<>()).add(mcall);
				macroCallsByUri.computeIfAbsent(currentPathUri, k -> new HashSet<>()).add(mcall);
			}
			
			if (buff != null) {
				macroCall.raw(buff);
			}
		}
	}

	private void handleInclusion(String pathStr, PathContext context, StringBuilder buff) {
		Path p = context.resolveFileInclusion(pathStr, currentPath);

		if (!Files.exists(p)) {
			warningPrint(() -> colorify(p.toString(), filePathColor) + " does not exist");
			return;
		}

		Supplier<String> logMsg = () -> {
			String coloredPath = colorify(pathStr, filePathColor);	
			return "Including: " + coloredPath;
		};
		
		if (listFilesInInfo) {
			infoPrint(logMsg);
		} else {
			debugPrint(logMsg);
		}

		preprocess(p, buff);
	}

	private void expandMacroCall(Token macroCall, HashSet<String> possibleArgs, StringBuilder buff) {
		
		final String content = macroCall.content();
		
		var parts = ParseUtils.splitQuoted(content);
		String macroName = parts.get(0);

		// ---------------------------------------

		Definition def = defines.getMacro(macroName);
		if (def != null) {
			nonexistentMacros.remove(macroName);
			
			List<MacroArg> args = new ArrayList<>();
			List<Integer> argPos = new ArrayList<>();
			HashMap<String, String> defArgs = new HashMap<>();

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
					String argStr = preprocessFragment(str, possibleArgs);
					// Properly quote multiline args
					if (argStr.indexOf('\n') >= 0) {
						argStr = "(" + argStr + ")";
					}
					args.add(new MacroArg(argStr, argLine, argStart, argEnd));
					argPos.add(argStart);
				} else {
					// Optional keyword args
					int eqPos = str.indexOf('=');
					if (eqPos != -1) {
						String key = str.substring(0, eqPos);
						if (def.getDefArgs().containsKey(key)) {
							defArgs.put(key, stripMatchingQuotes(str.substring(eqPos + 1)));
						} else {
							// TODO error: invalid defarg passed
						}
					} else {
						//TODO error: more defargs passed than needed
					}
				}
			}
			
			var mcall = new MacroCall(
					macroName,
					macroCall.beginLine()-1,
					macroCall.beginColumn()-1,
					argPos,
					currentPathUri);
			macroCallsByName.computeIfAbsent(macroName, k -> new HashSet<>()).add(mcall);
			macroCallsByUri.computeIfAbsent(currentPathUri, k -> new HashSet<>()).add(mcall);

			debugPrint(() -> {
				String argsString = Definition.argsAsString2(args, defArgs);
				return "expanding macro " + def.coloredName()
					+ (!argsString.isEmpty() ? " with " + colorify(argsString, macroArgColor) : "");
			});

			try {
				String out = def.getValue();

				// substitute args
				if ((def.getArgCount() > 0 ||  def.getDefArgCount() > 0) && hasMacroBlock(out)) {
					out = def.expand(args, defArgs);
				}
				// substitute macros
				out = preprocessFragment(out, def.getAllArgs());
				buff.append(out);
			} catch(IllegalArgumentException e) {
				errorPrint(() ->
					"Error expanding macro " + def.coloredName()
					+ " in "
					+ colorify(currentPathUri, filePathColor)
					+ ": " + e.getMessage());
				macroCall.raw(buff);
			}

		// Nested arg processing
		} else if (possibleArgs.contains(macroName)) {
			// FIXME: do nothing for now. may need checks later.
			macroCall.raw(buff);
		} else {
			int prev = buff.length();
			if (parts.size() == 1) {
				handleInclusion(macroName, context, buff);
			}
			
			// did we include anything? buff size should change.
			if (buff.length() - prev == 0) {
				// no change : handleInclusion failed
				nonexistentMacros.add(macroName);
				macroCall.raw(buff);
			}
		}
	}

	private String stripMatchingQuotes(String argVal) {
		// Keyword args are parsed from raw macro text and may carry wrapper quotes
		// (e.g. KEY="value"). Keep inner content and drop only a matching outer pair.
		if (argVal == null) return null;
		int len = argVal.length();
		if (len >= 2 && argVal.charAt(0) == '"' && argVal.charAt(len-1) == '"') {
			return argVal.substring(1, len - 1);
		}
		return argVal;
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

	public void expandMacros(boolean expand) {
		this.expandMacro = expand;
	}
}
