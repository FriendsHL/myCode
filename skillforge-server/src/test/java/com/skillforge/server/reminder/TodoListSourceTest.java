package com.skillforge.server.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.reminder.ReminderBuilder;
import com.skillforge.core.reminder.ReminderContext;
import com.skillforge.core.reminder.ReminderEntry;
import com.skillforge.server.skill.TodoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoListSourceTest {

    private TodoStore todoStore;
    private ObjectMapper objectMapper;
    private ReminderBuilder builder;

    @BeforeEach
    void setUp() {
        todoStore = new TodoStore();
        objectMapper = new ObjectMapper();
        builder = new ReminderBuilder(List.of(), 5_000, true);
    }

    private ReminderContext ctx(String sessionId, int turnIndex) {
        return new ReminderContext(sessionId, 7L, turnIndex, List.of(), 100_000,
                "", List.of(), 0, objectMapper, null, builder);
    }

    @Test
    @DisplayName("todo list emits grouped active todos from TodoStore")
    void emit_withStoredTodos_groupsByStatus() {
        todoStore.setTasks("sid-1", """
                [
                  {"id":"t1","title":"Implement source","status":"in_progress"},
                  {"id":"t2","title":"Add tests","status":"pending"},
                  {"id":"t3","title":"Read docs","status":"completed"}
                ]
                """);
        TodoListSource source = new TodoListSource(todoStore, objectMapper, true, 1, 20);

        assertThat(source.shouldEmit(ctx("sid-1", 3))).isTrue();
        ReminderEntry entry = source.emit(ctx("sid-1", 3));

        assertThat(entry).isNotNull();
        assertThat(entry.text()).contains("Todos:");
        assertThat(entry.text()).contains("In progress:");
        assertThat(entry.text()).contains("[>] t1: Implement source");
        assertThat(entry.text()).contains("Pending:");
        assertThat(entry.text()).contains("[ ] t2: Add tests");
        assertThat(entry.text()).contains("Completed:");
        assertThat(entry.text()).contains("[x] t3: Read docs");
    }

    @Test
    @DisplayName("empty, disabled, invalid, and missing-session inputs skip safely")
    void shouldEmit_noActionableTodos_skipsSafely() {
        assertThat(new TodoListSource(todoStore, objectMapper, false, 1, 20)
                .shouldEmit(ctx("sid-2", 1))).isFalse();
        assertThat(new TodoListSource(todoStore, objectMapper, true, 1, 20)
                .shouldEmit(ctx("", 1))).isFalse();

        TodoListSource source = new TodoListSource(todoStore, objectMapper, true, 1, 20);
        assertThat(source.shouldEmit(ctx("sid-empty", 1))).isFalse();

        todoStore.setTasks("sid-bad", "{not-json");
        assertThat(source.shouldEmit(ctx("sid-bad", 1))).isFalse();
        assertThat(source.emit(ctx("sid-bad", 1))).isNull();
    }

    @Test
    @DisplayName("maxTodos caps rendered items and reports hidden count")
    void emit_moreThanMaxTodos_truncatesWithHiddenCount() {
        todoStore.setTasks("sid-3", """
                [
                  {"id":"a","title":"A","status":"in_progress"},
                  {"id":"b","title":"B","status":"pending"},
                  {"id":"c","title":"C","status":"pending"}
                ]
                """);
        TodoListSource source = new TodoListSource(todoStore, objectMapper, true, 1, 2);

        ReminderEntry entry = source.emit(ctx("sid-3", 1));

        assertThat(entry).isNotNull();
        assertThat(entry.text()).contains("[>] a: A");
        assertThat(entry.text()).contains("[ ] b: B");
        assertThat(entry.text()).doesNotContain("[ ] c: C");
        assertThat(entry.text()).contains("... 1 more todo not shown");
    }

    @Test
    @DisplayName("debounce state is written only after a successful emit")
    void shouldEmit_afterSuccessfulEmit_respectsIntervalTurns() {
        todoStore.setTasks("sid-4", """
                [{"id":"a","title":"A","status":"pending"}]
                """);
        TodoListSource source = new TodoListSource(todoStore, objectMapper, true, 3, 20);

        assertThat(source.shouldEmit(ctx("sid-4", 10))).isTrue();
        assertThat(source.emit(ctx("sid-4", 10))).isNotNull();

        assertThat(source.shouldEmit(ctx("sid-4", 12))).isFalse();
        assertThat(source.shouldEmit(ctx("sid-4", 13))).isTrue();
    }
}
