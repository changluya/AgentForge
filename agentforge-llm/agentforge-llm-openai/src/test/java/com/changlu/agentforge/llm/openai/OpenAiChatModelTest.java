package com.changlu.agentforge.llm.openai;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OpenAiChatModelTest {

    /**
     * Optional real-endpoint verification.
     *
     * <p>Configure the following three values before running this test:</p>
     * <ul>
     *     <li>{@code -Dagentforge.openai.base-url=https://api.openai.com/v1}</li>
     *     <li>{@code -Dagentforge.openai.api-key=...}</li>
     *     <li>{@code -Dagentforge.openai.model-name=...}</li>
     * </ul>
     *
     * <p>The equivalent environment variables are
     * {@code AGENTFORGE_OPENAI_BASE_URL}, {@code AGENTFORGE_OPENAI_API_KEY},
     * and {@code AGENTFORGE_OPENAI_MODEL_NAME}. The system properties take
     * precedence. When the values are not configured, the test is skipped so
     * normal unit-test runs do not make a network request. Once configured,
     * the real request is executed and any HTTP/transport/response exception
     * fails the test; no provider-specific response assertion is required.</p>
     */
    @Test
    public void shouldCallRealEndpointWithUserConfiguration() {
        /*
         * 配置方式一：环境变量（推荐）
         *
         *   AGENTFORGE_OPENAI_BASE_URL=https://api.openai.com/v1
         *   AGENTFORGE_OPENAI_API_KEY=sk-xxxxxxxx
         *   AGENTFORGE_OPENAI_MODEL_NAME=gpt-4o-mini
         *
         * macOS/Linux 可以在执行 Maven 前配置：
         *
         *   export AGENTFORGE_OPENAI_BASE_URL="https://api.openai.com/v1"
         *   export AGENTFORGE_OPENAI_API_KEY="sk-xxxxxxxx"
         *   export AGENTFORGE_OPENAI_MODEL_NAME="gpt-4o-mini"
         *
         * Windows PowerShell：
         *
         *   $env:AGENTFORGE_OPENAI_BASE_URL="https://api.openai.com/v1"
         *   $env:AGENTFORGE_OPENAI_API_KEY="sk-xxxxxxxx"
         *   $env:AGENTFORGE_OPENAI_MODEL_NAME="gpt-4o-mini"
         *
         * 配置方式二：JVM 参数
         *
         *   mvn -pl agentforge-llm/agentforge-llm-openai -Dagentforge.openai.base-url="https://api.openai.com/v1" -Dagentforge.openai.api-key="sk-xxxxxxxx" -Dagentforge.openai.model-name="gpt-4o-mini" -Dtest=OpenAiChatModelTest#shouldCallRealEndpointWithUserConfiguration test
         *
         * IntelliJ IDEA：Run/Edit Configurations -> Environment variables，
         * 添加上面的三个 AGENTFORGE_OPENAI_* 变量即可。
         * baseUrl 也可以替换为其他 OpenAI 兼容服务的接口地址。
         */
        String baseUrl = configuredValue("agentforge.openai.base-url", "AGENTFORGE_OPENAI_BASE_URL");
        String apiKey = configuredValue("agentforge.openai.api-key", "AGENTFORGE_OPENAI_API_KEY");
        String modelName = configuredValue("agentforge.openai.model-name", "AGENTFORGE_OPENAI_MODEL_NAME");

        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(modelName)) {
            System.out.println("Skip real OpenAI endpoint test: configure "
                    + "baseUrl, apiKey and modelName with system properties or environment variables.");
            return;
        }

        ChatResponse response = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .maxTokens(64)
                .build()
                .chat(ChatRequest.builder()
                        .message(UserMessage.from("Reply with one short sentence confirming the connection works."))
                        .build());

        System.out.println("Real OpenAI endpoint test succeeded.");
        System.out.println("model=" + response.metadata().get("model"));
        System.out.println("finishReason=" + response.finishReason());
        System.out.println("answer=" + response.aiMessage().text());
    }

    private static String configuredValue(String systemProperty, String environmentVariable) {
        String value = System.getProperty(systemProperty);
        if (!isBlank(value)) return value.trim();
        value = System.getenv(environmentVariable);
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Test
    public void shouldMapRequestAndNormalizeResponse() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"id\":\"chatcmpl-1\",\"model\":\"gpt-test\",\"created\":123," +
                        "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello!\"},\"finish_reason\":\"stop\"}]," +
                        "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}"));

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://gateway.example/v1/")
                .apiKey("secret")
                .modelName("gpt-default")
                .temperature(0.2)
                .maxTokens(256)
                .customHeader("X-Project", "agentforge")
                .httpTransport(transport)
                .connectTimeoutMillis(1234)
                .readTimeoutMillis(5678)
                .build();

        DefaultChatRequestParameters requestParameters = DefaultChatRequestParameters.builder()
                .modelName("gpt-request")
                .temperature(0.7)
                .topP(0.9)
                .stopSequences(Arrays.asList("END"))
                .customParameter("seed", 42)
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .message(SystemMessage.from("Be concise"))
                .message(UserMessage.from("Hi"))
                .message(AiMessage.from("Earlier answer"))
                .parameters(requestParameters)
                .build());

        HttpRequest request = transport.lastRequest;
        assertNotNull(request);
        assertEquals("https://gateway.example/v1/chat/completions", request.url());
        assertEquals("POST", request.method());
        assertEquals("Bearer secret", request.headers().get("Authorization"));
        assertEquals("agentforge", request.headers().get("X-Project"));
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals(1234, request.connectTimeoutMillis());
        assertEquals(5678, request.readTimeoutMillis());

        Map<String, Object> payload = Json.parseObject(request.body());
        assertEquals("gpt-request", payload.get("model"));
        assertEquals(0.7d, ((Number) payload.get("temperature")).doubleValue(), 0.00001d);
        assertEquals(256L, ((Number) payload.get("max_tokens")).longValue());
        assertEquals(0.9d, ((Number) payload.get("top_p")).doubleValue(), 0.00001d);
        assertEquals(Arrays.asList("END"), payload.get("stop"));
        assertEquals(42L, ((Number) payload.get("seed")).longValue());

        List<Object> messages = Json.array(payload.get("messages"));
        assertEquals(3, messages.size());
        assertMessage(messages.get(0), "system", "Be concise");
        assertMessage(messages.get(1), "user", "Hi");
        assertMessage(messages.get(2), "assistant", "Earlier answer");

        assertEquals("Hello!", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(4L, response.tokenUsage().inputTokens());
        assertEquals(2L, response.tokenUsage().outputTokens());
        assertEquals(6L, response.tokenUsage().totalTokens());
        assertEquals("chatcmpl-1", response.metadata().get("id"));
        assertEquals("gpt-test", response.metadata().get("model"));
    }

    @Test
    public void shouldWorkWithoutApiKeyForOpenAiCompatibleEndpoint() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"length\"}]}"));

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("http://localhost:11434/v1")
                .modelName("local-model")
                .httpTransport(transport)
                .build();

        ChatResponse response = model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build());

        assertFalse(transport.lastRequest.headers().containsKey("Authorization"));
        assertEquals("ok", response.aiMessage().text());
        assertEquals(FinishReason.LENGTH, response.finishReason());
        assertNull(response.tokenUsage());
    }

    @Test
    public void shouldMapToolCallFinishReasonAndArrayContent() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]}," +
                        "\"finish_reason\":\"tool_calls\"}]}"));
        OpenAiChatModel model = OpenAiChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();

        ChatResponse response = model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build());

        assertEquals("AB", response.aiMessage().text());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
    }

    @Test
    public void shouldExposeHttpFailureAsLlmException() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(429, "{\"error\":\"rate_limit\"}"));
        OpenAiChatModel model = OpenAiChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(transport)
                .build();

        LlmException error = assertThrows(LlmException.class,
                () -> model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build()));

        assertEquals(Integer.valueOf(429), error.statusCode());
        assertTrue(error.responseBody().contains("rate_limit"));
    }

    @Test
    public void shouldWrapTransportIOException() {
        HttpTransport failing = new HttpTransport() {
            @Override
            public HttpResponse execute(HttpRequest request) throws IOException {
                throw new IOException("network down");
            }
        };
        OpenAiChatModel model = OpenAiChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(failing)
                .build();

        LlmException error = assertThrows(LlmException.class,
                () -> model.chat(ChatRequest.builder().message(UserMessage.from("hi")).build()));
        assertNotNull(error.getCause());
        assertEquals("network down", error.getCause().getMessage());
    }

    @Test
    public void shouldValidateModelNameAndResponseShape() {
        final ChatRequest request = ChatRequest.builder().message(UserMessage.from("hi")).build();
        OpenAiChatModel missingModel = OpenAiChatModel.builder()
                .httpTransport(new CapturingTransport(new HttpResponse(200, "{}")))
                .build();
        assertThrows(IllegalStateException.class, () -> missingModel.chat(request));

        OpenAiChatModel badResponse = OpenAiChatModel.builder()
                .modelName("gpt-test")
                .httpTransport(new CapturingTransport(new HttpResponse(200, "{\"choices\":[]}")))
                .build();
        assertThrows(LlmException.class, () -> badResponse.chat(request));
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
