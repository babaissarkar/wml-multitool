package com.babai.wml;

import static com.babai.wml.cli.ANSIFormatter.colorify;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;

import com.babai.wml.cli.ANSIFormatter;
import com.babai.wml.cli.ArgParser;
import com.babai.wml.lsp.WMLLanguageServer;
import com.babai.wml.output.DataExtractor;
import com.babai.wml.parser.Parser;
import com.babai.wml.parser.PathContext;
import com.babai.wml.preprocessor.Definition;
import com.babai.wml.preprocessor.Preprocessor;
import com.babai.wml.utils.Colors;
import com.babai.wml.utils.LogUtils;
import com.babai.wml.utils.Table;

public class Main {
	private static Table defines;
	private static HashMap<String, String> fileExplanations;
	private static PathContext pathContext;
	
	private static Table predefines = Table.ofWithIndices(
		new Class<?>[] { Integer.class, String.class, String.class, Definition.class },
		new String[] { "Line", "URI", "Name", "Definition" },
		1, 2
		);

	public static void main(String[] args) {
		var argParser = new ArgParser();
		argParser.parseArgs(args);
		
		ANSIFormatter.setColorsEnabled(argParser.enableColors);
		
		if (argParser.dataPath == null) {
			LogUtils.errorPrint("Wesnoth Gamedata path not specified.");
		} else {
			LogUtils.infoPrint("Wesnoth Gamedata path: "
				+ ANSIFormatter.colorify(
					argParser.dataPath.toAbsolutePath().toString(), Colors.filePathColor));
		}
		
		if (argParser.userDataPath == null) {
			LogUtils.errorPrint("Wesnoth Userdata path not specified.");
		} else {
			LogUtils.infoPrint("Wesnoth Userdata path: " 
				+ ANSIFormatter.colorify(
					argParser.userDataPath.toAbsolutePath().toString(), Colors.filePathColor));
		}
		
		pathContext = new PathContext(
			argParser.dataPath,
			argParser.userDataPath,
			new HashSet<Path>());
		
		predefines.addRow(0, "predefined", "MULTIPLAYER", new Definition("MULTIPLAYER", "true"));
		
		for (int i = 0; i < argParser.definesList.size(); i += 2) {
			String name = argParser.definesList.get(i);
			String val = argParser.definesList.get(i+1);
			predefines.addRow(0, "predefined", name, new Definition(name, val));
		}
		
		if (argParser.startLSPServer) {
			WMLLanguageServer.initServer(predefines, pathContext, argParser.includes);
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
		
		var p = new Preprocessor(pathContext, predefines);
		p.setListFilesInInfo(argParser.listFilesInInfo);
		
		LogUtils.infoPrint("Predefined macros: " + predefines.rowCount());
		
		BufferedWriter writer = null;
		if (argParser.outputPath != null) {
			writer = Files.newBufferedWriter(argParser.outputPath);
		} else {
			writer = new BufferedWriter(new OutputStreamWriter(System.out));
		}
//		p.setExtractData(argParse.extractUnitTypeData);

		for (Path incpath : argParser.includes) {
			// FIXME recheck directory support
			long depStart = System.nanoTime();
			long mCountStart = p.getDefines().rowCount();
			
			p.preprocess(incpath);
			
			long depEnd = System.nanoTime();
			long mCountEnd = p.getDefines().rowCount();
			
			LogUtils.infoPrint(
				"Preprocessed "
				+ colorify(pathContext.relativize(incpath), Colors.filePathColor) + ": "
				+ (depEnd - depStart) / 1_000_000 + " ms. "
				+ "Macros: " + (mCountEnd - mCountStart));
		}
		
		String out = "";
		if (argParser.inputPath != null) {
			long mainStart = System.nanoTime();
			long mCountStart = p.getDefines().rowCount();
			
			out = p.preprocess(argParser.inputPath);
			
			long mainEnd = System.nanoTime();
			long mCountEnd = p.getDefines().rowCount();
			
			LogUtils.infoPrint(
				"Preprocessed "
				+ colorify(pathContext.relativize(argParser.inputPath), Colors.filePathColor) + ": "
				+ (mainEnd - mainStart) / 1_000_000 + " ms. "
				+ "Macros: " + (mCountEnd - mCountStart));
		} else {
			// since this is stdin, time/macro count is inconvenient. may or may not change later.
			out = p.preprocessContent(new String(System.in.readAllBytes()));
		}
		
		long preprocEnd = System.nanoTime();
		
		defines = p.getDefines();
		fileExplanations = p.getFileExplanations();
		
		LogUtils.infoPrint(
			"Preprocessing finished: " + (preprocEnd - start) / 1_000_000 + " ms. "
			+ "Macros: " + defines.rowCount());
		
		if (argParser.definitions) {
			for (var row : defines.getRows()) {
				writer.write("macro: "
					+ (String) row.getColumn("Name").getValue() + " | "
					+ (String) row.getColumn("URI").getValue() + "\n");
			}
		} else if (argParser.queries.isEmpty()) {
			writer.write(out);
		}
		
		if (argParser.parse) {
			HashSet<Path> binaryPaths = new HashSet<>();
			var buff = new StringBuilder();
			
			var parser = new Parser();
			parser.addQuery("binary_path/path", v -> binaryPaths.add(Path.of(v)));
			
			for (var q : argParser.queries) {
				parser.addQuery(q, v -> buff.append("Query " + q + " result: " + v + "\n"));
			}
			
			parser.parse(out);
			
			start = preprocEnd;
			long parseEnd = System.nanoTime();
			LogUtils.infoPrint("Parsing finished in " + (parseEnd - start) / 1_000_000 + " ms");
			
			LogUtils.infoPrint("Binary Paths: " + binaryPaths);
			
			try {
				if (!argParser.queries.isEmpty()) {
					writer.write(!buff.isEmpty() ? buff.toString() : "Query did not match.");
				} else {
					writer.write(out);
				}
			} catch (IOException ioe) {
				LogUtils.errorPrint(ioe.getMessage());
			}
		}

//		var unitTypes = p.getUnitTypes();
//		if (argParse.extractUnitTypeData) {
//			HashSet<Config> unitTypeData = p.getUnitTypeData();
//			writeUnitTypeData(unitTypeData, argParse.unitTypeOutPath);
//			LogUtils.debugPrint("Total " + p.getDefines().rowCount() + " macros and " + unitTypeData.size() + " unit types defined.");
//		} else {
//			LogUtils.debugPrint("Unit Types: " + unitTypes);
//			LogUtils.debugPrint("Total " + p.getDefines().rowCount() + " macros and " + unitTypes.size() + " unit types defined.");
//		}
		
		writer.flush();
		writer.close();
	}
}
