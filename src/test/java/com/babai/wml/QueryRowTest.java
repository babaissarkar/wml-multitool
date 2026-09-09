package com.babai.wml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.babai.wml.parser.Parser;

class QueryRowTest {

	@Test
	void collectsEachUnitTypeIntoItsOwnOrderedRow() throws Exception {
		var parser = new Parser();
		var rows = new ArrayList<List<String>>();
		parser.addRowQueries(List.of("unit_type/id", "unit_type/name", "unit_type/level"), rows::add);

		parser.parse("""
			[unit_type]
			    id=UT1
			    name=Spearman
			    level=1
			[/unit_type]
			[unit_type]
			    name=Bowman
			    id=UT2
			[/unit_type]
			""");

		assertEquals(List.of(
			List.of("UT1", "Spearman", "1"),
			List.of("UT2", "Bowman", "")), rows);
	}

	@Test
	void joinsRepeatedValuesWithinOneRowColumn() throws Exception {
		var parser = new Parser();
		var rows = new ArrayList<List<String>>();
		parser.addRowQueries(List.of("unit_type/id", "unit_type/trait/id"), rows::add);

		parser.parse("""
			[unit_type]
			    id=UT1
			    [trait]
			        id=quick
			    [/trait]
			    [trait]
			        id=strong
			    [/trait]
			[/unit_type]
			""");

		assertEquals(List.of(List.of("UT1", "quick|strong")), rows);
	}

	@Test
	void rejectsQueriesWithoutASharedAbsoluteRowTag() {
		var parser = new Parser();
		assertThrows(IllegalArgumentException.class,
			() -> parser.addRowQueries(List.of("unit_type/id", "//attack/name"), values -> {}));
	}
}
