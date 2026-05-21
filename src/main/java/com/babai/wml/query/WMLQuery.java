package com.babai.wml.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class WMLQuery {
	private final static Pattern slashpattern = Pattern.compile("/");
	
	public static boolean match(List<String> tagStack, String queryStr, String key) {
		List<String> stack = new ArrayList<>(tagStack);
		if (!key.isEmpty()) {
			stack.add(key);
		}
		
		if (queryStr.startsWith("//")) {
			return Collections.indexOfSubList(stack, parseQueryString(queryStr)) != -1;
		} else {
			return Collections.indexOfSubList(stack, parseQueryString(queryStr)) == 0;
		}
//		return false;
	}
	
	private static List<String> parseQueryString(String queryStr) {
		if (queryStr.startsWith("//")) {
			queryStr = queryStr.substring(2, queryStr.length());
		}
		
		return List.of(slashpattern.split(queryStr.trim()));
	}
}
