package com.babai.wml.lsp;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import com.babai.wml.parser.PathContext;
import com.babai.wml.preprocessor.Definition;
import com.babai.wml.preprocessor.MacroCall;
import com.babai.wml.preprocessor.Preprocessor;
import com.babai.wml.tokenizer.Tokenizer;
import com.babai.wml.utils.FS;
import com.babai.wml.utils.LogUtils;
import com.babai.wml.utils.MacroTable;

import static org.eclipse.lsp4j.launch.LSPLauncher.createServerLauncher;

public class WMLLanguageServer implements LanguageServer, LanguageClientAware, TextDocumentService {
	private final static Set<String> animKeys = Set.of(
		"image", "image_diagonal", "halo", // Progressive Strings
		"halo_x", "halo_y", "x", "y", "directional_x", "directional_y", "layer", // Progressive Integers
		"alpha", "offset", "blend_ratio", "submerge" // Progressive Reals
	);
	
	private final static Set<String> unitTypeKeys = Set.of(
		"type", "recruit", "advances_to", "extra_recruit"
	);
	
	public LanguageClient client;
	
	private PathContext pathContext;
	String workspaceUri = null;

	private MacroTable defines;
	private HashMap<String, String> unitTypes = new HashMap<>();
	private Set<Path> binaryPaths = new HashSet<>();
	private List<Path> includePaths = new ArrayList<>();
	private List<CompletionItem> macroCompletions = new ArrayList<>();
	private List<CompletionItem> keywords = new ArrayList<>();
	private List<CompletionItem> tags = new ArrayList<>();
	private Properties tagLinks = new Properties();
	
	private Preprocessor p;

	private WMLLanguageServer(MacroTable predefines, PathContext ctxt, List<Path> includePaths) {
		this.pathContext = ctxt;
		this.includePaths = includePaths;
		this.defines = predefines;

		initDirectivesList();

		initTagRefLinks();
	}
	
	public static void initServer(MacroTable predefines, Path dataPath, Path userDataPath, List<Path> includes) {
		LogUtils.setLogLevel(Level.OFF);

		var server = new WMLLanguageServer(
			predefines,
			new PathContext(dataPath, userDataPath),
			includes);

		// Initialize a simple JSON-RPC connection over stdin/stdout
		// Most clients support this, unlike connection over TCP
		Launcher<LanguageClient> launcher = createServerLauncher(server, System.in, System.out);
		LanguageClient client = launcher.getRemoteProxy();

		server.connect(client);
		launcher.startListening();
	}
	
