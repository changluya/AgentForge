package com.changlu.agentforge.llm.anthropic;

import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpResponse;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.http.StreamingHttpResponseHandler;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that streamed Anthropic {@code tool_use} content blocks are accumulated by
 * index and surfaced as complete {@link ToolExecutionRequest}s on the final response.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class AnthropicStreamingToolUseTest {

    @Test
    public void shouldMergeStreamedToolUseInputJson() throws InterruptedException {
        StreamingTransport transport = StreamingTransport.success(
                sse("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"," +
                        "\"model\":\"claude-test\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}"),
                sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":" +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{}}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":" +
                        "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":" +
                        "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"hangzhou\\\"}\"}}"),
                sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}," +
                        "\"usage\":{\"output_tokens\":7}}"),
                sse("message_stop", "{\"type\":\"message_stop\"}"));

        RecordingHandler handler = new RecordingHandler();
        model(transport).chat(ChatRequest.builder().message(UserMessage.from("weather?")).build(), handler);

        assertTrue(handler.await());
        assertNull(handler.error);
        ChatResponse response = handler.completeResponse;
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());

        AiMessage aiMessage = response.aiMessage();
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(0);
        assertEquals("toolu_1", request.id());
        assertEquals("get_weather", request.name());
        assertEquals("{\"city\":\"hangzhou\"}", request.arguments());
        assertEquals(10L, response.tokenUsage().inputTokens());
        assertEquals(7L, response.tokenUsage().outputTokens());
        assertEquals("msg_1", response.metadata().get("id"));
    }

    @Test
    public void shouldMergeTextAndMultipleToolUses() throws InterruptedException {
        StreamingTransport transport = StreamingTransport.success(
                sse("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\"}}"),
                sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":" +
                        "{\"type\":\"text\",\"text\":\"\"}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":" +
                        "{\"type\":\"text_delta\",\"text\":\"Two \"}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":" +
                        "{\"type\":\"text_delta\",\"text\":\"cities\"}}"),
                sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":" +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_a\",\"name\":\"weather\"}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":" +
                        "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"hz\\\"}\"}}"),
                sse("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":" +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_b\",\"name\":\"weather\"}}"),
                sse("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":" +
                        "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"bj\\\"}\"}}"),
                sse("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}"),
                sse("message_stop", "{\"type\":\"message_stop\"}"));

        RecordingHandler handler = new RecordingHandler();
        model(transport).chat("two cities", handler);

        assertTrue(handler.await());
        assertEquals(Arrays.asList("Two ", "cities"), handler.partials);
        AiMessage aiMessage = handler.completeResponse.aiMessage();
        assertEquals("Two cities", aiMessage.text());

        List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("toolu_a", requests.get(0).id());
        assertEquals("{\"city\":\"hz\"}", requests.get(0).arguments());
        assertEquals("toolu_b", requests.get(1).id());
        assertEquals("{\"city\":\"bj\"}", requests.get(1).arguments());
    }

    private static String sse(String event, String data) {
        // Transport replays lines one by one; blank lines terminate a frame.
        return "event: " + event + "\ndata: " + data + "\n";
    }

    private static AnthropicStreamingChatModel model(HttpTransport transport) {
        return AnthropicStreamingChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(transport)
                .build();
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private final List<String> partials = new ArrayList<String>();
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile ChatResponse completeResponse;
        private volatile Throwable error;

        @Override
        public void onPartialResponse(String partialResponse) {
            partials.add(partialResponse);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            this.completeResponse = completeResponse;
            completion.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            completion.countDown();
        }

        private boolean await() throws InterruptedException {
            return completion.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class StreamingTransport implements HttpTransport {
        private final List<String> rawFrames;
        private HttpRequest lastRequest;

        private StreamingTransport(List<String> rawFrames) {
            this.rawFrames = rawFrames;
        }

        private static StreamingTransport success(String... frames) {
            return new StreamingTransport(Arrays.asList(frames));
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            this.lastRequest = request;
            handler.onOpen(200, Collections.<String, List<String>>emptyMap());
            for (String frame : rawFrames) {
                for (String line : frame.split("\n", -1)) {
                    handler.onLine(line);
                }
            }
            handler.onComplete();
        }
    }
}