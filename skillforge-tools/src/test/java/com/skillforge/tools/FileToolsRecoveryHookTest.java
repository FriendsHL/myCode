package com.skillforge.tools;

import com.skillforge.core.compact.recovery.FileStateCache;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P9-5: verifies FileRead / FileWrite / FileEdit each invoke FileStateCache.put on success.
 */
class FileToolsRecoveryHookTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("FileReadTool.execute records read content into FileStateCache")
    void fileRead_putsCacheEntry() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileReadTool tool = new FileReadTool(cache);
        Path file = tempDir.resolve("foo.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n");
        SkillContext ctx = new SkillContext(tempDir.toString(), "sess-1", 1L);

        SkillResult result = tool.execute(Map.of("file_path", file.toString()), ctx);

        assertThat(result.isSuccess()).isTrue();
        List<FileStateCache.FileEntry> snap = cache.snapshot("sess-1", 5, 10_000);
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).path()).isEqualTo(file.toString());
        assertThat(snap.get(0).headContent()).contains("alpha");
        assertThat(snap.get(0).headContent()).contains("gamma");
    }

    @Test
    @DisplayName("FileReadTool with null cache does not throw and still succeeds")
    void fileRead_nullCache_works() throws Exception {
        FileReadTool tool = new FileReadTool(null);
        Path file = tempDir.resolve("bar.txt");
        Files.writeString(file, "x");
        SkillContext ctx = new SkillContext(tempDir.toString(), "s", 1L);

        SkillResult result = tool.execute(Map.of("file_path", file.toString()), ctx);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("FileWriteTool.execute records written content into FileStateCache")
    void fileWrite_putsCacheEntry() {
        FileStateCache cache = new FileStateCache();
        FileWriteTool tool = new FileWriteTool(cache);
        Path file = tempDir.resolve("written.txt");
        SkillContext ctx = new SkillContext(tempDir.toString(), "sess-2", 1L);

        SkillResult result = tool.execute(
                Map.of("file_path", file.toString(), "content", "wrote-payload"), ctx);

        assertThat(result.isSuccess()).isTrue();
        List<FileStateCache.FileEntry> snap = cache.snapshot("sess-2", 5, 10_000);
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).path()).isEqualTo(file.toString());
        assertThat(snap.get(0).headContent()).isEqualTo("wrote-payload");
    }

    @Test
    @DisplayName("FileWriteTool with null cache does not throw")
    void fileWrite_nullCache_works() {
        FileWriteTool tool = new FileWriteTool(null);
        Path file = tempDir.resolve("a.txt");
        SkillContext ctx = new SkillContext(tempDir.toString(), "s", 1L);
        SkillResult result = tool.execute(
                Map.of("file_path", file.toString(), "content", "x"), ctx);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("FileEditTool.execute records post-edit content into FileStateCache")
    void fileEdit_putsCacheEntry() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileEditTool tool = new FileEditTool(cache);
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "hello world");
        SkillContext ctx = new SkillContext(tempDir.toString(), "sess-3", 1L);

        SkillResult result = tool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "world",
                "new_string", "universe"
        ), ctx);

        assertThat(result.isSuccess()).isTrue();
        List<FileStateCache.FileEntry> snap = cache.snapshot("sess-3", 5, 10_000);
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).headContent()).isEqualTo("hello universe");
    }

    @Test
    @DisplayName("FileEditTool with null cache does not throw")
    void fileEdit_nullCache_works() throws Exception {
        FileEditTool tool = new FileEditTool(null);
        Path file = tempDir.resolve("e.txt");
        Files.writeString(file, "abc");
        SkillContext ctx = new SkillContext(tempDir.toString(), "s", 1L);
        SkillResult result = tool.execute(Map.of(
                "file_path", file.toString(),
                "old_string", "b",
                "new_string", "X"
        ), ctx);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("FileReadTool failure (non-existent file) does not poison cache")
    void fileRead_failure_doesNotPut() {
        FileStateCache cache = new FileStateCache();
        FileReadTool tool = new FileReadTool(cache);
        SkillContext ctx = new SkillContext(tempDir.toString(), "sess-fail", 1L);
        SkillResult result = tool.execute(
                Map.of("file_path", tempDir.resolve("missing.txt").toString()), ctx);
        assertThat(result.isSuccess()).isFalse();
        assertThat(cache.snapshot("sess-fail", 5, 10_000)).isEmpty();
    }
}
