package com.babai.wml.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import com.babai.wml.utils.AIGenerated;

import static com.babai.wml.parser.ParseUtils.stripMatchingQuotes;

// Note: based on earlier work by me, Claude only add new options and
// made usage string dynamically generated.
@AIGenerated
public class ArgParser {

	// ── Server / mode ────────────────────────────────────────────────────────
	public boolean startLSPServer = false;
	public boolean fastMode       = false;

	// ── Paths ────────────────────────────────────────────────────────────────
	public Path        dataPath;
	public Path        userDataPath;
	public List<Path>  includes  = new ArrayList<>();
	public Path        inputPath;
	public Path        outputPath;
	public PrintStream out       = null;

	// ── Defines ──────────────────────────────────────────────────────────────
	public List<String> definesList = new ArrayList<>();

	// ── Logging ──────────────────────────────────────────────────────────────
	public Level   logLevel     = Level.INFO;
	public boolean enableColors = true;

	// ── Parsing ──────────────────────────────────────────────────────────────
	public boolean parse = true;

	// ── Data extraction / queries ─────────────────────────────────────────────
	public boolean      listFilesInInfo    = false;
	public boolean      extractUnitTypeData = false;
	public Path         unitTypeOutPath;
	public boolean      generateMacroRef   = false;
	public Path         macroRefPath;
	public boolean      definitions        = false;
	public List<String> queries            = new ArrayList<>();

	// ─────────────────────────────────────────────────────────────────────────

	private static final String VERSION = "WML Multitool and LSP, version 2.0.0";

	// Insertion-ordered map.
	// - Normal entry : key = flags string,        value = description
	// - Section header: key = "§" + section name, value = null
	private final LinkedHashMap<String, String> optMap = new LinkedHashMap<>();

	// ── Construction ──────────────────────────────────────────────────────────

	public ArgParser() {
		registerOptions();
		applyEnvDefaults();
	}

	private void registerOptions() {
		section("Server / mode");
		opt("-server, -s",                        "Start as WML LSP server");
		opt("-fastMode, -fm",                     "Fast mode: skip macro expansion & parsing. Auto-enabled for -df, -gmr, -s.");

		section("Paths");
		opt("-datadir <path>",                    "Absolute path to Wesnoth data dir (or env: WESNOTH_DATA)");
		opt("-userdatadir <path>",                "Absolute path to Wesnoth userdata dir (or env: WESNOTH_USERDATA)");
		opt("-i, -include <path>",                "Preprocess file/folder to collect macro definitions. Repeatable.");
		opt("-o, -output <path>",                 "Write output to file (default: stdout)");

		section("Defines");
		opt("-define, -d <NAME> <BODY>",          "Define macro before parsing. Repeatable.");

		section("Logging");
		opt("-log-parse, -log-p",                 "Parser debug logs (= -log-level debug)");
		opt("-warn-parse, -warn-p",               "Parser warnings only (= -log-level warn)");
		opt("-log-level <severe|warn|info|debug|off>", "Set log level explicitly");
		opt("-color <true|false>",                "Toggle ANSI color in logs (default: true)");

		section("Parsing");
		opt("-parse <true|false>",                "Toggle parsing preprocessed output (default: true)");

		section("Data extraction / queries");
		opt("-l, -list-files",                    "Print tree of preprocessed file names");
		opt("-df, -definitions",                  "List all macro definitions");
		opt("-q, -query <expr>",                  "XPath-style WML query. Repeatable.");
		opt("-eut, -extract-unit-type <path>",    "Extract unit type data to CSV");
		opt("-gmr, -generate-macro-ref <path>",   "Generate HTML macro reference");

		section("Help / version");
		opt("-h, -help, -?",                      "Print this help");
		opt("-v, -version",                       "Print version information");
	}

	/** Add a section header to the usage output. */
	private void section(String name) {
		optMap.put("§" + name, null);
	}

	/** Register one option with its description. */
	private void opt(String flags, String desc) {
		optMap.put(flags, desc);
	}

