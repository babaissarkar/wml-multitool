package com.babai.wml;

import static com.babai.wml.cli.ANSIFormatter.colorify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.logging.Level;

import com.babai.wml.cli.ANSIFormatter;
import com.babai.wml.cli.ArgParser;
import com.babai.wml.lsp.WMLLanguageServer;
import com.babai.wml.output.DataExtractor;
import com.babai.wml.parser.Parser;
import com.babai.wml.parser.PathContext;
import com.babai.wml.preprocessor.Definition;
import com.babai.wml.preprocessor.Preprocessor;
import com.babai.wml.tokenizer.Tokenizer;
import com.babai.wml.utils.Colors;
import com.babai.wml.utils.LogUtils;
import com.babai.wml.utils.MacroTable;

public class Main {
	private static MacroTable defines, predefines;
	private static HashMap<String, String> fileExplanations;
	private static PathContext pathContext;

	public static void main(String[] args) {
		defines = new MacroTable();
		predefines = new MacroTable();
		
		var argParser = new ArgParser();
		argParser.parseArgs(args);
		
		if (argParser.startLSPServer) {
			LogUtils.setLogLevel(Level.OFF);
			ANSIFormatter.setColorsEnabled(false);
		} else {
			ANSIFormatter.setColorsEnabled(argParser.enableColors);
		}
		
		predefines.addMacro("MULTIPLAYER", new Definition("MULTIPLAYER", "true"), 0, "predefined");
		
		for (int i = 0; i < argParser.definesList.size(); i += 2) {
			String name = argParser.definesList.get(i);
			String val = argParser.definesList.get(i+1);
			predefines.addMacro(name, new Definition(name, val), 0, "predefined");
		}
		
		// TODO autodetect if possible, requires preprocessing _main.cfg
		final String[] difficulties = {"EASY", "NORMAL", "HARD", "NIGHTMARE"};
		boolean hasDifficultyDefine = false;
		for (var d : difficulties) {
			hasDifficultyDefine = predefines.hasMacro(d);
		}
		
		if (!hasDifficultyDefine) {
			predefines.addMacro("NORMAL", new Definition("NORMAL", "true"), 0, "predefined");
		}
		
		if (argParser.startLSPServer) {
			WMLLanguageServer.initServer(predefines, argParser.dataPath, argParser.userDataPath, argParser.includes);
		} else {
			try {
				initParse(argParser);
				if (argParser.generateMacroRef) {
					DataExtractor.generateMacroRef(
						argParser.macroRefPath, defines, fileExplanations, pathContext);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private static void initParse(ArgParser argParser) throws IOException {
		LogUtils.setLogLevel(argParser.logLevel);
		
		long start = System.nanoTime();

		if (argParser.dataPath == null) {
			LogUtils.errorPrint(() ->"Wesnoth Gamedata path not specified.");
			System.exit(1);
		} else {
			LogUtils.infoPrint(() ->"Wesnoth Gamedata path: "
				+ ANSIFormatter.colorify(
					argParser.dataPath.toAbsolutePath().toString(), Colors.filePathColor));
		}

		if (argParser.userDataPath == null) {
			Path upath = argParser.inputPath.toAbsolutePath();
			while (upath != null && !upath.endsWith("data")) upath = upath.getParent();
			if (upath != null) {
				pathContext = new PathContext(argParser.dataPath, upath.getParent());
				LogUtils.infoPrint(() ->"Wesnoth Userdata path: "
					+ ANSIFormatter.colorify(
						pathContext.userDataPath().toAbsolutePath().toString(), Colors.filePathColor));
			} else {
				LogUtils.errorPrint(() ->"Wesnoth Userdata path not found or specified.");
				System.exit(1);
			}
		} else {
			LogUtils.infoPrint(() ->"Wesnoth Userdata path: "
				+ ANSIFormatter.colorify(
					argParser.userDataPath.toAbsolutePath().toString(), Colors.filePathColor));
		}
		
		var p = new Preprocessor(pathContext, predefines);
		// Fast Mode toggle: this disables macro expansion and only scans minimal details
		// to generate the required info.
		boolean fastMode = argParser.fastMode
			|| argParser.definitions
			|| argParser.generateMacroRef;
		Tokenizer.enableExtraction(fastMode);
		p.expandMacros(!fastMode);
		p.setListFilesInInfo(argParser.listFilesInInfo);
		
		LogUtils.infoPrint(() ->"Predefined macros: " + predefines.size());
		
		BufferedWriter writer = null;
		if (!fastMode) {
			if (argParser.outputPath != null) {
				writer = Files.newBufferedWriter(argParser.outputPath);
			} else {
				writer = new BufferedWriter(new OutputStreamWriter(System.out));
			}
		}
//		p.setExtractData(argParse.extractUnitTypeData);

		for (Path incpath : argParser.includes) {
			// FIXME recheck directory support
			long depStart = System.nanoTime();
			long mCountStart = p.getDefines().size();
			
			p.preprocess(incpath);
			
			writeTime(
				"Preprocessed " + colorify(pathContext.relativize(incpath), Colors.filePathColor) + ", "
				+ (p.getDefines().size() - mCountStart) + " macros. ",
				depStart);
		}
		
		String out = "";
		if (argParser.inputPath != null) {
			long mainStart = System.nanoTime();
			int countStart = p.getDefines().size();
			
			out = p.preprocess(argParser.inputPath);
			
			writeTime(
				"Preprocessed " + colorify(pathContext.relativize(argParser.inputPath), Colors.filePathColor) + ", "
				+ (p.getDefines().size() - countStart) + " macros. ",
				mainStart);
		} else {
			// since this is stdin, time/macro count is inconvenient. may or may not change later.
			out = p.preprocessString(new String(System.in.readAllBytes()));
		}
		
		defines = p.getDefines();
		fileExplanations = p.getFileExplanations();
		
		writeTime("Preprocess: " + defines.size() + " macros. ", start);
		start = System.nanoTime();
		
		if (argParser.definitions) {
			for (var name : defines.macros().keySet()) {
				writer.write("macro: ");
				writer.write(name);
				writer.write(" | ");
				writer.write(defines.getUri(name));
				writer.write("\n");
			}
		} else if (argParser.queries.isEmpty() && !fastMode) {
			writer.write(out);
		}
		
		if (argParser.parse && !fastMode) {
			HashSet<Path> binaryPaths = new HashSet<>();
			HashSet<String> unitTypes = new HashSet<>();
			var buff = new StringBuilder();
			
			binaryPaths.add(Path.of("data/core"));
			
			var parser = new Parser();
			parser.addQuery("//unit_type/id", v -> unitTypes.add(v));
			parser.addQuery("binary_path/path", v -> binaryPaths.add(Path.of(v)));
			
			for (var q : argParser.queries) {
				parser.addQuery(q, v ->
					buff.append("Query ").append(q).append(" result: ").append(v).append("\n"));
			}
			
			parser.parse(out);
			
			writeTime("Parse: ", start);
			
			LogUtils.infoPrint(() -> "Binary Paths: " + binaryPaths);
			LogUtils.infoPrint(() -> "Unit Types: " + unitTypes.size());
			
			try {
				if (!argParser.queries.isEmpty()) {
					writer.write(!buff.isEmpty() ? buff.toString() : "Query did not match.");
				} else if (!argParser.definitions && !fastMode) {
					writer.write(out);
				}
			} catch (IOException ioe) {
				LogUtils.errorPrint(() ->ioe.getMessage());
			}
		} else {
			LogUtils.infoPrint(() -> "Binary Paths: " + Tokenizer.getBinaryPaths());
			LogUtils.infoPrint(() -> "Unit Types: " + p.getUnitTypes().size());
		}
		
//		if (argParse.extractUnitTypeData) {

//			HashSet<Config> unitTypeData = p.getUnitTypeData();
//			writeUnitTypeData(unitTypeData, argParse.unitTypeOutPath);
//			LogUtils.debugPrint(() ->"Total " + p.getDefines().rowCount() + " macros and " + unitTypeData.size() + " unit types defined.");
//		} else {

//			LogUtils.debugPrint(() ->"Total " + p.getDefines().rowCount() + " macros and " + unitTypes.size() + " unit types defined.");
//		}
		
		if (writer != null) {
			writer.flush();
			writer.close();
		}
	}

	private static void writeTime(String msg, long start) {
		long end = System.nanoTime();
		LogUtils.infoPrint(() -> msg + (end - start) / 1_000_000 + " ms");
	}
}
