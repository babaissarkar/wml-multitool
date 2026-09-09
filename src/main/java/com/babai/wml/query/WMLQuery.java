package com.babai.wml.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.babai.wml.utils.AIGenerated;

public class WMLQuery {	
	private List<String> qparts;
	private boolean absolute;
	
	private WMLQuery(List<String> parts, boolean absolute) {
		this.qparts = parts;
		this.absolute = absolute;
	}
	
	public boolean match(List<String> tagStack, String key) {
		List<String> stack = new ArrayList<>(tagStack);
		if (!key.isEmpty()) {
			stack.add(key);
		}
		
		if (!absolute) {
			return Collections.indexOfSubList(stack, qparts) != -1;
		} else {
			return Collections.indexOfSubList(stack, qparts) == 0;
		}
//		return false;
	}

	/** The top-level tag that scopes an query. */
	public String rowTag() {
		return !qparts.isEmpty() ? qparts.getFirst() : "";
	}
	
	public static List<WMLQuery> of(String queryStr) {
		int firstComma = queryStr.indexOf(',');
		List<WMLQuery> list = new ArrayList<>();
		
		if (firstComma < 0) {
			list.add(WMLQuery.ofSingle(queryStr));
			return list;
		}
		
		int baseEnd    = queryStr.lastIndexOf('/', firstComma) + 1;
		String base    = queryStr.substring(0, baseEnd);
		
		for (var field : queryStr.substring(baseEnd).split(",")) {
			list.add(WMLQuery.ofSingle(base + field.trim()));
		}
		
		return list;
	}
	
	@AIGenerated
	private static WMLQuery ofSingle(String queryStr) {
		int start = 0;
		boolean absolute = true;

		if (queryStr.length() >= 2 && queryStr.charAt(0) == '/' && queryStr.charAt(1) == '/') {
			absolute = false;
			start = 2;
		}

		// trim leading whitespace
		while (start < queryStr.length() && Character.isWhitespace(queryStr.charAt(start))) start++;

		// trim trailing whitespace
		int end = queryStr.length();
		while (end > start && Character.isWhitespace(queryStr.charAt(end - 1))) end--;

		var parts = new ArrayList<String>();
		int partStart = start;
		for (int i = start; i <= end; i++) {
			if (i == end || queryStr.charAt(i) == '/') {
				if (i > partStart) parts.add(queryStr.substring(partStart, i));
				partStart = i + 1;
			}
		}

		return new WMLQuery(parts, absolute);
	}
}