	/** Build the usage string dynamically from optMap. */
	private String buildUsage() {
		int colWidth = 0;
		for (Map.Entry<String, String> e : optMap.entrySet()) {
			if (e.getValue() != null && e.getKey().length() > colWidth) {
				colWidth = e.getKey().length();
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Usage: wml [OPTIONS] [INPUT]\n");
		sb.append("\nArguments:\n");
		sb.append("  INPUT   Path to the main input file or folder (not needed in LSP mode -s)\n");
		sb.append("\nOptions:");

		for (Map.Entry<String, String> e : optMap.entrySet()) {
			if (e.getValue() == null) {
				// Section header — strip the § sentinel
				sb.append("\n\n  ").append(e.getKey().substring(1)).append(":\n");
			} else {
				sb.append(String.format("    %-" + colWidth + "s   %s%n",
						e.getKey(), e.getValue()));
			}
		}
		return sb.toString();
	}

	// ── Env-var defaults ──────────────────────────────────────────────────────

	private void applyEnvDefaults() {
		String envData = System.getenv("WESNOTH_DATA");
		if (envData != null && !envData.isBlank()) {
			dataPath = Path.of(stripMatchingQuotes(envData));
		}
		String envUserData = System.getenv("WESNOTH_USERDATA");
		if (envUserData != null && !envUserData.isBlank()) {
			userDataPath = Path.of(stripMatchingQuotes(envUserData));
		}
	}

	// ── Parsing ───────────────────────────────────────────────────────────────

	public void parseArgs(String[] args) {
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];

			// Positional argument → inputPath
			if (!arg.startsWith("-") && !arg.startsWith("/")) {
				inputPath = Path.of(arg);
				continue;
			}

			if (arg.startsWith("--"))                     arg = arg.substring(2);
			else if (arg.startsWith("-") || arg.startsWith("/")) arg = arg.substring(1);

			switch (arg) {

			// ── Server / mode ─────────────────────────────────────────
			case "server", "s"    -> startLSPServer = true;
			case "fastMode", "fm" -> fastMode = true;

			// ── Paths ─────────────────────────────────────────────────
			case "datadir"     -> dataPath     = Path.of(stripMatchingQuotes(args[++i]));
			case "userdatadir" -> userDataPath = Path.of(stripMatchingQuotes(args[++i]));

			case "i", "include" -> {
				try { includes.add(Path.of(stripMatchingQuotes(args[++i]))); }
				catch (Exception e) { e.printStackTrace(); }
			}
			case "o", "output" -> {
				try {
					outputPath = Path.of(stripMatchingQuotes(args[++i]));
					out = new PrintStream(Files.newOutputStream(outputPath));
				} catch (Exception e) { e.printStackTrace(); }
			}

			// ── Defines ───────────────────────────────────────────────
			case "define", "d" -> {
				definesList.add(args[++i]); // NAME
				definesList.add(args[++i]); // BODY
			}

			// ── Logging ───────────────────────────────────────────────
			case "log-parse", "log-p"   -> logLevel = Level.FINER;
			case "warn-parse", "warn-p" -> logLevel = Level.WARNING;
			case "log-level" -> logLevel = switch (args[++i]) {
			case "severe" -> Level.SEVERE;
			case "warn"   -> Level.WARNING;
			case "info"   -> Level.INFO;
			case "debug"  -> Level.FINER;
			case "off"    -> Level.OFF;
			default       -> Level.INFO;
			};
			case "color" -> enableColors = Boolean.parseBoolean(args[++i]);

			// ── Parsing ───────────────────────────────────────────────
			case "parse" -> parse = Boolean.parseBoolean(args[++i]);

			// ── Data extraction / queries ─────────────────────────────
			case "l", "list-files"   -> listFilesInInfo = true;
			case "df", "definitions" -> definitions = true;
			case "q", "query"        -> queries.add(args[++i]);

			case "eut", "extract-unit-type" -> {
				extractUnitTypeData = true;
				unitTypeOutPath = Path.of(args[++i]);
			}
			case "gmr", "generate-macro-ref" -> {
				generateMacroRef = true;
				macroRefPath = Path.of(args[++i]);
			}

			// ── Help / version ────────────────────────────────────────
			case "h", "help", "?" -> { System.out.println(buildUsage()); System.exit(0); }
			case "v", "version"   -> { System.out.println(VERSION);      System.exit(0); }
			}
		}
	}
}
