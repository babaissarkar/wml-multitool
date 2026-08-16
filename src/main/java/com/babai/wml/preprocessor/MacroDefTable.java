package com.babai.wml.preprocessor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MacroDefTable {
	Map<String, MacroDef> nameValMap;
	Map<String, Integer> nameLineNumMap;
	Map<String, String> nameUriMap;
	Map<String, Set<String>> uriNamelistMap;
	
	public MacroDefTable() {
		nameValMap = new HashMap<>();
		nameLineNumMap = new HashMap<>();
		nameUriMap = new HashMap<>();
		uriNamelistMap = new HashMap<>();
	}
	
	public MacroDefTable(MacroDefTable defines) {
		this();
		nameValMap.putAll(defines.nameValMap);
		nameLineNumMap.putAll(defines.nameLineNumMap);
		nameUriMap.putAll(defines.nameUriMap);
		uriNamelistMap.putAll(defines.uriNamelistMap);
	}

	public void addMacro(String name, MacroDef def, Integer linenum, String uri) {
		//TODO duplicate warning
		nameValMap.put(name, def);
		nameLineNumMap.put(name, linenum);
		nameUriMap.put(name, uri);
		uriNamelistMap.computeIfAbsent(uri, k -> new HashSet<>()).add(name);
	}
	
	public boolean hasMacro(String name) {
		return nameValMap.containsKey(name);
	}
	
	public MacroDef getMacro(String name) {
		return nameValMap.get(name);
	}
	
	public void removeMacro(String name) {
		nameValMap.remove(name);
		nameLineNumMap.remove(name);
		nameUriMap.remove(name);
	}
	
	public void removeMacroByUri(String uri) {
		Set<String> names = uriNamelistMap.remove(uri);
		if (names == null) return;
		for (String name : names) {
			nameValMap.remove(name);
			nameLineNumMap.remove(name);
			nameUriMap.remove(name);
		}
	}
	
	public String getUri(String name) {
		return nameUriMap.get(name);
	}
	
	public int getLineNum(String name) {
		return nameLineNumMap.get(name);
	}
	
	public Map<String, MacroDef> macros() {
		return nameValMap;
	}
	
	public Set<String> macrosByUri(String uri) {
		return uriNamelistMap.get(uri);
	}

	public Map<String, Set<String>> uriMap() {
		return uriNamelistMap;
	}
	
	public int size() {
		return nameValMap.size();
	}
	
	@Override
	public String toString() {
		return macros().toString().replaceAll(", ", "\n");
	}
}