	private void initTagRefLinks() {
		// Reference links for tags
		try {
			tagLinks.load(getClass().getResourceAsStream("/taglinks.properties"));
			for (var tag : tagLinks.keySet()) {
				CompletionItem item = new CompletionItem(tag.toString());
				item.setInsertText(item.getLabel() + "]$0[/" + item.getLabel());
				item.setKind(CompletionItemKind.Snippet);
				item.setInsertTextFormat(InsertTextFormat.Snippet);
				tags.add(item);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	//TODO maybe load from a file?
	private void initDirectivesList() {
		// Directives, this List never changes so created here once
		BiFunction<String, String, CompletionItem> make = (label, doc) -> {
			CompletionItem item = new CompletionItem(label);
			item.setDocumentation(doc);
			item.setInsertText(item.getLabel() + " ");
			item.setKind(CompletionItemKind.Keyword);
			keywords.add(item);
			return item;
		};

		make.apply("define", "Define a macro");
		make.apply("enddef", "End macro definition");
		make.apply("ifdef", "Do if macro defined");
		make.apply("ifndef", "Do if macro not defined");
		make.apply("ifhave", "Do if file exists");
		make.apply("ifver", "Do if wesnoth version matches condition");
		make.apply("endif", "End if directives block");
		make.apply("arg", "Start optional argument in macro definition");
		make.apply("endarg", "End optional argument in macro definition");
		make.apply("textdomain", "Define Textdomain");
	}

	public void connect(LanguageClient client) {
		this.client = client;
	}

	@Override
	@SuppressWarnings("deprecation")
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		var capabilities = new ServerCapabilities();
		capabilities.setDefinitionProvider(true);
		capabilities.setReferencesProvider(true);
		capabilities.setHoverProvider(true);
		capabilities.setInlayHintProvider(true);
		capabilities.setDocumentSymbolProvider(true);
		capabilities.setCompletionProvider(new CompletionOptions(true, List.of("#", "{", "/", "[", "=", ",")));

		var syncOptions = new TextDocumentSyncOptions();
		syncOptions.setOpenClose(true);
		syncOptions.setChange(TextDocumentSyncKind.Full);
		syncOptions.setSave(true);
		capabilities.setTextDocumentSync(syncOptions);

		var wfOptions = new WorkspaceFoldersOptions();
		wfOptions.setSupported(true);
		wfOptions.setChangeNotifications(false);
		var workspaceCaps = new WorkspaceServerCapabilities();
		workspaceCaps.setWorkspaceFolders(wfOptions);
		capabilities.setWorkspace(workspaceCaps);

		var result = new InitializeResult(capabilities);

		if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
			// 1. Multi-root workspaces (modern)
			workspaceUri = params.getWorkspaceFolders().get(0).getUri();
		} else if (params.getRootUri() != null) {
			// 2. Single-root URI
			workspaceUri = params.getRootUri();
		} else if (params.getRootPath() != null) {
			// 3. Old deprecated rootPath
			workspaceUri = Path.of(params.getRootPath()).toUri().toString();
		}
		
		if (pathContext.userDataPath() == null) {
			Path upath = Path.of(URI.create(workspaceUri));
			while (upath != null && !upath.endsWith("data")) upath = upath.getParent();
			if (upath != null) {
				this.pathContext = new PathContext(pathContext.dataPath(), upath.getParent());
			}
		}

		// Send a "ready" message after startup
		showLSPMessage("WML LSP Server started at: URI=" + workspaceUri);

		initParserForLSP(workspaceUri);

		return CompletableFuture.completedFuture(result);
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
		definition(DefinitionParams params)
	{
		String word = null;
		try {
			word = getWordAtPosition(params.getTextDocument().getUri(), params.getPosition());
		} catch (IOException e) {
			showLSPMessage("Can't find word under cursor!");
		}
		
		if (word == null) return CompletableFuture.completedFuture(null);
		
		// Attempt resolution as macro
		if (defines != null && defines.hasMacro(word)) {
			String targetURI = defines.getUri(word);
			int targetLine = defines.getLineNum(word);
			var range = new Range(new Position(targetLine, 0), new Position(targetLine, 1));
			var loc = new Location(targetURI, range);
			return CompletableFuture.completedFuture(Either.forLeft(List.of(loc)));
		}
			
		// Attempt resolution as file
		Path p = checkWesnothPath(word, params.getTextDocument().getUri());
			
		if (p != null && Files.exists(p) && Files.isRegularFile(p)) {
			String targetURI = p.toUri().toString();
			var range = new Range(new Position(0, 0), new Position(0, 1));
			var loc = new Location(targetURI, range);
			return CompletableFuture.completedFuture(Either.forLeft(List.of(loc)));
		}
		// TODO when folder -> check for _main.cfg -> open that if exists?

		// Attempt as Unit Type
		if (unitTypes.containsKey(word)) {
			String targetURI = unitTypes.get(word);
			var range = new Range(new Position(0, 0), new Position(0, 1));
			var loc = new Location(targetURI, range);
			return CompletableFuture.completedFuture(Either.forLeft(List.of(loc)));
		}

		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
		String word = null;
		String uri = params.getTextDocument().getUri();
		try {
			word = getWordAtPosition(uri, params.getPosition());
		} catch (IOException e) {
			showLSPMessage("Can't find word under cursor!");
		}

		// Only macro names are supported as reference targets for now.
		if (word == null || defines == null || !defines.hasMacro(word)) {
			return CompletableFuture.completedFuture(null);
		}

		final String macroName = word;
		List<Location> locations = new ArrayList<>();

		for (MacroCall call : p.getMacroCallsByName(macroName)) {
			locations.add(new Location(call.uri(), macroNameRange(call)));
		}

		boolean includeDeclaration = params.getContext() != null
				&& params.getContext().isIncludeDeclaration();
		if (includeDeclaration) {
			String defUri = defines.getUri(macroName);
			int defLine = defines.getLineNum(macroName);
			if (defUri != null) {
				var defRange = new Range(new Position(defLine, 0), new Position(defLine, 1));
				locations.add(new Location(defUri, defRange));
			}
		}

		return CompletableFuture.completedFuture(locations);
	}

	/**
	 * Range of the macro name itself within a call, e.g. the NAME span in
	 * "{NAME arg1 arg2}". call.startChar() points at the opening '{', and by
	 * WML convention the name immediately follows it with no whitespace.
	 */
	private static Range macroNameRange(MacroCall call) {
		int nameStart = call.startChar() + 1; // skip '{'
		int nameEnd = nameStart + call.name().length();
		return new Range(
				new Position(call.startLine(), nameStart),
				new Position(call.startLine(), nameEnd));
	}

	private Path checkWesnothPath(String word, String currUri) {
		// FIXME gets triggers by web URLs
		if (!word.contains("://") && (word.contains("/") || word.contains("~"))) {
			// Wesnoth Paths
			// if tilde is in front, it's a userdata path
			int pos = word.indexOf('~');
			int bktStart = word.indexOf('[');
			// IPF, drop
			if (word.charAt(0) != '~' && pos != -1 && bktStart == -1) {
				word = word.substring(0, pos);
			} else if (bktStart >= 0) {
				// AnimationWML
				int bktEnd = word.indexOf(']');
				if (bktEnd >= bktStart) {
					// Crude: replace with first number after '[',
					// then delete upto ']'
					String bktPart = word.substring(bktStart, bktEnd+1);
					word = word.replace(bktPart, String.valueOf(word.charAt(bktStart+1)));
				}
			}
			
			// drop ':' and anything after it
			pos = word.indexOf(':');
			if (pos != -1) {
				word = word.substring(0, pos);
			}
			
			try {
				return pathContext.resolve(word, Path.of(new URI(currUri)), binaryPaths);
			} catch (URISyntaxException e) {
				return null;
			}
		} else {
			return null;
		}
	}

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		var content = new MarkupContent();
		Path p = null;
		try {
			String uri = params.getTextDocument().getUri();
			String word = getWordAtPosition(uri, params.getPosition());
			if (word == null || word.isEmpty()) return CompletableFuture.completedFuture(null);
			
			if (word.charAt(0) == '[' && word.charAt(word.length()-1) == ']') {
				// Tags
				int startAt = (word.charAt(1) == '/' || word.charAt(1) == '+') ? 2 : 1;
				// drop [ and ], and optional /
				String searchWord = word.substring(startAt, word.length() - 1);
				String link = tagLinks.getProperty(searchWord);
				content.setKind("markdown");
				content.setValue("Tag: **" + word + "**" +  "\n\n"
					+ (link != null ? "Reference: " + link : "")
				);
			} else if (defines != null && defines.hasMacro(word)) {
				// Macro calls
				Definition def = defines.getMacro(word);
				content.setKind("markdown");
				content.setValue("**" + def.name() + "**\n\n" + def.getDocs());
			} else if ((p = checkWesnothPath(word, params.getTextDocument().getUri())) != null) {
				if (Files.exists(p)) {
					content.setKind("markdown");
					String uriStr = p.toUri().toString();
					if (FS.getAssetType(uriStr).equals("images")) {
						content.setValue("![Image](" + uriStr + ")");
					} else {
						content.setValue("Resolves to: " + p);
					}
				} else {
					p = pathContext.resolveFileInclusion(word, Path.of(URI.create(uri)));
					content.setKind("plaintext");
					if (Files.exists(p)) {
						content.setValue("Resolves to: " + p);
					} else {
						content.setValue("Non-existant path: " + word);
					}
				}
			} else if (unitTypes.containsKey(word)) {
				content.setKind("markdown");
				content.setValue("**Unit Type:** [" + word + "](" + unitTypes.get(word) + ")");
			} else {
				return CompletableFuture.completedFuture(null);
			}
		} catch (IOException e) {
			showLSPMessage("IO Error while resolving: " + e.getMessage());
			return CompletableFuture.completedFuture(null);
		}
	
		Hover hover = new Hover(content);
		return CompletableFuture.completedFuture(hover);
	}

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		if (params.getContext() == null) return CompletableFuture.completedFuture(null);
		
		String triggerChar = params.getContext().getTriggerCharacter();
		CompletionTriggerKind triggerKind = params.getContext().getTriggerKind();
		List<CompletionItem> items = new ArrayList<>();

		// Directives
		if (triggerKind == CompletionTriggerKind.Invoked
				|| triggerChar.equals("#")) {
			items.addAll(keywords);
			return CompletableFuture.completedFuture(Either.forLeft(items));
		}

		if (triggerKind == CompletionTriggerKind.Invoked
				|| triggerChar.equals("{")) {
			items.addAll(macroCompletions);
			return CompletableFuture.completedFuture(Either.forLeft(items));
		}

		if (triggerKind == CompletionTriggerKind.Invoked
				|| triggerChar.equals("[")) {
			items.addAll(tags);
			return CompletableFuture.completedFuture(Either.forLeft(items));
		}

		if (triggerKind == CompletionTriggerKind.Invoked
				|| triggerChar.equals("=") || triggerChar.equals(","))
		{
			for (var type : unitTypes.keySet()) {
				CompletionItem item = new CompletionItem(type);
				item.setInsertText(item.getLabel());
				item.setKind(CompletionItemKind.Constant);
				items.add(item);
			}
			return CompletableFuture.completedFuture(Either.forLeft(items));
		}

		if (triggerChar != null && triggerChar.equals("/")) {
			try {
				items.addAll(listAll(Path.of(URI.create(workspaceUri)), "", params.getPosition()));
				return CompletableFuture.completedFuture(Either.forLeft(items));
			} catch (IOException e) {
				e.printStackTrace();
				return CompletableFuture.completedFuture(null);
			}
		}

		return CompletableFuture.completedFuture(null);
	}	

