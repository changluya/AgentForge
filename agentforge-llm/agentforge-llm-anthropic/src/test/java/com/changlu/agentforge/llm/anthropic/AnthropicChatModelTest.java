package com.changlu.agentforge.llm.anthropic;

import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.exception.LlmException;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpResponse;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.internal.json.Json;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AnthropicChatModelTest {

    @Test
    public void shouldMapMessagesSystemPromptAndNormalizeResponse() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"id\":\"msg_1\",\"type\":\"message\",\"model\":\"claude-test\"," +
                        "\"content\":[{\"type\":\"text\",\"text\":\"Hello\"},{\"type\":\"text\",\"text\":\" world\"}]," +
                        "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"));

        AnthropicChatModel model = AnthropicChatModel.builder()
                .baseUrl("https://anthropic.example/")
                .apiKey("secret")
                .anthropicVersion("2023-06-01")
                .modelName("claude-default")
                .temperature(0.1)
                .maxTokens(512)
                .customHeader("X-Project", "agentforge")
                .httpTransport(transport)
                .connectTimeoutMillis(111)
                .readTimeoutMillis(222)
                .build();

        DefaultChatRequestParameters requestParameters = DefaultChatRequestParameters.builder()
                .modelName("claude-request")
                .temperature(0.5)
                .topP(0.8)
                .stopSequences(Arrays.asList("DONE"))
                .customParameter("metadata", "test")
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .message(SystemMessage.from("First system"))
                .message(SystemMessage.from("Second system"))
                .message(UserMessage.from("Hi"))
                .message(AiMessage.from("Earlier answer"))
                .parameters(requestParameters)
                .build());

        HttpRequest request = transport.lastRequest;
        assertNotNull(request);
        assertEquals("https://anthropic.example/v1/messages", request.url());
        assertEquals("secret", request.headers().get("x-api-key"));
        assertEquals("2023-06-01", request.headers().get("anthropic-version"));
        assertEquals("agentforge", request.headers().get("X-Project"));
        assertEquals(111, request.connectTimeoutMillis());
        assertEquals(222, request.readTimeoutMillis());

        Map<String, Object> payload = Json.parseObject(request.body());
        assertEquals("claude-request", payload.get("model"));
        assertEquals(512L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(0.5d, ((Number) payload.get("temperature")).doubleValue(), 0.00001d);
        assertEquals(0.8d, ((Number) payload.get("top_p")).doubleValue(), 0.00001d);
        assertEquals(Arrays.asList("DONE"), payload.get("stop_sequences"));
        assertEquals("test", payload.get("metadata"));
        assertEquals("First system\n\nSecond system", payload.get("system"));

        List<Object> messages = Json.array(payload.get("messages"));
        assertEquals(2, messages.size());
        assertMessage(messages.get(0), "user", "Hi");
        assertMessage(messages.get(1), "assistant", "Earlier answer");

        assertEquals("Hello world", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(5L, response.tokenUsage().inputTokens());
        assertEquals(3L, response.tokenUsage().outputTokens());
        assertEquals(8L, response.tokenUsage().totalTokens());
        assertEquals("msg_1", response.metadata().get("id"));
        assertEquals("claude-test", response.metadata().get("model"));
    }

    @Test
    public void shouldDefaultMaxTokensAndMapToolUseFinishReason() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"Use tool\"}],\"stop_reason\":\"tool_use\"}"));
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(transport)
                .build();

        ChatResponse response = model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build());
        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());

        assertEquals(1024L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals("Use tool", response.aiMessage().text());
    }

    @Test
    public void shouldRejectSystemOnlyRequest() {
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(new CapturingTransport(new HttpResponse(200, "{}")))
                .build();

        final ChatRequest request = ChatRequest.builder().message(SystemMessage.from("system only")).build();
        assertThrows(IllegalArgumentException.class, () -> model.chat(request));
    }

    @Test
    public void shouldValidateApiKeyAndModelName() {
        final ChatRequest request = ChatRequest.builder().message(UserMessage.from("hi")).build();

        AnthropicChatModel missingKey = AnthropicChatModel.builder()
                .modelName("claude-test")
                .httpTransport(new CapturingTransport(new HttpResponse(200, "{}")))
                .build();
        assertThrows(IllegalStateException.class, () -> missingKey.chat(request));

        AnthropicChatModel missingModel = AnthropicChatModel.builder()
                .apiKey("secret")
                .httpTransport(new CapturingTransport(new HttpResponse(200, "{}")))
                .build();
        assertThrows(IllegalStateException.class, () -> missingModel.chat(request));
    }

    @Test
    public void shouldExposeHttpFailureAsLlmException() {
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(new CapturingTransport(new HttpResponse(401, "{\"error\":\"unauthorized\"}")))
                .build();

        LlmException error = assertThrows(LlmException.class,
                () -> model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build()));

        assertEquals(Integer.valueOf(401), error.statusCode());
        assertTrue(error.responseBody().contains("unauthorized"));
    }

    @Test
    public void shouldWrapTransportIOException() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) throws IOException {
                throw new IOException("network down");
            }
        };
        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(failing)
                .build();

        LlmException error = assertThrows(LlmException.class,
                () -> model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build()));
        assertNotNull(error.getCause());
        assertEquals("network down", error.getCause().getMessage());
    }

    @SuppressWarnings("unchecked")
    private static void assertMessage(Object value, String role, String content) {
        Map<String, Object> message = (Map<String, Object>) value;
        assertEquals(role, message.get("role"));
        assertEquals(content, message.get("content"));
    }

    private static final class CapturingTransport implements HttpTransport {
        private final HttpResponse response;
        private HttpRequest lastRequest;

        private CapturingTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.lastRequest = request;
            return response;
        }
    }
}
