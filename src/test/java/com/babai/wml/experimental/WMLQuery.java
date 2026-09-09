package com.babai.wml.experimental;

import java.util.List;
import org.junit.jupiter.api.*;

import com.babai.wml.query.WMLQuery;
import com.babai.wml.utils.AIGenerated;

import static org.junit.jupiter.api.Assertions.*;

@AIGenerated
class WMLQueryTest {

	List<String> stack(String... tags) {
		return List.of(tags).reversed();
	}
	
	boolean match(List<String> tagStack, String queryStr, String key) {
		var query = WMLQuery.of(queryStr);
		return query.stream().anyMatch(q -> q.match(tagStack, key));
	}

	// --- full tag path match (no key) ---

	@Test
	void exactPathMatch() {
		// query: top/mid/bot, stack innermost-first: [bot, mid, top]
		assertTrue(match(stack("bot", "mid", "top"), "top/mid/bot", ""));
	}

	@Test
	void partialPathNoMatch() {
		// stack is only mid/top, bot not entered yet
		assertFalse(match(stack("mid", "top"), "top/mid/bot", ""));
	}

	@Test
	void wrongTagNoMatch() {
		assertFalse(match(stack("wrong", "mid", "top"), "top/mid/bot", ""));
	}

	// --- key match ---

	@Test
	void keyMatch() {
		// query: top/mid/id — path matches, key line matches
		assertTrue(match(stack("mid", "top"), "top/mid/id", "id"));
	}

	@Test
	void keyMismatch() {
		assertFalse(match(stack("mid", "top"), "top/mid/id", "name=foo"));
	}

	@Test
	void keyMatchWrongPath() {
		assertFalse(match(stack("wrong", "top"), "top/mid/id", "id=foo"));
	}
	
	// --- anywhere match ---
	
	@Test
	void anyWhereMatch() {
		assertTrue(match(stack("b", "a", "c"), "//a/b", ""));
	}
	
	@Test
	void anyWhereMatchFalseRecovery() {
		assertTrue(match(stack("d", "b", "a", "a", "e", "c"), "//a/b", ""));
	}

	// --- edge cases ---

	@Test
	void stackTooShallow() {
		assertFalse(match(stack("top"), "top/mid/bot", ""));
	}

	@Test
	void singleTagQuery() {
		assertTrue(match(stack("top"), "top", ""));
	}

	@Test
	void singleKeyQuery() {
		assertTrue(match(stack("top"), "top/id", "id"));
	}

	@Test
	void emptyKeyLine() {
		assertFalse(match(stack("mid", "top"), "top/mid/id", ""));
	}
}