	@Override
	public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {

		String docUri = params.getTextDocument().getUri();
		docUri = Path.of(URI.create(docUri)).toUri().toString();
		var emptyRange = new Range(
				new Position(0, 0),
				new Position(0, 0));
		List<Either<SymbolInformation, DocumentSymbol>> symbolList = new ArrayList<>();

		// Macro Definitions
		var matches = defines.macrosByUri(docUri);
		if (matches != null && !matches.isEmpty()) {
			List<DocumentSymbol> listDef = new ArrayList<>();
			DocumentSymbol mdefRoot = new DocumentSymbol();
			mdefRoot.setName("Macro Definitions");
			mdefRoot.setKind(SymbolKind.Namespace);
			mdefRoot.setRange(emptyRange);
			mdefRoot.setSelectionRange(emptyRange);
			for (var targetName : matches) {
				int targetLine = defines.getLineNum(targetName);
				var range = new Range(new Position(targetLine, 0), new Position(targetLine, 1));
				DocumentSymbol sym = new DocumentSymbol();
				sym.setName(targetName);
				sym.setKind(SymbolKind.Function);
				sym.setRange(range);
				sym.setSelectionRange(range);
				listDef.add(sym);
			}
			mdefRoot.setChildren(listDef);
			symbolList.add(Either.forRight(mdefRoot));
		}

		return CompletableFuture.completedFuture(symbolList);
	}

