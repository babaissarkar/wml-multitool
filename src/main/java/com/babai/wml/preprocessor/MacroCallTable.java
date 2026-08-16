package com.babai.wml.preprocessor;

import java.util.HashMap;
import java.util.HashSet;

public class MacroCallTable {
	private HashMap<String, HashSet<MacroCall>> macroCallsByName = new HashMap<>();
	private HashMap<String, HashSet<MacroCall>> macroCallsByUri = new HashMap<>();
	
	public void clearAll() {
		macroCallsByName.clear();
		macroCallsByUri.clear();
	}
	
	public void clearByUri(String uri) {
		macroCallsByUri.remove(uri);
		macroCallsByName.forEach((k, v) -> v.removeIf(m -> m.uri().equals(uri)));
	}
	
	public HashSet<MacroCall> byName(String name) {
		return macroCallsByName.getOrDefault(name, new HashSet<>());
	}
	
	public HashSet<MacroCall> byUri(String uri) {
		return macroCallsByUri.getOrDefault(uri, new HashSet<>());
	}
	
	public void add(MacroCall call) {
		macroCallsByName.computeIfAbsent(call.name(), k -> new HashSet<>()).add(call);
		macroCallsByUri.computeIfAbsent(call.uri(), k -> new HashSet<>()).add(call);
	}
}
