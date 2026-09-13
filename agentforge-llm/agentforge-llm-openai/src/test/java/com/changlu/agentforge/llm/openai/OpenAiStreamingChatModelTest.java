package com.changlu.agentforge.llm.openai;

import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;
import com.changlu.agentforge.llm.exception.LlmException;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpResponse;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.http.StreamingHttpResponseHandler;
import com.changlu.agentforge.llm.internal.json.Json;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests OpenAI Chat Completions streaming behavior. The real-endpoint test is
 * opt-in and is skipped when no endpoint configuration is provided.
 *
 * @author changlu
 * @date 2026/09/13
 */
public class OpenAiStreamingChatModelTest {

    // Modify these constants when you want to run the real-endpoint test.
    private static final String REAL_ENDPOINT_BASE_URL = "https://api.openai.com/v1";
    private static final String REAL_ENDPOINT_API_KEY = "";
    private static final String REAL_ENDPOINT_MODEL_NAME = "gpt-4o-mini";

    /**
     * Optional real-endpoint verification.
     *
     * <p>Modify the three {@code REAL_ENDPOINT_*} constants above before running this test.</p>
     * <ul>
     *     <li>Use {@code REAL_ENDPOINT_BASE_URL} for the OpenAI-compatible endpoint.</li>
     *     <li>Use {@code REAL_ENDPOINT_API_KEY} for the API key.</li>
     *     <li>Use {@code REAL_ENDPOINT_MODEL_NAME} for the model name.</li>
     * </ul>
     *
     * <p>When the API key is blank, the test is skipped so normal unit-test runs
     * do not make a network request. The endpoint may be any OpenAI-compatible service.</p>
     */
    @Test
    public void shouldStreamFromRealEndpointWithUserConfiguration() throws InterruptedException {
        String baseUrl = REAL_ENDPOINT_BASE_URL;
        String apiKey = REAL_ENDPOINT_API_KEY;
        String modelName = REAL_ENDPOINT_MODEL_NAME;

        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            System.out.println("Skip real OpenAI streaming endpoint test: modify the REAL_ENDPOINT_* constants first.");
            return;
        }

        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .maxTokens(64)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat(ChatRequest.builder()
                .message(UserMessage.from("Reply with one short sentence confirming the connection works."))
                .build(), handler);

        assertTrue("Timed out waiting for the streaming response",
                handler.await(60, TimeUnit.SECONDS));
        assertNull(handler.error);
        assertNotNull(handler.completeResponse);
        assertNotNull(handler.completeResponse.aiMessage());
        assertTrue("The streaming response should contain text",
                !isBlank(handler.completeResponse.aiMessage().text()));