	@Override
	public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
		String uri = params.getTextDocument().getUri();
		uri = Path.of(URI.create(uri)).toUri().toString(); // normalize uri
		var viewRange = params.getRange();
		List<InlayHint> hints = new ArrayList<>();

		for (MacroCall call : p.getMacroCallsByUri(uri)) {
			// skip calls outside the visible range
			if (call.startLine() > viewRange.getEnd().getLine()) continue;
			if (call.startLine() < viewRange.getStart().getLine()) continue;

			String macro = call.name();
			var def = defines.getMacro(macro);
			if (def == null) continue;
			if (def.getArgCount() <= 0) continue;
			
			String defTargetUri = defines.getUri(macro);
			int targetLine = defines.getLineNum(macro);
			var range = new Range(new Position(targetLine, 0), new Position(targetLine, 1));
			var loc = new Location(defTargetUri, range);

			for (int i = 0; i < def.getArgs().size(); i++) {

				var paramName = def.getArgs().get(i);

				var part = new InlayHintLabelPart(paramName + "=");
				part.setLocation(loc); // ctrl+click jumps to #define

				var hint = new InlayHint();
				hint.setPosition(new Position(call.startLine(), call.startChar() + call.positions(i)));
				hint.setLabel(Either.forRight(List.of(part)));
				hint.setKind(InlayHintKind.Parameter);
				hint.setPaddingRight(true);
				hints.add(hint);
			}
		}

