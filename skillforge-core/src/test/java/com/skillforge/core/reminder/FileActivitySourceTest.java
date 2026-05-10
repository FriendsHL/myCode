package com.skillforge.core.reminder;

import com.skillforge.core.compact.recovery.FileStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileActivitySourceTest {

    private ReminderBuilder builder;
    private FileStateCache cache;

    @BeforeEach
    void setUp() {
        builder = new ReminderBuilder(List.of(), 5_000, true);
        cache = new FileStateCache();
    }

    private ReminderContext ctxAtTurn(int turn) {
        return new ReminderContext("s1", 1L, turn,
                Collections.emptyList(), 200_000,
                "", List.of(), 0, null, null, builder);
    }

    @Test
    @DisplayName("disabled → never emits")
    void disabled_skip() {
        cache.put("s1", "/foo.txt", "x");
        FileActivitySource src = new FileActivitySource(cache, false, 5, 5, 0);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isFalse();
    }

    @Test
    @DisplayName("empty cache → no emit")
    void emptyCache_skip() {
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isFalse();
    }

    @Test
    @DisplayName("non-empty cache, minAgeSeconds=0 → emits with path + age")
    void nonEmpty_emits() throws InterruptedException {
        cache.put("s1", "/abs/path/foo.java", "public class Foo {}\n");
        Thread.sleep(2);
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isTrue();
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNotNull();
        assertThat(e.text()).contains("Recent files");
        assertThat(e.text()).contains("/abs/path/foo.java");
        assertThat(e.text()).contains("ago)");
        assertThat(e.estimatedTokens()).isGreaterThan(0);
    }

    @Test
    @DisplayName("all entries younger than minAgeSeconds → emit returns null")
    void allTooYoung_returnsNull() {
        cache.put("s1", "/just-touched.txt", "data");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 3_600);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNull();
    }

    @Test
    @DisplayName("W5: shouldEmit returns false when all entries are too young (no wasted emit)")
    void w5_shouldEmit_filtersOnMinAge() {
        cache.put("s1", "/just-touched.txt", "data");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 3_600);
        assertThat(src.shouldEmit(ctxAtTurn(0)))
                .as("W5: shouldEmit must filter out all-too-young entries to avoid emit/null pair")
                .isFalse();
    }

    @Test
    @DisplayName("debounce: interval=5 holds for 4 turns then re-allows")
    void debounce_5turns() {
        cache.put("s1", "/foo.txt", "x");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        assertThat(src.shouldEmit(ctxAtTurn(0))).isTrue();
        src.emit(ctxAtTurn(0));
        for (int t = 1; t < 5; t++) {
            assertThat(src.shouldEmit(ctxAtTurn(t)))
                    .as("turn %d should be debounced", t).isFalse();
        }
        assertThat(src.shouldEmit(ctxAtTurn(5))).isTrue();
    }

    @Test
    @DisplayName("emit updates per-session lastEmitted state when actually rendering")
    void emit_updatesBuilderState() {
        cache.put("s1", "/foo.txt", "x");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        assertThat(builder.getLastEmitted("s1", "file-activity")).isNull();
        src.emit(ctxAtTurn(3));
        assertThat(builder.getLastEmitted("s1", "file-activity")).isEqualTo(3);
    }

    @Test
    @DisplayName("emit does NOT update state when filtered down to nothing (all too young)")
    void emit_noStateUpdateWhenSkipped() {
        cache.put("s1", "/foo.txt", "x");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 3_600);
        ReminderEntry e = src.emit(ctxAtTurn(3));
        assertThat(e).isNull();
        assertThat(builder.getLastEmitted("s1", "file-activity")).isNull();
    }

    @Test
    @DisplayName("multiple files concatenated with comma separator")
    void multipleFiles_commaSeparated() throws InterruptedException {
        cache.put("s1", "/a.java", "x");
        Thread.sleep(2);
        cache.put("s1", "/b.java", "y");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        ReminderEntry e = src.emit(ctxAtTurn(0));
        assertThat(e).isNotNull();
        assertThat(e.text()).contains("/a.java");
        assertThat(e.text()).contains("/b.java");
        assertThat(e.text()).contains(", ");
    }

    @Test
    @DisplayName("null sessionId → shouldEmit=false (defensive)")
    void nullSessionId_skip() {
        cache.put("s1", "/foo.txt", "x");
        FileActivitySource src = new FileActivitySource(cache, true, 5, 5, 0);
        ReminderContext ctx = new ReminderContext(null, 1L, 0,
                Collections.emptyList(), 200_000,
                "", List.of(), 0, null, null, builder);
        assertThat(src.shouldEmit(ctx)).isFalse();
    }

    @Test
    @DisplayName("formatAgeSeconds buckets")
    void formatAge_buckets() {
        assertThat(FileActivitySource.formatAgeSeconds(45)).isEqualTo("45s");
        assertThat(FileActivitySource.formatAgeSeconds(120)).isEqualTo("2 min");
        assertThat(FileActivitySource.formatAgeSeconds(3 * 3600)).isEqualTo("3h");
        assertThat(FileActivitySource.formatAgeSeconds(86_400L * 5)).isEqualTo("5d");
    }
}
