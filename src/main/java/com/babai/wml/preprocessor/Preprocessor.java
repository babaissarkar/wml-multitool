package com.babai.wml.preprocessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.parser.PathContext;
import com.babai.wml.tokenizer.Token;
import com.babai.wml.tokenizer.Tokenizer;
import com.babai.wml.utils.Tree;

import static com.babai.wml.utils.Colors.*;
import static com.babai.wml.utils.LogUtils.*;
import static com.babai.wml.cli.ANSIFormatter.colorify;
import static com.babai.wml.parser.ParseUtils.*;
import static com.babai.wml.tokenizer.Token.Kind.*;

public class Preprocessor implements TokenProcessor {
	private boolean expandMacro = true;
	
	private boolean listFilesInInfo = false;
	private Tree<String> filesTree = new Tree<>();
	
	private Map<String, String> fileExplanations = new HashMap<>();
	
	private PathContext context;
	private Path currentPath = Path.of(".");
	private String currentPathUri;
	
	// used to keep track of duplicate files
	private HashSet<Path> fileList = new HashSet<>();
	
	private DirectiveProcessor directiveProcessor;
	
	// connected macrocall refs
	private MacroDefTable defines;
	private MacroCallTable calls;

	// TODO(Warning): this doesn't respect scope: a macro can be unavailable for a short span until
	// it gets defined somewhere later. Perhaps each file can have a copy or something better.
	// Currently: one warning per file if it stays undefined in whole file.
	private HashSet<String> nonexistentMacros = new HashSet<>();
	private HashMap<String, String> unitTypes = new HashMap<>();

	// toplevel
	public Preprocessor(PathContext context) {
		this.context = context;
		this.defines = new MacroDefTable();
		this.calls = new MacroCallTable();
		
		this.directiveProcessor = new DirectiveProcessor(this);
	}

	// usually for child processes
	public Preprocessor(PathContext context, MacroDefTable defines) {
		this.context = context;
		this.defines = defines;
		this.calls = new MacroCallTable(); // TODO check if this needs same treatment as defines
		
		this.directiveProcessor = new DirectiveProcessor(this);
	}
	
	// toplevel
	public String preprocess(Path path) {
		var out = expandMacro ? new StringBuilder() : null;
		filesTree = new Tree<>();
		preprocess(path, out);
		return out != null ? out.toString() : "";
	}

	// Can handle both file or folder
	private void preprocess(Path path, StringBuilder out) {
		if (Files.isDirectory(path)) {
			long start = System.nanoTime();
			
			if (listFilesInInfo) {
				filesTree.add(filesTree.isEmpty() ? context.relativize(path) : path.getFileName().toString());
				filesTree.descend();
			} else {
				debugPrint(() -> "Including directory: " + colorify(path.toString(), filePathColor));
			}
			
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
			
			if (listFilesInInfo) {
				filesTree.ascend();
				
				long end = System.nanoTime();
				filesTree.update(filesTree.current() + " " + (end - start) / 1_000 + " us");
			}
		} else {
			preprocessFile(path, out);
		}
	}

