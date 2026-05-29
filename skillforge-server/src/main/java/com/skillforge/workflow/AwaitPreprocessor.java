package com.skillforge.workflow;

/**
 * Task J (Judge ruling #1): strips the {@code await} keyword from workflow
 * source before handing it to Rhino.
 *
 * <p>Rationale: the DSL keeps {@code await} in its surface syntax (Claude Code
 * compatibility — agents habitually write {@code await agent(...)}), but Rhino
 * 1.7.14 has no {@code async}/{@code await} support and {@code agent()} /
 * {@code parallel()} are already synchronous (they block / barrier internally).
 * Under synchronous semantics {@code await expr ≡ expr}, so removing the keyword
 * is a safe no-op transform.
 *
 * <p>The stripper is a small lexer that walks the source tracking string
 * literals ({@code '...'}, {@code "..."}, {@code `...`}) and comments
 * ({@code //...}, {@code /* ... *}{@code /}) so {@code await} inside a string or
 * comment is preserved verbatim. Only a standalone {@code await} <em>keyword</em>
 * (identifier-boundary on the left, whitespace on the right, not a member access
 * like {@code obj.await}) is removed, along with the single following run of
 * whitespace.
 */
public final class AwaitPreprocessor {

    private AwaitPreprocessor() {
    }

    public static String stripAwait(String source) {
        return JsKeywordStripper.strip(source, "await");
    }
}
