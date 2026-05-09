package com.skillforge.core.compact.recovery;

import com.skillforge.core.compact.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * P9-5: in-memory cache of the last-known content of files touched by FileRead/Write/Edit
 * tools, partitioned per session. Feeds the post-compact recovery payload so the LLM, after
 * a full compact "amnesia" point, still knows which files it just looked at.
 *
 * <p>Storage: {@code sessionId -> (path -> FileEntry)} double {@link ConcurrentHashMap}.
 * Same path overwrites; different paths coexist.  Token-budget truncation (head only) is
 * applied at {@link #put} time so memory footprint is bounded per file.
 *
 * <p>Lifecycle: data lives only in memory.  {@link #evictSession(String)} is called from the
 * agent loop's afterLoop hook (and engine try-finally) to drop the per-session map; idempotent.
 *
 * <p>Thread safety: all accesses go through ConcurrentHashMap.  No external locking required;
 * snapshot returns a defensive copy so concurrent mutation during read is safe.
 *
 * <p>Framework-free: this class has no Spring annotations because skillforge-core does not
 * depend on Spring.  It is wired as a {@code @Bean} in skillforge-server (SkillForgeConfig).
 */
public class FileStateCache {

    private static final Logger log = LoggerFactory.getLogger(FileStateCache.class);

    /** Default cap on tokens kept per file (head-only). Caller can pass a custom value. */
    public static final int DEFAULT_MAX_TOKENS_PER_FILE = 5_000;

    /** Conservative chars-per-token used as a first-pass head cut before measuring tokens. */
    private static final int CHARS_PER_TOKEN_HINT = 4;

    private final ConcurrentMap<String, ConcurrentMap<String, FileEntry>> sessionCaches =
            new ConcurrentHashMap<>();

    private final int maxTokensPerFile;

    public FileStateCache() {
        this(DEFAULT_MAX_TOKENS_PER_FILE);
    }

    /** Mainly for tests / explicit budget override at bean wiring time. */
    public FileStateCache(int maxTokensPerFile) {
        this.maxTokensPerFile = maxTokensPerFile > 0 ? maxTokensPerFile : DEFAULT_MAX_TOKENS_PER_FILE;
    }

    /** Immutable per-file entry: path + truncated head + line count + last access time. */
    public record FileEntry(String path, String headContent, int lineCount, Instant lastReadAt) {}

    /**
     * Record / refresh a file entry for {@code sessionId}.  {@code content} is truncated to the
     * configured head-token budget.  null sessionId or path is logged at WARN and skipped (defends
     * against tool calls without a session context, e.g. ad-hoc test invocations).
     */
    public void put(String sessionId, String path, String content) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("FileStateCache.put skipped: null/blank sessionId path={}", path);
            return;
        }
        if (path == null || path.isBlank()) {
            log.warn("FileStateCache.put skipped: null/blank path sessionId={}", sessionId);
            return;
        }
        String safe = content == null ? "" : content;
        String head = truncateToHeadTokens(safe, maxTokensPerFile);
        int lineCount = countLines(safe);
        FileEntry entry = new FileEntry(path, head, lineCount, Instant.now());
        sessionCaches
                .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(path, entry);
    }

    /**
     * Return up to {@code topN} entries (by lastReadAt DESC) whose cumulative token count
     * stays within {@code budgetTokens}.  Empty cache or null/blank sessionId returns an
     * empty list.  Defensive copy — caller can iterate safely.
     */
    public List<FileEntry> snapshot(String sessionId, int topN, int budgetTokens) {
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        ConcurrentMap<String, FileEntry> map = sessionCaches.get(sessionId);
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        List<FileEntry> all = new ArrayList<>(map.values());
        // sort by lastReadAt DESC (most recent first); null instants sort last for safety
        all.sort(Comparator.comparing(
                (FileEntry e) -> e.lastReadAt() == null ? Instant.EPOCH : e.lastReadAt()
        ).reversed());

        int safeTopN = Math.max(0, topN);
        int safeBudget = Math.max(0, budgetTokens);
        List<FileEntry> out = new ArrayList<>(Math.min(safeTopN, all.size()));
        int used = 0;
        for (FileEntry e : all) {
            if (out.size() >= safeTopN) break;
            int cost = TokenEstimator.estimateString(e.headContent());
            if (used + cost > safeBudget && !out.isEmpty()) {
                // budget exhausted; keep what we have (always include first entry even if oversized
                // — otherwise a single huge file would yield empty payload, which loses signal)
                break;
            }
            out.add(e);
            used += cost;
        }
        return out;
    }

    /** Drop the session's cache entry. Idempotent: no-op if session not tracked. */
    public void evictSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionCaches.remove(sessionId);
    }

    /** Test helper: how many sessions are currently tracked. */
    public int sessionCount() {
        return sessionCaches.size();
    }

    /** Test helper: how many files in a given session's cache (0 if not tracked). */
    public int fileCount(String sessionId) {
        ConcurrentMap<String, FileEntry> map = sessionCaches.get(sessionId);
        return map == null ? 0 : map.size();
    }

    // ============= helpers =============

    /**
     * Two-stage head truncation:
     * 1. Cheap char cut at {@code maxTokens * CHARS_PER_TOKEN_HINT} (avoids tokenizing huge files).
     * 2. If the cut prefix still exceeds {@code maxTokens}, fall back to a token-aware refine.
     *
     * <p>UTF-16 safety: every cut point is rolled back through {@link #safeCutLen} so we never
     * split a surrogate pair (high then low halves of supplementary code points such as emoji
     * U+1F600 or CJK supplementary plane). Without this guard, downstream Jackson serialization
     * raises {@code MismatchedSurrogateException}.
     */
    private static String truncateToHeadTokens(String content, int maxTokens) {
        if (content.isEmpty() || maxTokens <= 0) {
            return content;
        }
        int hintChars = (int) Math.min((long) maxTokens * CHARS_PER_TOKEN_HINT, Integer.MAX_VALUE);
        int initialLen = Math.min(content.length(), hintChars);
        String head = content.substring(0, safeCutLen(content, initialLen));
        int tokens = TokenEstimator.estimateString(head);
        if (tokens <= maxTokens) {
            return head;
        }
        // Refine: shrink by ratio. estimateString cost is ~linear; one or two passes is fine.
        int len = head.length();
        for (int attempt = 0; attempt < 3 && tokens > maxTokens && len > 1; attempt++) {
            int target = (int) ((long) len * maxTokens / Math.max(tokens, 1));
            if (target < 1) target = 1;
            int cut = safeCutLen(head, Math.min(target, head.length()));
            head = head.substring(0, cut);
            len = head.length();
            tokens = TokenEstimator.estimateString(head);
        }
        return head;
    }

    /**
     * Return a cut length ≤ {@code requested} that does NOT land between a UTF-16 high
     * surrogate (0xD800-0xDBFF) and its trailing low surrogate (0xDC00-0xDFFF).  If the
     * char immediately before {@code requested} is a high surrogate, step back by 1 so the
     * surrogate pair is fully excluded rather than split.
     */
    static int safeCutLen(String s, int requested) {
        if (requested <= 0) return 0;
        if (requested >= s.length()) return s.length();
        // s.charAt(requested-1) is the LAST included char. If it's a high surrogate, the
        // matching low surrogate is at index `requested` (not yet included) — split case.
        if (Character.isHighSurrogate(s.charAt(requested - 1))) {
            return requested - 1;
        }
        return requested;
    }

    private static int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') n++;
        }
        return n;
    }
}
