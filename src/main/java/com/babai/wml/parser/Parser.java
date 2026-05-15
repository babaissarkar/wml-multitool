package com.babai.wml.parser;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.babai.wml.query.WMLQuery;
import com.babai.wml.tokenizer.Token;

import static com.babai.wml.utils.Colors.*;
import static com.babai.wml.utils.LogUtils.*;
import static com.babai.wml.cli.ANSIFormatter.colorify;
import static com.babai.wml.tokenizer.Tokenizer.tokenize;
import static com.babai.wml.tokenizer.Token.Kind.*;

public class Parser {
	private List<String> tagStack = new ArrayList<>();
	private HashMap<String, List<Consumer<String>>> queryLambdas = new LinkedHashMap<>();
	private String lastLocTraceString;
	private static final Pattern NOT_TAG_PATTERN = Pattern.compile("[^a-z_\\d]|^\\d");
	
	public void addQuery(String query, Consumer<String> queryLambda) {
		queryLambdas.computeIfAbsent(query, k -> new ArrayList<>()).add(queryLambda);
	}
	
	public void parse(String text) throws IOException {
		var itor = tokenize(new StringReader(text)).listIterator();
		while (itor.hasNext()) {
			Token t = itor.next();
			parseToken(itor, t);
		}
	}

	private void parseToken(ListIterator<Token> itor, Token t) {
		switch (t.kind()) {
		case TEXT -> {
			var line = new StringBuilder();
			
			while (t.isKind(TEXT, WHITESPACE)) {
				line.append(t.content());
				t = itor.next();
			}
			
			for (var query : queryLambdas.entrySet()) {
				String[] parts = line.toString().split("=", 2);
				if (WMLQuery.match(tagStack, query.getKey(), parts[0].trim())) {
					String value = parts[1].trim();
					for (var lambda : query.getValue()) {
						lambda.accept(value);
					}
				}
			}
		}
		case TAG -> {
			String tagName = t.content();
			
			// Detect location from embedded @ annotation, if any
			if (tagName.contains("@")) {
				String[] nameAndLoc = tagName.split("@", 2);
				tagName = nameAndLoc[0].trim();
				lastLocTraceString = "\n\tIncluded from: "
					+ nameAndLoc[1].replace(":", ", line ").replace("@", "\n\tIncluded from: ");
			}
			
			if (tagName.startsWith("+")) {
				// appending tag, like [+units]
				tagName = tagName.substring(1, tagName.length());
				if (!NOT_TAG_PATTERN.matcher(tagName).find()) {
					tagStack.add(tagName);
				}
			} else if (tagName.startsWith("/")) {
				// end tag
				tagName = tagName.substring(1, tagName.length());
				if (tagStack.isEmpty()) {
					errorPrint("End tag without matching start tag.");
				} else if (tagStack.getLast().equals(tagName)) {
					tagStack.removeLast();
				} else {
					errorPrint(
						"Wrong end tag " + colorify(tagName, RED)
						+ " found for tag "
						+ colorify("[" + tagStack.getLast() + "]", tagColor)
						+ lastLocTraceString);
				}
				// needs better handling
			} else if (!NOT_TAG_PATTERN.matcher(tagName).find()) {
				tagStack.add(tagName);
			}
		}
		case WHITESPACE, EOL, COMMENT, QUOTED, ANGLE_QUOTED -> {} //ignore
		case MACRO -> warningPrint("Parser: Unexpanded macro {" + t.content() + "}, skipping");
		default -> warningPrint("Parser: Unexpected token " + t.content() + ", skipping");
		}
	}

	
}
