package com.babai.wml.preprocessor;

import static com.babai.wml.cli.ANSIFormatter.colorify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.babai.wml.parser.ParseUtils;
import com.babai.wml.utils.Colors;

public class MacroDef {
	private String name, value, docs = "";
	private List<String> args = new ArrayList<>();
	private HashMap<String, String> defArgs = new HashMap<>();
	private HashSet<String> allArgs;
	
	private boolean deprecated;
	private int deprecationLevel;
	private String deprecationRemovalVersion;
	private String deprecationMessage;

	public MacroDef(String name, String value) {
		this.name = name;
		this.value = value;
	}

	public MacroDef(String name, String value, List<String> args) {
		this.name = name;
		this.value = value;
		this.args = args;
		allArgs = new HashSet<>(args);
	}

	public MacroDef(String name, String value, List<String> args, HashMap<String, String> defArgs) {
		this.name = name;
		this.value = value;
		this.args = args;
		this.defArgs = defArgs;
		allArgs = new HashSet<>(args);
		allArgs.addAll(defArgs.keySet());
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
		sb.append(" ");
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

	public HashSet<String> getAllArgs() {
		return allArgs;
	}
}
