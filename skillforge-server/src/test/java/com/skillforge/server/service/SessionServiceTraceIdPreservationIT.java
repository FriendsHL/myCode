package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.Message;
import com.skillforge.server.AbstractPostgresIT;
import com.skillforge.server.config.SessionMessageStoreProperties;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.SessionMessageRepository;
import com.skillforge.server.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-2 Q1 regression coverage: light compaction (no boundary) used to wipe the
 * entire {@code t_session_message.trace_id} column because
 * {@code rewriteMessages} ran a DELETE+INSERT with default 3-arg AppendMessage
 * (traceId=null). This IT pins the auto-preserve layer in
 * {@link SessionService#rewriteMessages} that snapshots existing trace_ids by
 * seq_no and patches them back onto rewritten rows whose AppendMessage
 * traceId is null.
 *
 * <p>Also covers {@link SessionService#findTailTraceIds} which the full-compact
 * retained-block preservation in {@code CompactionService} relies on.
 */
@DisplayName("SessionService trace_id preservation across rewriteMessages")
class SessionServiceTraceIdPreservationIT extends AbstractPostgresIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionMessageRepository sessionMessageRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionMessageRepository.deleteAll();
        sessionRepository.deleteAll();
        sessionService = new SessionService(
                sessionRepository,
                sessionMessageRepository,
                agentRepository,
                new SessionMessageStoreProperties(), // defaults: rowWrite/rowRead 均 true
                new ObjectMapper(),
                transactionManager
        );
    }

    private String newSession() {
        SessionEntity s = new SessionEntity();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(1L);
        s.setAgentId(10L);
        s.setTitle("traceid-preserve-it");
        s.setStatus("active");
        s.setRuntimeStatus("idle");
        sessionRepository.save(s);
        return s.getId();
    }

    @Test
    @DisplayName("rewriteMessages auto-preserves trace_ids when caller passes null")
    void rewriteMessages_autoPreservesTraceIds_whenCallerPassesNull() {
        // Arrange: seed 3 rows with explicit trace_ids via the 7-arg constructor.
        String sid = newSession();
        sessionService.appendMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("turn 1 user"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-A"),
                new SessionService.AppendMessage(
                        Message.assistant("turn 1 assistant"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-B"),
                new SessionService.AppendMessage(
                        Message.user("turn 2 user"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-C")
        ));

        // Sanity: trace_ids landed on the seeded rows.
        List<SessionService.StoredMessage> seeded = sessionService.getFullHistoryRecords(sid);
        assertThat(seeded).hasSize(3);
        assertThat(seeded.get(0).traceId()).isEqualTo("trace-A");
        assertThat(seeded.get(1).traceId()).isEqualTo("trace-B");
        assertThat(seeded.get(2).traceId()).isEqualTo("trace-C");

        // Act: rewrite with 3 fresh AppendMessages (3-arg → traceId default null).
        // Mimics light-no-boundary path: same row count, content slightly mutated
        // (e.g. truncated tool output).
        List<SessionService.AppendMessage> rewritten = new ArrayList<>();
        rewritten.add(new SessionService.AppendMessage(
                Message.user("turn 1 user (truncated)"),
                SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
        rewritten.add(new SessionService.AppendMessage(
                Message.assistant("turn 1 assistant (truncated)"),
                SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
        rewritten.add(new SessionService.AppendMessage(
                Message.user("turn 2 user (truncated)"),
                SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()));
        sessionService.rewriteMessages(sid, rewritten);

        // Assert: rewritten rows carry the original trace_ids by seq_no alignment.
        List<SessionService.StoredMessage> after = sessionService.getFullHistoryRecords(sid);
        assertThat(after).hasSize(3);
        assertThat(after.get(0).traceId()).isEqualTo("trace-A");
        assertThat(after.get(1).traceId()).isEqualTo("trace-B");
        assertThat(after.get(2).traceId()).isEqualTo("trace-C");
        // And the new content actually landed (not a no-op).
        assertThat(after.get(0).message().getTextContent()).contains("(truncated)");
    }

    @Test
    @DisplayName("rewriteMessages does NOT overwrite caller-provided non-null trace_ids")
    void rewriteMessages_doesNotOverwriteExplicitTraceIds() {
        // Arrange: seed a row with trace-A.
        String sid = newSession();
        sessionService.appendMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("seeded"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-A")
        ));

        // Act: rewrite with an explicit DIFFERENT trace_id at the same index.
        sessionService.rewriteMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("rewritten"),
                        SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-NEW")
        ));

        // Assert: caller value wins (auto-preserve only fills nulls).
        List<SessionService.StoredMessage> after = sessionService.getFullHistoryRecords(sid);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).traceId()).isEqualTo("trace-NEW");
    }

    @Test
    @DisplayName("rewriteMessages no-ops trace_id patch when no historical rows had a trace_id")
    void rewriteMessages_noPatch_whenNoHistoricalTraceIds() {
        // Arrange: seed via legacy 3-arg path (traceId=null on every row).
        String sid = newSession();
        sessionService.rewriteMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("a"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()),
                new SessionService.AppendMessage(
                        Message.user("b"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())
        ));
        List<SessionService.StoredMessage> seeded = sessionService.getFullHistoryRecords(sid);
        assertThat(seeded).hasSize(2);
        assertThat(seeded.get(0).traceId()).isNull();
        assertThat(seeded.get(1).traceId()).isNull();

        // Act: rewrite again with null trace_ids.
        sessionService.rewriteMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("a2"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap()),
                new SessionService.AppendMessage(
                        Message.user("b2"), SessionService.MSG_TYPE_NORMAL, Collections.emptyMap())
        ));

        // Assert: still null on every row, no spurious values appeared.
        List<SessionService.StoredMessage> after = sessionService.getFullHistoryRecords(sid);
        assertThat(after).hasSize(2);
        assertThat(after.get(0).traceId()).isNull();
        assertThat(after.get(1).traceId()).isNull();
    }

    @Test
    @DisplayName("findTailTraceIds returns last N trace_ids in seq_no ASC order, preserving nulls")
    void findTailTraceIds_returnsTailInAscOrder_withNullsPreserved() {
        // Arrange: 4 rows — only middle two have trace_ids (mimics a session that
        // started before OBS-2 M1 and gained trace_ids partway through).
        String sid = newSession();
        sessionService.appendMessages(sid, List.of(
                new SessionService.AppendMessage(
                        Message.user("r0"), SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), null),
                new SessionService.AppendMessage(
                        Message.user("r1"), SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-1"),
                new SessionService.AppendMessage(
                        Message.user("r2"), SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), "trace-2"),
                new SessionService.AppendMessage(
                        Message.user("r3"), SessionService.MSG_TYPE_NORMAL,
                        SessionService.MESSAGE_TYPE_NORMAL,
                        null, null, Collections.emptyMap(), null)
        ));

        // Act + Assert: last 3 → seq_nos 1,2,3 → [trace-1, trace-2, null]
        List<String> tail3 = sessionService.findTailTraceIds(sid, 3);
        assertThat(tail3).containsExactly("trace-1", "trace-2", null);

        // Last 2 → seq_nos 2,3 → [trace-2, null]
        List<String> tail2 = sessionService.findTailTraceIds(sid, 2);
        assertThat(tail2).containsExactly("trace-2", null);

        // n == 0 → empty (no DB hit, see guard).
        assertThat(sessionService.findTailTraceIds(sid, 0)).isEmpty();
        // n > rows → only what exists.
        assertThat(sessionService.findTailTraceIds(sid, 99)).hasSize(4);
    }
}
