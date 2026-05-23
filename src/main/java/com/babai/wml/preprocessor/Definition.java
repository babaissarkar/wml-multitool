package com.babai.wml.preprocessor;

import static com.babai.wml.cli.ANSIFormatter.colorify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.tokenizer.Token;
import com.babai.wml.utils.Colors;
import static com.babai.wml.tokenizer.Tokenizer.tokenize;

public class Definition {
	private String name, value, docs = "";
	private List<String> args = new ArrayList<>();
	private HashMap<String, String> defArgs = new HashMap<>();
	private List<Token> cachedValueTokens;
	
	private boolean deprecated;
	private int deprecationLevel;
	private String deprecationRemovalVersion;
	private String deprecationMessage;

	public Definition(String name, String value) {
		this.name = name;
		this.value = value;
	}

	public Definition(String name, String value, List<String> args) {
		this.name = name;
		this.value = value;
		this.args = args;
	}

	public Definition(String name, String value, List<String> args, HashMap<String, String> defArgs) {
		this.name = name;
		this.value = value;
		this.args.addAll(args);
		this.defArgs = defArgs;
	}

	public void addArg(String arg) {
		args.add(arg);
	}

	public void addDefArg(String key, String val) {
		defArgs.put(key, val);
	}

	public List<String> getArgs() {
		return args;
	}

	public HashMap<String, String> getDefArgs() {
		return defArgs;
	}

	public String getValue() {
		return this.value;
	}

	public List<Token> getValueTokens() {
		if (cachedValueTokens == null) {
			try {
				cachedValueTokens = tokenize(value);
			} catch (IOException e) {
				cachedValueTokens = List.of();
			}
		}
		return cachedValueTokens;
	}
	
	public int getArgCount() {
		return args.size();
	}

	public int getDefArgCount() {
		return defArgs.size();
	}

	public String getDocs() {
		return this.docs;
	}

	public void setDocs(String docs) {
		this.docs = docs;
	}
	
	/** Expand the macro, substituting any given args */
	public String expand(List<MacroArg> values, Map<String, String> keyVals) {
		if (values.size() != args.size()) {
			throw new IllegalArgumentException("Wrong number of arguments supplied to macro '" + name() + "'. "
					+ "Expected " + args.size() + " but got " + values.size() + ".");
		}
		
		var substMap = new HashMap<String, String>();
		
		for (int i = 0; i < args.size(); i++) {
			substMap.put(args.get(i), values.get(i).value());
		}
		
		for (var entry : defArgs.entrySet()) {
			String val = keyVals.get(entry.getKey());
			if (val == null) {
				val = entry.getValue();
			}
			substMap.put(entry.getKey(), val);
		} 

		return ParseUtils.substitute(this.value, substMap);
	}

	public String toString() {
		var sb = new StringBuilder();
		sb.append("#define ");
		sb.append(name);
		for (var arg : args) {
			sb.append(" " + arg);
		}
		sb.append("\n");
		sb.append(defArgs.toString());
		sb.append("\n");
		sb.append(value);
		sb.append("#enddef");
		return sb.toString();
	}

	public String name() {
		String argsAsString = argsAsString(args, defArgs);
		return "{" + name + (!argsAsString.isEmpty() ? " " + argsAsString : "") + "}";
	}
	
	public String coloredName() {
		return colorify(name(), Colors.macroNameColor);
	}
	
	public static String argsAsString(List<String> args, Map<String, String> defArgs) {
		var keyValsStrings = defArgs.entrySet().stream().map(Map.Entry::toString).collect(Collectors.toList());

		return String.join(", ", args)
				+ (args.size() > 0 && !keyValsStrings.isEmpty() ? ", " : "")
				+ String.join(", ", keyValsStrings);
	}

	public static String argsAsString2(List<MacroArg> args, Map<String, String> defArgs) {
		var argStrings = args.stream().map(a -> a.value()).collect(Collectors.toList());
		var keyValsStrings = defArgs.entrySet().stream().map(Map.Entry::toString).collect(Collectors.toList());

		return String.join(", ", argStrings)
				+ (args.size() > 0 && !keyValsStrings.isEmpty() ? ", " : "")
				+ String.join(", ", keyValsStrings);
	}
	
	// Deprecation related methods
	
	public void setDeprecated(boolean isDeprecated) {
		this.deprecated = isDeprecated;
	}

	public void setDeprecationLevel(int depreLevel) {
		this.deprecationLevel = depreLevel;
	}
	
	public void setDeprecationRemovalVersion(String removalVersion) {
		this.deprecationRemovalVersion = removalVersion;
	}
	
	public void setDeprecationMessage(String deprecationMessage) {
		this.deprecationMessage = deprecationMessage;
	}

	public boolean isDeprecated() {
		return deprecated;
	}

	public int getDeprecationLevel() {
		return deprecationLevel;
	}

	public String getDeprecationRemovalVersion() {
		return deprecationRemovalVersion;
	}

	public String getDeprecationMessage() {
		return deprecationMessage;
	}
}
