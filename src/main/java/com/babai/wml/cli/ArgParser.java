package com.babai.wml.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "wml", version = "WML Multitool and LSP, version 2.0.0", mixinStandardHelpOptions = true)
public class ArgParser {

	@Option(names = {"-server", "-s", "--server"}, description = "Run in LSP server mode.")
	public boolean startLSPServer = false;

	@Option(
		names = {"-datadir", "--datadir"},
		description = "Absolute path to Wesnoth's data directory. Can also be specified via environment variable WESNOTH_DATA.",
		defaultValue="${env:WESNOTH_DATA}")
	public Path dataPath;

	@Option(
		names = {"-userdatadir", "--userdatadir"},
		description = "Absolute path to Wesnoth's userdata directory. Can also be specified via environment variable WESNOTH_USERDATA.",
		defaultValue="${env:WESNOTH_USERDATA}")
	public Path userDataPath;
	
	@Option(names = {"-i", "-include", "--include"}, arity = "1", description = "File/folders to be preprocessed before to collect macro definitions")
	public List<Path> includes = new ArrayList<>();

	@Option(names = {"-define", "-d", "--define"}, arity = "2", description = "Define macro: -define NAME BODY", paramLabel = "NAME BODY", hideParamSyntax = true)
	public List<String> definesList = new ArrayList<>();
	
	@Parameters(index = "0", arity = "0..1", paramLabel = "INPUT", description = "Path to the main input file or folder (default: stdin). Not needed in LSP mode (-s).")
	public Path inputPath;

	@Option(names = {"-o", "-output", "--output"}, description = "Path to a file to write output to (default: stdout)")
	public Path outputPath;

	// -------------- LOGGING ----------------
	public Level logLevel = Level.INFO;

	@Option(names = {"-color", "--color"}, arity="1", description = "Toggle color in log messages (default: true)", paramLabel="<'true'|'false'>")
	public boolean enableColors = true;
	
	@Option(names = {"--list-files", "-l"}, description = "List preprocessed file names in Info log (stderr)")
	public boolean listFilesInInfo = false;
	
	@Option(names = {"-log-parse", "-log-p", "--log-parse"}, description = "Print all parser logs (= -log-level debug)")
	public void setLogParse(boolean on) { if (on) logLevel = Level.FINER; }

	@Option(names = {"-warn-parse", "-warn-p", "--warn-parse"}, description = "Print parser warnings only (= -log-level warn)")
	public void setWarnParse(boolean on) { if (on) logLevel = Level.WARNING; }

	@Option(names = {"-log-level", "--log-level"}, description = "Set log level to following values severe|warn|info|debug|off", paramLabel = "<'severe'|'warn'|'info'|'debug'|'off'>")
	public void setLogLevel(String level) {
		logLevel = switch (level) {
		case "severe" -> Level.SEVERE;
		case "warn"   -> Level.WARNING;
		case "info"   -> Level.INFO;
		case "debug"  -> Level.FINER;
		case "off"    -> Level.OFF;
		default       -> Level.INFO;
		};
	}
	
	@Option(names = { "-parse", "--parse" }, arity="1", description = "Toggle parsing preprocessed output (default: true). Disables WML Queries and Binary Path detection if disabled.")
	public boolean parse = true;

	// -------------------- DATA EXTRACTION ---------------------------
	
	public boolean extractUnitTypeData = false;
	public Path unitTypeOutPath;
	
	// TODO unimplemented in code yet
	@Option(names = {"-extract-unit-type", "-eut", "--extract-unit-type"}, description = "Extract unit type data to CSV at given path", paramLabel = "<outputPath>", hidden = true)
	public void setExtractUnitTypeDataPath(String path) {
		extractUnitTypeData = true;
		unitTypeOutPath = Path.of(path);
	}
	
	public boolean generateMacroRef = false;
	public Path macroRefPath;

	@Option(names = {"-generate-macro-ref", "-gmr", "--generate-macro-ref"}, description = "Generate HTML macro reference file", paramLabel = "<outputPath>")
	public void setMacroRefPath(String path) {
		generateMacroRef = true;
		macroRefPath = Path.of(path);
	}
	
	@Option(names = {"-df", "-definitions", "--definitions"}, description = "List all macro definitions. Output written to stdout or file pointed by -o.")
	public boolean definitions = false;
	
	@Option(names = {"-q", "-query", "--query"}, description = "XPath-style WML query. Any tag/key matching this will be printed to stdout or file pointed by -o.")
	public List<String> queries = new ArrayList<>();
	
	// --------------- help + version -------------
	
	@Option(names = {"-h", "-help", "--help", "-?"}, usageHelp = true, description = "Print this help")
	private boolean helpRequested;
	
	@Option(names = {"-v", "-version", "--version"}, versionHelp = true, description = "Print version information")
	private boolean versionRequested;

	public void parseArgs(String[] args) {
		CommandLine cmd = new CommandLine(this);
		cmd.setUsageHelpAutoWidth(true);
		try {
			CommandLine.ParseResult result = cmd.parseArgs(args);
			if (result.isUsageHelpRequested()) {
				cmd.usage(System.out);
				System.exit(0);
			} else if (result.isVersionHelpRequested()) {
				cmd.printVersionHelp(System.out);
				System.exit(0);
			}
		} catch (CommandLine.ParameterException ex) {
			System.err.println(ex.getMessage());
			cmd.usage(System.err);
		}
	}
}