	public void preprocessFile(Path path, StringBuilder buff) {
		long start = System.nanoTime();
		
		int prevMacroCount = this.defines.size();
		
		boolean skip = fileList.contains(path); 
		if (!skip) {
			fileList.add(path);
		} else {
			debugPrint(() -> "Skipping duplicate: " + colorify(this.currentPathUri, filePathColor));
			return;
		}
		
		var oldPath = this.currentPath;
		var oldPathUri = this.currentPathUri;
		
		this.currentPath = path;
		this.currentPathUri = path.toUri().toString();

		if (listFilesInInfo) {
			filesTree.add(filesTree.isEmpty() ? context.relativize(path) : path.getFileName().toString());
			filesTree.descend();
		} else {
			debugPrint(() -> "Preprocessing: " + colorify(this.currentPathUri, filePathColor));
		}

		try {
			preprocessContent(Files.readString(path), buff);
			
			int newMacroCount = this.defines.size() - prevMacroCount;
			
			Supplier<String> logMsg = () -> {
				String msg = "Preprocessed %s" + (newMacroCount > 0 ? ": " + newMacroCount + " macros" : "");
				String coloredPath = colorify(context.relativize(path), filePathColor);
				return msg.formatted(coloredPath);
			};
			debugPrint(logMsg);
			
			this.currentPath = oldPath;
			this.currentPathUri = oldPathUri;
		} catch (IOException e) {
			errorPrint(() -> "Cannot find " + path + ", skipping.");
		}
		
		for (var ut : Tokenizer.getUnitTypes()) {
			unitTypes.put(ut, path.toUri().toString());
		}
		Tokenizer.clearUnitTypes();
		
		if (listFilesInInfo) {
			filesTree.ascend();
		
			if (!skip) {
				long end = System.nanoTime();
				filesTree.update(filesTree.current() + " " + (end - start) / 1_000 + " us");
			} else {
				filesTree.update(filesTree.current() + " (duplicate)");
			}
		}
		
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
		var itor = new Tokenizer(content);

		skip(itor, EOL);

		skip(itor, WHITESPACE);

		if (peek(itor).isDirectiveName("#textdomain", true)) {
			Token t = itor.next();
			directiveProcessor.handleDirective(t, itor, defines, currentPathUri);
		}

		fileExplanations.put(currentPathUri, directiveProcessor.handleDocComment(itor));

		while (itor.hasNext()) {
			Token t = itor.next();
			processToken(itor, t, buff, Set.of(), true);
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

	private String preprocessFragment(String fragment, Set<String> args) {
		if (!hasMacroBlock(fragment)) return fragment;
		var buff = new StringBuilder();
		var itor = new Tokenizer(fragment);
		while (itor.hasNext()) {
			Token t = itor.next();
			boolean expand = !args.contains(t.content());
			processToken(itor, t, buff, args, expand);
		}
		return buff.toString();
	}

	@Override
	public void processToken(Tokenizer itor, Token t, StringBuilder buff, Set<String> currentArgs, boolean expandMacro) {
		// add [campaign]define= definition, usually found in _main.cfg
		// TODO support line number, don't allow redefinition
		String mdef = Tokenizer.getMainDefine();
		if (mdef != null && !mdef.isEmpty() && !defines.hasMacro(mdef)) {
			defines.addMacro(mdef, new MacroDef(mdef, "true"), 0, currentPathUri);
		}
		
		if (t.isKind(COMMENT)) {
			if (t.isDirective()) {
				directiveProcessor.handleDirective(t, itor, defines, currentPathUri);
			}
		} else if (t.isKind(MACRO)) {
			// expand macro tokens
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
	private void expandMacro(Token macroCall, Set<String> possibleArgs, PathContext context, StringBuilder buff) {
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
				
				calls.add(mcall);
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
		
		if (listFilesInInfo) {
			filesTree.add("[Include] " + pathStr);
			filesTree.descend();
		} else {
			debugPrint(() -> {
				String coloredPath = colorify(pathStr, filePathColor);
				return "Including: " + coloredPath;
			});
		}

		preprocess(p, buff);
		
		if (listFilesInInfo) {
			filesTree.ascend();
		}
	}

	private void expandMacroCall(Token macroCall, Set<String> possibleArgs, StringBuilder buff) {
		
		final String content = macroCall.content();
		
		var parts = ParseUtils.splitQuoted(content);
		String macroName = parts.get(0);

		// ---------------------------------------

		MacroDef def = defines.getMacro(macroName);
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
			calls.add(mcall);

			debugPrint(() -> {
				String argsString = MacroDef.argsAsString2(args, defArgs);
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

	public void expandMacros(boolean expand) {
		this.expandMacro = expand;
	}

	public Map<String, String> getFileExplanations() {
		return fileExplanations;
	}
	
	public MacroDefTable getDefines() {
		return defines;
	}

	public void setDefines(MacroDefTable t) {
		this.defines = t;
	}
	
	public MacroCallTable getMacroCalls() {
		return calls;
	}
	
	public HashMap<String, String> getUnitTypes() {
		return unitTypes;
	}

	public void setListFilesInInfo(boolean listFilesInInfo) {
		this.listFilesInInfo = listFilesInInfo;
	}
	
	public Tree<String> getIncludeTree() {
		return filesTree;
	}
}
