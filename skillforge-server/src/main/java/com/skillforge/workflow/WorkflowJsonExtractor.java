package com.skillforge.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort JSON extraction from a model response — shared by BOTH the live
 * {@code agent()} path ({@code DefaultWorkflowAgentInvoker.tryParseJson}) and the
 * journal-replay path ({@code HostAgent.cachedAgentResult}).
 *
 * <p><b>Why shared (parity, the bug this fixes):</b> reasoning models (e.g. mimo)
 * routinely wrap their JSON answer in prose ("Now let me analyze… {json}") or a
 * markdown ```json fence. The journal stores the <em>raw</em> {@code finalResponse}.
 * If the live path tolerantly extracts the JSON (schema passes → step completed)
 * but replay parses the same raw string with a strict {@code readTree}, replay
 * throws on a response the live run accepted — exactly what broke approve→resume.
 * Routing both paths through this one method guarantees the same raw string
 * yields the same JSON node.
 *
 * <p>Acceptance ladder (widest set without changing what the journal stores):
 * <ol>
 *   <li>fast path — the whole string is valid JSON;</li>
 *   <li>strip a single markdown code fence and re-parse its body;</li>
 *   <li>extract the first <em>balanced</em> top-level {@code {...}} or {@code [...]}
 *       substring (brace counting that skips brackets inside string literals).</li>
 * </ol>
 */
public final class WorkflowJsonExtractor {

    private WorkflowJsonExtractor() {
    }

    /**
     * Matches a single markdown code fence, capturing its body. Optional
     * {@code json} language tag; non-greedy body so the first fence wins.
     */
    private static final Pattern CODE_FENCE = Pattern.compile(
            "```(?:json|JSON)?\\s*\\r?\\n?([\\s\\S]*?)```");

    /**
     * Tolerantly parse a model response into a {@link JsonNode}.
     *
     * @param resp the raw model output (may contain prose / code fences)
     * @param om   the ObjectMapper to parse with
     * @return the parsed node, or {@code null} when no JSON could be recovered
     *         (callers preserve their existing null contract — schema retry on
     *         the live path, IllegalStateException on the replay path)
     */
    public static JsonNode tolerantReadTree(String resp, ObjectMapper om) {
        if (resp == null || resp.isBlank() || om == null) {
            return null;
        }
        // 1. fast path — pure JSON.
        JsonNode direct = readTreeOrNull(resp, om);
        if (direct != null) {
            return direct;
        }
        // 2. strip a markdown code fence (```json ... ``` or ``` ... ```).
        String unfenced = stripCodeFence(resp);
        if (unfenced != null) {
            JsonNode fenced = readTreeOrNull(unfenced, om);
            if (fenced != null) {
                return fenced;
            }
        }
        // 3. first balanced top-level {...} / [...] substring. Try the fence body
        // first (tighter), then the raw response.
        for (String candidate : new String[] {unfenced, resp}) {
            if (candidate == null) {
                continue;
            }
            JsonNode extracted = readTreeOrNull(extractBalancedJson(candidate), om);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private static JsonNode readTreeOrNull(String s, ObjectMapper om) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the body of the first markdown code fence (```json … ``` or ``` …
     * ```), trimmed, or {@code null} when the string has no fence.
     */
    private static String stripCodeFence(String resp) {
        Matcher m = CODE_FENCE.matcher(resp);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Extracts the first balanced top-level JSON object/array substring. Brace
     * counting ignores {@code { } [ ]} characters that appear inside string
     * literals (tracking quotes + backslash escapes), so prose punctuation and
     * braces embedded in string values never throw the balance off. Returns
     * {@code null} when no opening bracket is found or the structure never
     * closes.
     */
    private static String extractBalancedJson(String s) {
        if (s == null) {
            return null;
        }
        int start = -1;
        char open = 0;
        char close = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                start = i; open = '{'; close = '}'; break;
            }
            if (c == '[') {
                start = i; open = '['; close = ']'; break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null; // never balanced
    }
}
