package com.babai.wml.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import com.babai.wml.query.WMLQuery;
import com.babai.wml.tokenizer.Token;

import static com.babai.wml.utils.Colors.*;
import static com.babai.wml.utils.LogUtils.*;
import static com.babai.wml.cli.ANSIFormatter.colorify;

import static com.babai.wml.tokenizer.ParserTokenizer.addEvent;
import static com.babai.wml.tokenizer.ParserTokenizer.tokenize;

public class Parser {
	private List<String> tagStack = new ArrayList<>();
	private HashMap<WMLQuery, List<Consumer<String>>> queryLambdas = new HashMap<>();
	private List<WMLQuery> rowQueries = List.of();
	private Consumer<List<String>> rowConsumer;
	private String rowTag = "";
	private List<List<String>> rowValues;

	public void addQuery(String queryStr, Consumer<String> queryLambda) {
		for (var query : WMLQuery.of(queryStr)) {
			queryLambdas.computeIfAbsent(query, k -> new ArrayList<>()).add(queryLambda);
		}
	}
	
	public void addQuery(WMLQuery query, Consumer<String> queryLambda) {
		queryLambdas.computeIfAbsent(query, k -> new ArrayList<>()).add(queryLambda);
	}
	
	public void addRowQueries(List<String> queries, Consumer<List<String>> consumer) {
		List<WMLQuery> parsed = new ArrayList<>();
		for (var q : queries) {
			parsed.addAll(WMLQuery.of(q));
		}
		addRowQueries2(parsed, consumer);
	}

	/**
	 * Collect a group of queries into one result for each occurrence of
	 * their shared top-level tag. Values repeated within a column are joined with
	 * {@code |}; missing values are returned as empty strings.
	 */
	public void addRowQueries2(List<WMLQuery> queries, Consumer<List<String>> consumer) {
		if (queries.size() < 2) {
			throw new IllegalArgumentException("Row queries require at least two query expressions.");
		}

		String sharedRowTag = queries.getFirst().rowTag();
		if (queries.stream().anyMatch(q -> !sharedRowTag.equals(q.rowTag()))) {
			throw new IllegalArgumentException(
				"Row queries must share the same top-level tag (e.g. unit_type/id and unit_type/name).");
		}

		rowQueries = queries;
		rowConsumer = consumer;
		rowTag = sharedRowTag;
	}

	public void parse(String text) throws IOException {
		var key = new StringBuilder();
		var val = new StringBuilder();
		addEvent((kind, buff) -> parseToken(buff.toString(), kind, key, val));
		tokenize(text.toCharArray());
	}

	private void parseToken(String contents, Token.Kind kind, StringBuilder key, StringBuilder val) {
		switch (kind) {
		case TEXT -> key.append(contents);
		
		case VAL -> val.append(contents);
		
		case EOL -> {
			if (!key.isEmpty() && !val.isEmpty()) {
				// Check against queries
				final String keyStr = key.toString();
				final String valStr = val.toString();
				queryLambdas.forEach((q, actions) -> {
					if (q.match(tagStack, keyStr)) {
						for (var lambda : actions) {
							lambda.accept(valStr);
						}
					}
				});

				if (rowValues != null) {
					for (int i = 0; i < rowQueries.size(); i++) {
						if (rowQueries.get(i).match(tagStack, keyStr)) {
							rowValues.get(i).add(valStr);
						}
					}
				}
			}
			
			key.setLength(0);
			val.setLength(0);
		}
		
		case TAG_START -> {
			if (isTag(contents)) {
				tagStack.add(contents);
				if (contents.equals(rowTag)) {
					rowValues = new ArrayList<>();
					for (int i = 0; i < rowQueries.size(); i++) rowValues.add(new ArrayList<>());
				}
			}
		}
		
		case TAG_END -> {
			if (tagStack.isEmpty()) {
				errorPrint(() -> "End tag without matching start tag.");
			} else if (tagStack.getLast().equals(contents)) {
				if (contents.equals(rowTag) && rowValues != null) {
					rowConsumer.accept(rowValues.stream()
						.map(values -> String.join("|", values))
						.toList());
					rowValues = null;
				}
				tagStack.removeLast();
			} else {
				errorPrint(() -> "Wrong end tag " + colorify(contents, RED)
				+ " found for tag "
				+ colorify("[" + tagStack.getLast() + "]", tagColor));
			}
		}
		case WHITESPACE, COMMENT, QUOTED, ANGLE_QUOTED -> {} //ignore
		case MACRO -> warningPrint(() -> "Unexpanded macro during parse, {" + contents + "}, skipping");
		default -> {} //TODO
		}
	}
	
	private boolean isTag(String tagName) {
		if (tagName.length() == 0) return false;
		char c;
		for (int i = 0; i < tagName.length(); i++) {
			c = tagName.charAt(i);
			if (i == 0) {
				if (!(Character.isLetter(c))) return false;
			} else {
				if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
			}
		}
		return true;
	}
}
