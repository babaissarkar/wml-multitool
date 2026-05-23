# Preprocessor Performance Audit

## High-impact hotspots

1. **Recursive tokenization in `preprocessFragment` creates repeat full scans of strings.**
   - Every embedded macro block (`{"..."}` inside non-MACRO tokens) calls `preprocessFragment`, which calls `tokenize(fragment)` again.
   - This is triggered in both `processToken` and `expandMacroCall` paths, so nested macros/arguments can multiply work.
   - Code locations:
     - `processToken` checks for `{`/`}` in generic tokens and re-enters fragment preprocessing.
     - `expandMacroCall` preprocesses each argument string independently (`preprocessFragment(str, List.of())`).

2. **Per-token string checks and object churn in hot loops.**
   - `processToken` runs on every token and repeatedly performs:
     - `content.contains("{") && content.contains("}")`
     - string reconstruction via `Token.getRaw(nestedSubst, t.kind())`
   - `expandMacroCall` allocates several short-lived collections (`parts`, `args`, `defArgs`, `argsList`) for each call.

3. **Directory traversal repeatedly recurses through includes with no memoization.**
   - `preprocess(Path)` recursively processes directories and files and can revisit the same include tree from multiple include sites.
   - There is no "already-processed file" or include-cycle guard cache.

4. **Global mutable state (`currentPath`, `skipElse`, `currentDefineArgs`) couples control flow and prevents parallelism.**
   - Include handling and recursive preprocess operations mutate shared instance fields.
   - This architecture makes it difficult to safely parallelize includes or macro expansion and increases correctness/perf risk under refactors.

5. **`Table` lookups are indexed for `Name`, but API still rebuilds row lists per query.**
   - `getRows` converts indexed `Cell` hits into a new `List<Row>` on every lookup.
   - Macro expansion performs this lookup for every macro token; repeated list reconstruction adds overhead.

## Architecture-level bottlenecks

1. **Single-pass parser mixed with execution side effects.**
   - Token parsing, directive interpretation, include resolution, and macro execution are intertwined.
   - This prevents precompiled macro IR/caching and complicates profiling boundaries.

2. **String-based macro body handling instead of token-stream IR.**
   - Macro bodies are stored as strings and re-tokenized during expansion.
   - A token/AST IR for macro bodies would avoid repeated lexical passes.

3. **Inclusion and definition storage are global and append-only during run.**
   - No versioned scope snapshots or immutable contexts for nested expansion.
   - Makes caching (e.g., include output by file hash + define set) difficult.

## Recommended optimization plan (ordered)

1. Add profiling counters/timers around:
   - `preprocessFragment`
   - `expandMacroCall`
   - `processToken`
   - `preprocess(Path)` recursion and include fan-out
2. Add include memoization + cycle detection (`Set<Path>` active stack, `Map<Path, String>` cached output when safe).
3. Pre-tokenize macro definition bodies at define time, store token lists in `Definition`, and expand from tokens.
4. Replace repeated list creation in macro definition lookups with direct `Definition` index (`Map<String, Definition>` fast path).
5. Reduce temporary allocations in hot loops (reuse builders/lists where possible).
6. Split parser from executor to enable concurrency and better cacheability.

## Quick wins

- Cache `context.relativize(...)` and colorized path strings only for logging mode.
- Avoid unconditional `"\n"` append in `preprocess(Path)` when caller already controls boundaries.
- Replace repeated `peek(itor)` calls in loops with a local variable where safe.