        System.out.println("Real OpenAI streaming endpoint test succeeded.");
        System.out.println("model=" + handler.completeResponse.metadata().get("model"));
        System.out.println("finishReason=" + handler.completeResponse.finishReason());
        System.out.println("answer=" + handler.completeResponse.aiMessage().text());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Test
    public void shouldStreamPartialResponsesAndBuildCompleteResponse() {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"created\":123,\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"finish_reason\":null}]}",
                "",
                "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":null}]}",
                "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: {\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}",
                "data: [DONE]");

        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey("test-key")
                .modelName("gpt-test")
                .temperature(0.2)
                .customHeader("X-Test", "yes")
                .httpTransport(transport)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat(ChatRequest.builder()
                .message(SystemMessage.from("Be concise"))
                .message(UserMessage.from("Hi"))
                .build(), handler);

        assertEquals(Arrays.asList("Hel", "lo"), handler.partials);
        assertNull(handler.error);
        assertNotNull(handler.completeResponse);
        assertEquals("Hello", handler.completeResponse.aiMessage().text());
        assertEquals(FinishReason.STOP, handler.completeResponse.finishReason());
        assertEquals(4L, handler.completeResponse.tokenUsage().inputTokens());
        assertEquals(2L, handler.completeResponse.tokenUsage().outputTokens());
        assertEquals(6L, handler.completeResponse.tokenUsage().totalTokens());
        assertEquals("chatcmpl-1", handler.completeResponse.metadata().get("id"));
        assertEquals("gpt-test", handler.completeResponse.metadata().get("model"));
        assertEquals(123L, ((Number) handler.completeResponse.metadata().get("created")).longValue());

        assertEquals("https://api.openai.com/v1/chat/completions", transport.lastRequest.url());
        assertEquals("Bearer test-key", transport.lastRequest.headers().get("Authorization"));
        assertEquals("text/event-stream", transport.lastRequest.headers().get("Accept"));
        assertEquals("yes", transport.lastRequest.headers().get("X-Test"));

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("gpt-test", payload.get("model"));
        assertEquals(Boolean.TRUE, payload.get("stream"));
        assertEquals(0.2d, ((Number) payload.get("temperature")).doubleValue(), 0.0d);
        Map<String, Object> streamOptions = Json.object(payload.get("stream_options"));
        assertEquals(Boolean.TRUE, streamOptions.get("include_usage"));

        List<Object> messages = Json.array(payload.get("messages"));
        assertEquals(2, messages.size());
        assertMessage(messages.get(0), "system", "Be concise");
        assertMessage(messages.get(1), "user", "Hi");
    }

    @Test
    public void shouldApplyRequestParametersOverModelDefaults() {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}]}",
                "data: [DONE]");
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName("default-model")
                .temperature(0.1)
                .maxTokens(100)
                .topP(0.8)
                .customParameter("seed", 1)
                .httpTransport(transport)
                .build();

        DefaultChatRequestParameters overrides = DefaultChatRequestParameters.builder()
                .modelName("request-model")
                .temperature(0.7)
                .maxTokens(20)
                .topP(0.9)
                .customParameter("seed", 2)
                .build();
        RecordingHandler handler = new RecordingHandler();
        model.chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .parameters(overrides)
                .build(), handler);

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("request-model", payload.get("model"));
        assertEquals(0.7d, ((Number) payload.get("temperature")).doubleValue(), 0.0d);
        assertEquals(20L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(0.9d, ((Number) payload.get("top_p")).doubleValue(), 0.0d);
        assertEquals(2L, ((Number) payload.get("seed")).longValue());
        assertEquals("ok", handler.completeResponse.aiMessage().text());
        assertEquals(FinishReason.LENGTH, handler.completeResponse.finishReason());
    }

    @Test
    public void shouldWorkWithOpenAiCompatibleEndpointWithoutApiKey() {
        StreamingTransport transport = StreamingTransport.success(
                "data: {\"choices\":[{\"delta\":{\"content\":\"local\"},\"finish_reason\":\"stop\"}]}"
                // Intentionally no [DONE]: EOF should still produce the final response.
        );
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl("http://localhost:11434/v1/")
                .modelName("local-model")
                .httpTransport(transport)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertEquals("http://localhost:11434/v1/chat/completions", transport.lastRequest.url());
        assertFalse(transport.lastRequest.headers().containsKey("Authorization"));
        assertEquals("local", handler.completeResponse.aiMessage().text());
    }

    @Test
    public void shouldExposeHttpFailureAsLlmException() {
        StreamingTransport transport = new StreamingTransport(429,
                Collections.singletonList("{\"error\":{\"message\":\"rate limit\"}}"), null);
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertNull(handler.completeResponse);
        assertTrue(handler.error instanceof LlmException);
        LlmException error = (LlmException) handler.error;
        assertEquals(Integer.valueOf(429), error.statusCode());
        assertTrue(error.responseBody().contains("rate limit"));
    }

    @Test
    public void shouldReportMalformedSseChunk() {
        StreamingTransport transport = StreamingTransport.success("data: {not-json}");
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertNull(handler.completeResponse);
        assertTrue(handler.error instanceof LlmException);
        assertTrue(handler.error.getMessage().contains("parse"));
    }

    @Test
    public void shouldWrapStreamingTransportFailure() {
        StreamingTransport transport = new StreamingTransport(200,
                Collections.<String>emptyList(), new IOException("network down"));
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();
        RecordingHandler handler = new RecordingHandler();

        model.chat("hello", handler);

        assertTrue(handler.error instanceof LlmException);
        assertNotNull(handler.error.getCause());
        assertEquals("network down", handler.error.getCause().getMessage());
    }

    @Test
    public void shouldValidateModelAndHandler() {
        final StreamingTransport transport = StreamingTransport.success("data: [DONE]");
        final OpenAiStreamingChatModel missingModel = OpenAiStreamingChatModel.builder()
                .httpTransport(transport)
                .build();
        final ChatRequest request = ChatRequest.builder().message(UserMessage.from("hi")).build();
        final RecordingHandler handler = new RecordingHandler();

        assertThrows(IllegalStateException.class, () -> missingModel.chat(request, handler));

        final OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();
        assertThrows(IllegalArgumentException.class, () -> model.chat((ChatRequest) null, handler));
        assertThrows(IllegalArgumentException.class, () -> model.chat(request, null));
    }

    @SuppressWarnings("unchecked")
    private static void assertMessage(Object value, String role, String content) {
        Map<String, Object> message = (Map<String, Object>) value;
        assertEquals(role, message.get("role"));
        assertEquals(content, message.get("content"));
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

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completion.await(timeout, unit);
        }
    }

    private static final class StreamingTransport implements HttpTransport {
        private final int statusCode;
        private final List<String> lines;
        private final Throwable failure;
        private HttpRequest lastRequest;

        private StreamingTransport(int statusCode, List<String> lines, Throwable failure) {
            this.statusCode = statusCode;
            this.lines = lines;
            this.failure = failure;
        }

        private static StreamingTransport success(String... lines) {
            return new StreamingTransport(200, Arrays.asList(lines), null);
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("not used by streaming tests");
        }

        @Override
        public void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
            this.lastRequest = request;
            if (failure != null) {
                handler.onError(failure);
                return;
            }
            handler.onOpen(statusCode, Collections.<String, List<String>>emptyMap());
            for (String line : lines) {
                handler.onLine(line);
            }
            handler.onComplete();
        }
    }
}
