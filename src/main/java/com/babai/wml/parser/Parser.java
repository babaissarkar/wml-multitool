package com.babai.wml.parser;

import java.io.IOException;
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
import static com.babai.wml.parser.ParseUtils.peek;

public class Parser {
	private final static Pattern nottagpattern = Pattern.compile("[^a-z_\\d]|^\\d", Pattern.CASE_INSENSITIVE);
	private final static Pattern eqlpattern = Pattern.compile("=");

	private List<String> tagStack = new ArrayList<>();
	private HashMap<String, List<Consumer<String>>> queryLambdas = new LinkedHashMap<>();

	public void addQuery(String query, Consumer<String> queryLambda) {
		queryLambdas.computeIfAbsent(query, k -> new ArrayList<>()).add(queryLambda);
	}

	public void parse(String text) throws IOException {
		var itor = tokenize(text).listIterator();
		while (itor.hasNext()) {
			Token t = itor.next();
			parseToken(itor, t);
		}
	}

	private void parseToken(ListIterator<Token> itor, Token t) {
		switch (t.kind()) {
		case TEXT -> {
			var line = new StringBuilder();

			line.append(t.content());
			while (peek(itor).isKind(TEXT, WHITESPACE, QUOTED, ANGLE_QUOTED)) {
				t = itor.next();
				line.append(t.content());
			}

			for (var query : queryLambdas.entrySet()) {
				String[] parts = eqlpattern.split(line.toString(), 2);
				if (WMLQuery.match(tagStack, query.getKey(), parts[0].trim())) {
					String value = parts[1].trim();
					for (var lambda : query.getValue()) {
						lambda.accept(value);
					}
				}
			}
		}
		case TAG -> {
			String tagName = t.content().strip();
			if (tagName.startsWith("+")) {
				// appending tag, like [+units]
				tagName = tagName.substring(1, tagName.length());
				if (!nottagpattern.matcher(tagName).find()) {
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
					errorPrint("Wrong end tag " + colorify(tagName, RED)
					+ " found for tag "
					+ colorify("[" + tagStack.getLast() + "]", tagColor));
				}
				// needs better handling
			} else if (!nottagpattern.matcher(tagName).find()) {
				tagStack.add(tagName);
			}
		}
		case WHITESPACE, EOL, COMMENT, QUOTED, ANGLE_QUOTED -> {} //ignore
		case MACRO -> warningPrint("Parser: Unexpanded macro {" + t.content() + "}, skipping");
		default -> warningPrint("Parser: Unexpected token " + t.content() + ", skipping");
		}
	}


}
