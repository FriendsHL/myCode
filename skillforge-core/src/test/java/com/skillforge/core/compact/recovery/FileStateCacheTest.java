package com.skillforge.core.compact.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileStateCacheTest {

    @Test
    @DisplayName("put then snapshot returns the entry")
    void put_thenSnapshot_returnsEntry() throws InterruptedException {
        FileStateCache cache = new FileStateCache();
        cache.put("s1", "/a.txt", "hello\nworld\n");

        List<FileStateCache.FileEntry> snap = cache.snapshot("s1", 5, 10_000);
        assertThat(snap).hasSize(1);
        FileStateCache.FileEntry e = snap.get(0);
        assertThat(e.path()).isEqualTo("/a.txt");
        assertThat(e.headContent()).contains("hello");
        assertThat(e.lineCount()).isEqualTo(3); // 2 newlines → 3 lines
        assertThat(e.lastReadAt()).isNotNull();
    }

    @Test
    @DisplayName("put same path overwrites and refreshes lastReadAt")
    void put_samePath_overwrites() throws InterruptedException {
        FileStateCache cache = new FileStateCache();
        cache.put("s1", "/a.txt", "first");
        var first = cache.snapshot("s1", 5, 10_000).get(0);

        Thread.sleep(15); // ensure Instant.now() advances on fast machines
        cache.put("s1", "/a.txt", "second content");
        var second = cache.snapshot("s1", 5, 10_000).get(0);

        assertThat(cache.fileCount("s1")).isEqualTo(1); // overwrite, not append
        assertThat(second.headContent()).isEqualTo("second content");
        assertThat(second.lastReadAt()).isAfterOrEqualTo(first.lastReadAt());
    }

    @Test
    @DisplayName("put truncates content to head-token budget")
    void put_largeContent_truncatedToBudget() {
        // budget = 100 tokens (~400 chars). Content well above that.
        FileStateCache cache = new FileStateCache(100);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5_000; i++) big.append("alpha ");
        cache.put("s1", "/big.txt", big.toString());

        var entry = cache.snapshot("s1", 5, 100_000).get(0);
        // head token estimate must respect the cap (allow some tokenizer slack)
        int headTokens = com.skillforge.core.compact.TokenEstimator.estimateString(entry.headContent());
        assertThat(headTokens).isLessThanOrEqualTo(100 + 5);
        // truncated content is shorter than original (assert non-trivial truncation)
        assertThat(entry.headContent().length()).isLessThan(big.length());
    }

    @Test
    @DisplayName("snapshot returns entries sorted by lastReadAt DESC")
    void snapshot_sortsByLastReadDesc() throws InterruptedException {
        FileStateCache cache = new FileStateCache();
        cache.put("s1", "/old.txt", "old");
        Thread.sleep(15);
        cache.put("s1", "/mid.txt", "mid");
        Thread.sleep(15);
        cache.put("s1", "/new.txt", "new");

        List<FileStateCache.FileEntry> snap = cache.snapshot("s1", 5, 10_000);
        assertThat(snap).extracting(FileStateCache.FileEntry::path)
                .containsExactly("/new.txt", "/mid.txt", "/old.txt");
    }

    @Test
    @DisplayName("snapshot honors topN even when more entries exist")
    void snapshot_topNTruncation() throws InterruptedException {
        FileStateCache cache = new FileStateCache();
        for (int i = 0; i < 10; i++) {
            cache.put("s1", "/f" + i + ".txt", "content " + i);
            Thread.sleep(2);
        }
        List<FileStateCache.FileEntry> snap = cache.snapshot("s1", 3, 10_000);
        assertThat(snap).hasSize(3);
        // Must be the 3 most recent
        assertThat(snap).extracting(FileStateCache.FileEntry::path)
                .containsExactly("/f9.txt", "/f8.txt", "/f7.txt");
    }

    @Test
    @DisplayName("snapshot stops accumulating when budget would overflow (after first entry)")
    void snapshot_budgetTruncation() {
        FileStateCache cache = new FileStateCache(1_000);
        // each "huge_<n>" file: ~200 token after truncation; budget allows ~3 of 5
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < 800; i++) block.append("word ");
        for (int i = 0; i < 5; i++) {
            cache.put("s1", "/huge_" + i + ".txt", block.toString());
        }
        // budget = 500 tokens → fewer than 5 fit; first must always be included.
        List<FileStateCache.FileEntry> snap = cache.snapshot("s1", 5, 500);
        assertThat(snap).isNotEmpty();
        assertThat(snap.size()).isLessThan(5);
    }

    @Test
    @DisplayName("snapshot of unknown session returns empty list")
    void snapshot_unknownSession_returnsEmpty() {
        FileStateCache cache = new FileStateCache();
        cache.put("s1", "/a.txt", "x");
        assertThat(cache.snapshot("s2", 5, 10_000)).isEmpty();
        assertThat(cache.snapshot(null, 5, 10_000)).isEmpty();
        assertThat(cache.snapshot("", 5, 10_000)).isEmpty();
    }

    @Test
    @DisplayName("evictSession is idempotent and only affects given session")
    void evictSession_isolated_idempotent() {
        FileStateCache cache = new FileStateCache();
        cache.put("sA", "/a.txt", "A");
        cache.put("sB", "/b.txt", "B");

        cache.evictSession("sA");
        cache.evictSession("sA"); // idempotent
        cache.evictSession("nonexistent"); // idempotent
        cache.evictSession(null); // null-safe

        assertThat(cache.snapshot("sA", 5, 10_000)).isEmpty();
        assertThat(cache.snapshot("sB", 5, 10_000)).hasSize(1);
        assertThat(cache.fileCount("sA")).isEqualTo(0);
        assertThat(cache.fileCount("sB")).isEqualTo(1);
    }

    @Test
    @DisplayName("put with null sessionId or null path is skipped without throwing")
    void put_nullInputs_skipped() {
        FileStateCache cache = new FileStateCache();
        // none of these should throw
        cache.put(null, "/a.txt", "x");
        cache.put("", "/a.txt", "x");
        cache.put("s1", null, "x");
        cache.put("s1", "", "x");

        assertThat(cache.fileCount("s1")).isEqualTo(0);
        assertThat(cache.sessionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("put with null content treats it as empty string")
    void put_nullContent_storedAsEmpty() {
        FileStateCache cache = new FileStateCache();
        cache.put("s1", "/a.txt", null);
        var entry = cache.snapshot("s1", 5, 10_000).get(0);
        assertThat(entry.headContent()).isEmpty();
        assertThat(entry.lineCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("constructor maxTokensPerFile actually controls truncation cap")
    void constructorBudget_isHonored() {
        // budget = 30 tokens.  Build content that would ~easily exceed when measured.
        FileStateCache small = new FileStateCache(30);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1_000; i++) big.append("token ");
        small.put("s", "/big.txt", big.toString());
        var entry = small.snapshot("s", 5, 100_000).get(0);
        int headTokens = com.skillforge.core.compact.TokenEstimator.estimateString(entry.headContent());
        // allow tokenizer slack, but must clearly respect 30-cap (not the legacy 5000 default).
        assertThat(headTokens).isLessThanOrEqualTo(35);
    }

    @Test
    @DisplayName("W1: truncation never splits a UTF-16 surrogate pair (emoji-safe)")
    void truncation_doesNotSplitSurrogate() {
        // Emoji 😀 = U+1F600 = high D83D + low DE00 (one supplementary code point, two chars).
        // Build a string of N emoji + filler so the head budget cuts INSIDE the emoji region.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("😀"); // 😀 × 200 → 400 chars
        for (int i = 0; i < 5_000; i++) sb.append('a');           // filler so chars-per-token cut bites
        // budget = 50 tokens.  CHARS_PER_TOKEN_HINT=4 → initial cut at 200 chars (mid-emoji region).
        FileStateCache cache = new FileStateCache(50);
        cache.put("s", "/emoji.txt", sb.toString());

        var entry = cache.snapshot("s", 5, 100_000).get(0);
        String head = entry.headContent();
        // Last char of head must NOT be a high surrogate (otherwise we'd split a pair).
        if (!head.isEmpty()) {
            char last = head.charAt(head.length() - 1);
            assertThat(Character.isHighSurrogate(last))
                    .as("last char of truncated head must not be a high surrogate")
                    .isFalse();
        }
        // Independent corroboration: the truncated head must be a valid UTF-16 string —
        // if a surrogate was split, the JVM's UTF-8 encoder would emit replacement chars
        // (encode→decode round-trip is lossy for orphan surrogates).
        byte[] utf8 = head.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String roundTripped = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(roundTripped).isEqualTo(head);
    }

    @Test
    @DisplayName("W1 (unit): safeCutLen rolls back when requested would split a surrogate pair")
    void safeCutLen_rollsBackOnHighSurrogate() {
        String s = "ab😀cd"; // a, b, [high, low], c, d  — len 6
        // requested = 3 → would land between high (idx 2) and low (idx 3); roll back to 2.
        assertThat(FileStateCache.safeCutLen(s, 3)).isEqualTo(2);
        // requested = 4 → cuts AFTER the full surrogate pair; OK to keep.
        assertThat(FileStateCache.safeCutLen(s, 4)).isEqualTo(4);
        // requested = 0 / negative / past-end behave normally
        assertThat(FileStateCache.safeCutLen(s, 0)).isEqualTo(0);
        assertThat(FileStateCache.safeCutLen(s, -1)).isEqualTo(0);
        assertThat(FileStateCache.safeCutLen(s, 99)).isEqualTo(s.length());
    }
}