		return CompletableFuture.completedFuture(hints);
	}

	private static CompletionItem toCompletionItem(String relPath, Position cursor) {
		CompletionItem item = new CompletionItem(relPath);
		item.setKind(CompletionItemKind.File); // You could detect folder vs file if you want
//		item.setInsertText(relPath);           // Simple snippet = just the path
//		 Replace the trigger "/" with the path text
		Range replaceRange = new Range(
				new Position(cursor.getLine(), cursor.getCharacter() - 1),
				cursor
				);
		item.setTextEdit(Either.forLeft(new TextEdit(replaceRange, relPath)));
		return item;
	}

	private static List<CompletionItem> listAll(Path baseDir, String prefix, Position cursor) throws IOException {
		try (Stream<Path> stream = Files.walk(baseDir)) {
			return stream
					.filter(p -> !p.equals(baseDir))
					.map(baseDir::relativize)
					.map(p -> p.toString().replace('\\', '/'))
					.filter(rel -> rel.startsWith(prefix))
					.filter(rel -> !rel.startsWith("."))
					.map(rel -> toCompletionItem(rel, cursor))
					.collect(Collectors.toList());
		}
	}

	@Override
	public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
		// Add more info if needed, or just return the item as-is
		return CompletableFuture.completedFuture(item);
	}

	@Override
	public void exit() {
		System.exit(0);
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return this;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return new WorkspaceService() {
			@Override
			public void didChangeConfiguration(DidChangeConfigurationParams params) {
			}

			@Override
			public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
			}
		};
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		parseFile(params.getTextDocument().getUri());
		
		client.refreshInlayHints();
		client.refreshDiagnostics();
	}

	@Override
	public void didClose(DidCloseTextDocumentParams arg0) {
		// TODO Auto-generated method stub

	}

	// FIXME still buggy. if you change a file and save, you need to relaunch editor for the diagnostic change to take effect.
	// putting parsing in didSave doesn't work, either
	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		parseFile(params.getTextDocument().getUri());
		
		client.refreshInlayHints();
		client.refreshDiagnostics();
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		parseFile(params.getTextDocument().getUri());
		
		client.refreshInlayHints();
		client.refreshDiagnostics();
	}

	private void initParserForLSP(String rootUri) {
		Tokenizer.enableExtraction(true);
		
		p = new Preprocessor(pathContext, defines); // 'defines' supposed to contain only predefined macros
		p.expandMacros(false); // we don't need full expansion for LSP mode
		
		includePaths.forEach(p::preprocess);
		
		if (rootUri != null) {
			parseFile(rootUri);
		}

		showLSPMessage("Parsed, " + defines.size() + " macros and " + unitTypes.size() + " unittypes defined.");
	}

	private void parseFile(String uri) {
		p.clearMacroCallsByUri(uri);
		defines.removeMacroByUri(uri);
		unitTypes.entrySet().removeIf(e -> e.getValue().equals(uri));
		
		p.preprocess(Path.of(URI.create(uri)));

		binaryPaths = Tokenizer.getBinaryPaths();
		defines = p.getDefines();
		unitTypes = p.getUnitTypes();

		macroCompletions.clear();
		for (var def : defines.macros().values()) {
			CompletionItem item = new CompletionItem();
			item.setLabel(def.name());
			item.setKind(CompletionItemKind.Method);
			String docs = def.getDocs();
			item.setDocumentation(def.name() + (docs != null && !docs.isEmpty() ? ("\n" + docs) : ""));
			item.setInsertText(item.getLabel());
			item.setInsertTextFormat(InsertTextFormat.Snippet); //
			macroCompletions.add(item);
		}
	}


	private static final Predicate<Character> UNIT_EXTRA =
		c -> Character.isJavaIdentifierPart(c) || Character.isWhitespace(c) || c == '_' || c == '-';

	private static final Predicate<Character> ANIM_EXTRA =
		c -> Character.isJavaIdentifierPart(c) ||
			c == ':' || c == '+' || c == '-' || c == '/' || c == '~' || c == '.' ||
			c == '[' || c == ']' || c == ',';

	private static final Predicate<Character> DEFAULT_EXTRA =
		c -> Character.isJavaIdentifierPart(c) ||
			c == ':' || c == '+' || c == '-' || c == '/' || c == '~' || c == '.' ||
			c == '[' || c == ']';

	/**
	 * Checks whether line[start, end) equals any element of keys, without
	 * allocating a substring for the comparison.
	 */
	private static boolean containsKey(Set<String> keys, String line, int start, int end) {
		int keyLen = end - start;
		for (String k : keys) {
			if (k.length() == keyLen && line.regionMatches(start, k, 0, keyLen)) {
				return true;
			}
		}
		return false;
	}

	/** Returns the word under cursor in the file pointed by URI */
	private static String getWordAtPosition(String uri, Position pos) throws IOException {
		byte[] data = Files.readAllBytes(Path.of(URI.create(uri)));
		int len = data.length;
		// Scan bytes to manually find the line
		int lineNum = pos.getLine();
		int lineStart = -1;
		int currentLine = 0;
		for (int i = 0; i < len; i++) {
			if (currentLine == lineNum) {
				lineStart = i;
				break;
			}
			if (data[i] == '\n') currentLine++;
		}
		if (lineStart < 0)
			return null; // lineNum negative or out of range
		int lineEnd = lineStart;
		while (lineEnd < len && data[lineEnd] != '\n') lineEnd++;
		if (lineEnd > lineStart && data[lineEnd - 1] == '\r') lineEnd--; // strip CRLF
		String line = new String(data, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8);

		int charIndex = pos.getCharacter();
		if (charIndex < 0)
			charIndex = 0;
		if (charIndex >= line.length())
			charIndex = line.length() - 1;

		Predicate<Character> isValid;
		int eqlPos = line.indexOf('=');
		if (eqlPos >= 0) {
			// Trim key bounds in-place instead of line.substring(0, eqlPos).strip()
			int keyStart = 0;
			int keyEnd = eqlPos;
			while (keyStart < keyEnd && Character.isWhitespace(line.charAt(keyStart))) keyStart++;
			while (keyEnd > keyStart && Character.isWhitespace(line.charAt(keyEnd - 1))) keyEnd--;

			if (containsKey(unitTypeKeys, line, keyStart, keyEnd)) {
				isValid = UNIT_EXTRA;
			} else if (containsKey(animKeys, line, keyStart, keyEnd)) {
				isValid = ANIM_EXTRA;
			} else {
				isValid = DEFAULT_EXTRA;
			}
		} else {
			isValid = DEFAULT_EXTRA;
		}

		// If cursor is on whitespace, move back one char
		if (charIndex > 0 && !isValid.test(line.charAt(charIndex))) {
			charIndex--;
		}
		int start = charIndex;
		int end = charIndex;
		while (start > 0 && isValid.test(line.charAt(start - 1)))
			start--;
		while (end < line.length() && isValid.test(line.charAt(end)))
			end++;
		if (start >= end)
			return null;

		// Trim whitespace in-place instead of line.substring(start, end).strip()
		int trimStart = start;
		int trimEnd = end;
		while (trimStart < trimEnd && Character.isWhitespace(line.charAt(trimStart))) trimStart++;
		while (trimEnd > trimStart && Character.isWhitespace(line.charAt(trimEnd - 1))) trimEnd--;
		return line.substring(trimStart, trimEnd);
	}
	
	private void showLSPMessage(String m) {
		if (client != null) {
			MessageParams msg = new MessageParams();
			msg.setType(MessageType.Info);
			msg.setMessage(m);
			client.showMessage(msg);
		}
	}
}