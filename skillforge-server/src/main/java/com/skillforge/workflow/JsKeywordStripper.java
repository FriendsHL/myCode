package com.skillforge.workflow;

/**
 * String/comment-aware stripper for a standalone JS prefix keyword (e.g.
 * {@code await} or {@code export}) plus the single run of whitespace that
 * follows it.
 *
 * <p>It walks the source tracking string literals ({@code '...'}, {@code "..."},
 * {@code `...`}) and comments ({@code //...}, block comments) so the keyword is
 * only removed when it appears as a real identifier-boundary keyword in code —
 * never inside a string or comment, and never as part of a longer identifier
 * ({@code awaitable}) or a member access ({@code obj.export}).
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link AwaitPreprocessor} — strips {@code await} (Judge ruling #1: the
 *       DSL keeps {@code await} in its surface syntax but Rhino 1.7.14 has no
 *       async/await, and the primitives are synchronous so {@code await expr ≡
 *       expr}).</li>
 *   <li>{@link WorkflowDefinitionRegistry} — strips {@code export} so Rhino can
 *       parse the body ({@code export const meta = ...} → {@code const meta =
 *       ...}).</li>
 * </ul>
 */
public final class JsKeywordStripper {

    private JsKeywordStripper() {
    }

    public static String strip(String source, String keyword) {
        if (source == null || source.isEmpty() || keyword == null || keyword.isEmpty()) {
            return source;
        }
        int n = source.length();
        int kn = keyword.length();
        char k0 = keyword.charAt(0);
        StringBuilder out = new StringBuilder(n);

        boolean inSq = false;      // '...'
        boolean inDq = false;      // "..."
        boolean inTpl = false;     // `...`
        boolean inLine = false;    // // ...
        boolean inBlock = false;   // /* ... */

        int i = 0;
        while (i < n) {
            char c = source.charAt(i);

            // --- inside string/comment states: copy verbatim, watch for exit ---
            if (inLine) {
                out.append(c);
                if (c == '\n') inLine = false;
                i++;
                continue;
            }
            if (inBlock) {
                out.append(c);
                if (c == '*' && i + 1 < n && source.charAt(i + 1) == '/') {
                    out.append('/');
                    i += 2;
                    inBlock = false;
                } else {
                    i++;
                }
                continue;
            }
            if (inSq || inDq || inTpl) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {        // escape: copy next char raw
                    out.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (inSq && c == '\'') inSq = false;
                else if (inDq && c == '"') inDq = false;
                else if (inTpl && c == '`') inTpl = false;
                i++;
                continue;
            }

            // --- normal code: detect comment / string openers first ---
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                out.append("//");
                i += 2;
                inLine = true;
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                out.append("/*");
                i += 2;
                inBlock = true;
                continue;
            }
            if (c == '\'') { inSq = true; out.append(c); i++; continue; }
            if (c == '"') { inDq = true; out.append(c); i++; continue; }
            if (c == '`') { inTpl = true; out.append(c); i++; continue; }

            // --- detect a standalone keyword ---
            if (c == k0 && matchesKeyword(source, i, keyword, kn)) {
                i += kn;
                // skip the following whitespace run (keyword expr -> expr)
                while (i < n && isWhitespace(source.charAt(i))) {
                    i++;
                }
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * True if {@code source[pos..pos+kn)} is {@code keyword} with a left
     * identifier-boundary (not preceded by an identifier char or {@code .}) and a
     * right boundary that is whitespace (so it's a prefix keyword, not part of a
     * longer identifier or a member access).
     */
    private static boolean matchesKeyword(String source, int pos, String keyword, int kn) {
        int n = source.length();
        if (pos + kn > n) return false;
        if (!source.regionMatches(pos, keyword, 0, kn)) return false;

        if (pos > 0) {
            char prev = source.charAt(pos - 1);
            if (isIdentifierPart(prev) || prev == '.') {
                return false;
            }
        }
        if (pos + kn >= n) return false;
        char next = source.charAt(pos + kn);
        return isWhitespace(next);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
