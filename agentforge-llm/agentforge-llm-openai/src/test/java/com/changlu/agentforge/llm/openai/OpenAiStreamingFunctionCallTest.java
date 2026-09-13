package com.changlu.agentforge.llm.openai;

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
 * Verifies that streamed OpenAI {@code tool_calls} delta fragments are merged by index
 * into complete {@link ToolExecutionRequest}s on the final {@link ChatResponse}.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class OpenAiStreamingFunctionCallTest {

    @Test
    public void shouldMergeSingleStreamedToolCall() throws InterruptedException {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null}}]}",
                "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"\"}}]}}]}",
                "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
                "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"hangzhou\\\"}\"}}]}}]}",
                "data: {\"id\":\"c1\",\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

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
        assertEquals("call_1", request.id());
        assertEquals("getWeather", request.name());
        assertEquals("{\"city\":\"hangzhou\"}", request.arguments());
    }

    @Test
    public void shouldMergeMultipleInterleavedStreamedToolCalls() throws InterruptedException {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                        "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"weather\"}}," +
                        "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"weather\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                        "{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"hz\\\"}\"}}," +
                        "{\"index\":1,\"function\":{\"arguments\":\"{\\\"city\\\":\\\"bj\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

        RecordingHandler handler = new RecordingHandler();
        model(transport).chat("weather for two cities", handler);

        assertTrue(handler.await());
        List<ToolExecutionRequest> requests = handler.completeResponse.aiMessage().toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("call_a", requests.get(0).id());
        assertEquals("{\"city\":\"hz\"}", requests.get(0).arguments());
        assertEquals("call_b", requests.get(1).id());
        assertEquals("{\"city\":\"bj\"}", requests.get(1).arguments());
    }

    @Test
    public void shouldMergeTextAndToolCallInSameStream() throws InterruptedException {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Checking\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" weather\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"city\\\":\\\"hangzhou\\\"}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

        RecordingHandler handler = new RecordingHandler();
        model(transport).chat("weather?", handler);

        assertTrue(handler.await());
        assertEquals(Arrays.asList("Checking", " weather"), handler.partials);
        AiMessage aiMessage = handler.completeResponse.aiMessage();
        assertEquals("Checking weather", aiMessage.text());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        assertEquals("call_x", aiMessage.toolExecutionRequests().get(0).id());
    }

    @Test
    public void shouldHandleStreamedToolCallsWithoutIndex() throws InterruptedException {
        // Some OpenAI-compatible gateways omit the index field. The first fragment carries
        // the id/name, later fragments only carry argument deltas.
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call_n\",\"function\":{\"name\":\"getWeather\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "data: [DONE]");

        RecordingHandler handler = new RecordingHandler();
        model(transport).chat("weather?", handler);

        assertTrue(handler.await());
        List<ToolExecutionRequest> requests = handler.completeResponse.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("call_n", requests.get(0).id());
        assertEquals("getWeather", requests.get(0).name());
        assertEquals("{\"a\":1}", requests.get(0).arguments());
    }

    private static OpenAiStreamingChatModel model(HttpTransport transport) {
        return OpenAiStreamingChatModel.builder()
                .apiKey("test-key")
                .modelName("gpt-test")
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
        private final List<String> lines;
        private HttpRequest lastRequest;

        private StreamingTransport(List<String> lines) {
            this.lines = lines;
        }

        private static StreamingTransport success(String... lines) {
            return new StreamingTransport(Arrays.asList(lines));
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            this.lastRequest = request;
            handler.onOpen(200, Collections.<String, List<String>>emptyMap());
            for (String line : lines) {
                handler.onLine(line);
            }
            handler.onComplete();
        }
    }
}