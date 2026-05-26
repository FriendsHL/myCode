package com.skillforge.core.llm;

import com.skillforge.core.model.ToolUseBlock;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHAT-REASONING-PANEL — verify that {@code delta.reasoning_content} from OpenAI-
 * compatible providers (DeepSeek / Qwen / mimo thinking mode) is routed through
 * {@code handler.onReasoning(...)} rather than {@code handler.onText(...)}.
 *
 * <p>Regression target for the original bug: reasoning_content delta was mixed
 * into the assistant text bubble, leaked into SessionTitleService's title prompt
 * (OpenAiProvider.java:1043 referenced in plan + index.md).
 */
class OpenAiProviderReasoningStreamTest {

    @Test
    @DisplayName("reasoning_content delta routes to onReasoning, not onText")
    void reasoningContentDelta_routesToOnReasoning() throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                "test-key", "http://localhost:1", "deepseek-reasoner",
                "deepseek", "DEEPSEEK_API_KEY", 60, 1);
        RecordingStreamHandler handler = new RecordingStreamHandler();

        invokeProcessSse(provider, sse(
                event("{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think...\"},\"finish_reason\":null}]}"),
                event("{\"choices\":[{\"delta\":{\"reasoning_content\":\" about this problem.\"},\"finish_reason\":null}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"The answer is 42.\"},\"finish_reason\":\"stop\"}]}"),
                "data: [DONE]\n\n"
        ), handler);

        // reasoning chunks land on the dedicated channel
        assertThat(handler.reasoningChunks)
                .containsExactly("Let me think...", " about this problem.");

        // text chunks see only the answer, not the reasoning
        assertThat(handler.textChunks).containsExactly("The answer is 42.");

        // accumulated reasoning is preserved on the final LlmResponse
        assertThat(handler.completed).isNotNull();
        assertThat(handler.completed.getReasoningContent())
                .isEqualTo("Let me think... about this problem.");
    }

    @Test
    @DisplayName("empty reasoning_content delta is skipped (no spurious onReasoning call)")
    void emptyReasoningContentDelta_skipped() throws Exception {
        OpenAiProvider provider = new OpenAiProvider(
                "test-key", "http://localhost:1", "qwen3.5-plus",
                "bailian", "BAILIAN_API_KEY", 60, 1);
        RecordingStreamHandler handler = new RecordingStreamHandler();

        invokeProcessSse(provider, sse(
                event("{\"choices\":[{\"delta\":{\"reasoning_content\":\"\"},\"finish_reason\":null}]}"),
                event("{\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}"),
                "data: [DONE]\n\n"
        ), handler);

        assertThat(handler.reasoningChunks).isEmpty();
        assertThat(handler.textChunks).containsExactly("Hello");
    }

    private static void invokeProcessSse(OpenAiProvider provider, String body, LlmStreamHandler handler)
            throws Exception {
        Method processSse = OpenAiProvider.class.getDeclaredMethod(
                "processSSEStream", Response.class, LlmStreamHandler.class);
        processSse.setAccessible(true);
        processSse.invoke(provider, response(body), handler);
    }

    private static Response response(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://localhost/stream").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("text/event-stream")))
                .build();
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String sse(String... events) {
        return String.join("", events);
    }

    private static final class RecordingStreamHandler implements LlmStreamHandler {
        private final List<String> textChunks = new ArrayList<>();
        private final List<String> reasoningChunks = new ArrayList<>();
        private LlmResponse completed;

        @Override public void onText(String text) {
            textChunks.add(text);
        }

        @Override public void onReasoning(String reasoning) {
            reasoningChunks.add(reasoning);
        }

        @Override public void onToolUse(ToolUseBlock block) {
        }

        @Override public void onComplete(LlmResponse fullResponse) {
            this.completed = fullResponse;
        }

        @Override public void onError(Throwable error) {
            throw new AssertionError(error);
        }
    }
}
