package com.changlu.agentforge.llm.anthropic;

import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequestParameters;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.chat.response.TokenUsage;
import com.changlu.agentforge.llm.exception.LlmException;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpResponse;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.http.JdkHttpTransport;
import com.changlu.agentforge.llm.internal.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API implementation of {@link ChatModel}.
 */
public final class AnthropicChatModel implements ChatModel {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final String baseUrl;
    private final String apiKey;
    private final String anthropicVersion;
    private final DefaultChatRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private AnthropicChatModel(Builder builder) {
        this.baseUrl = trimTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.anthropicVersion = builder.anthropicVersion;
        this.defaultParameters = DefaultChatRequestParameters.builder()
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .maxTokens(builder.maxTokens)
                .topP(builder.topP)
                .stopSequences(builder.stopSequences)
                .customParameters(builder.customParameters)
                .build();
        this.customHeaders = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.customHeaders));
        this.httpTransport = builder.httpTransport;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        if (chatRequest == null) {
            throw new IllegalArgumentException("chatRequest must not be null");
        }
        DefaultChatRequestParameters parameters = DefaultChatRequestParameters.merge(
                defaultParameters, chatRequest.parameters());
        requireModelName(parameters.modelName());
        requireApiKey(apiKey);

        Map<String, Object> payload = buildPayload(chatRequest, parameters);
        HttpRequest request = HttpRequest.builder()
                .url(baseUrl + "/v1/messages")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .headers(customHeaders)
                .body(Json.stringify(payload))
                .connectTimeoutMillis(connectTimeoutMillis)
                .readTimeoutMillis(readTimeoutMillis)
                .build();

        try {
            HttpResponse response = httpTransport.execute(request);
            if (!response.isSuccessful()) {
                throw new LlmException("Anthropic request failed with HTTP " + response.statusCode(),
                        response.statusCode(), response.body());
            }
            return parseResponse(response.body());
        } catch (IOException e) {
            throw new LlmException("Anthropic request failed", e);
        }
    }

    private static Map<String, Object> buildPayload(ChatRequest request, ChatRequestParameters parameters) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        if (parameters.customParameters() != null) {
            payload.putAll(parameters.customParameters());
        }
        payload.put("model", parameters.modelName());
        payload.put("max_tokens", parameters.maxTokens() == null ? DEFAULT_MAX_TOKENS : parameters.maxTokens());
        putIfNotNull(payload, "temperature", parameters.temperature());
        putIfNotNull(payload, "top_p", parameters.topP());
        putIfNotNull(payload, "stop_sequences", parameters.stopSequences());

        String system = collectSystemMessages(request.messages());
        if (!system.isEmpty()) {
            payload.put("system", system);
        }
        payload.put("messages", toAnthropicMessages(request.messages()));
        return payload;
    }

    private static String collectSystemMessages(List<ChatMessage> messages) {
        StringBuilder system = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                if (system.length() > 0) system.append("\n\n");
                system.append(message.text());
            }
        }
        return system.toString();
    }

    private static List<Map<String, Object>> toAnthropicMessages(List<ChatMessage> messages) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                continue;
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", message.type() == ChatMessageType.AI ? "assistant" : "user");
            item.put("content", message.text());
            result.add(item);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Anthropic request requires at least one user/assistant message");
        }
        return result;
    }

    private static ChatResponse parseResponse(String body) {
        Map<String, Object> root = Json.parseObject(body);
        String text = extractText(root.get("content"));
        ChatResponse.Builder response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(mapFinishReason(root.get("stop_reason")))
                .metadata("id", root.get("id"))
                .metadata("model", root.get("model"))
                .metadata("type", root.get("type"));

        Map<String, Object> usage = Json.object(root.get("usage"));
        if (usage != null) {
            long input = Json.longValue(usage.get("input_tokens"), 0L);
            long output = Json.longValue(usage.get("output_tokens"), 0L);
            response.tokenUsage(TokenUsage.of(input, output));
        }
        return response.build();
    }

    private static String extractText(Object contentValue) {
        List<Object> blocks = Json.array(contentValue);
        if (blocks == null) {
            return contentValue == null ? "" : String.valueOf(contentValue);
        }
        StringBuilder text = new StringBuilder();
        for (Object blockValue : blocks) {
            Map<String, Object> block = Json.object(blockValue);
            if (block == null) continue;
            if ("text".equals(Json.string(block.get("type"))) && block.get("text") != null) {
                text.append(String.valueOf(block.get("text")));
            }
        }
        return text.toString();
    }

    private static FinishReason mapFinishReason(Object reasonValue) {
        String reason = Json.string(reasonValue);
        if (reason == null) return null;
        if ("end_turn".equals(reason) || "stop_sequence".equals(reason)) return FinishReason.STOP;
        if ("max_tokens".equals(reason)) return FinishReason.LENGTH;
        if ("tool_use".equals(reason)) return FinishReason.TOOL_EXECUTION;
        return FinishReason.OTHER;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static void requireModelName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalStateException("Anthropic modelName must be configured on the model or request");
        }
    }

    private static void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Anthropic apiKey must be configured");
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null || value.trim().isEmpty() ? DEFAULT_BASE_URL : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey;
        private String anthropicVersion = DEFAULT_ANTHROPIC_VERSION;
        private String modelName;
        private Double temperature;
        private Integer maxTokens = DEFAULT_MAX_TOKENS;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> customHeaders = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport = new JdkHttpTransport();
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder anthropicVersion(String anthropicVersion) {
            if (anthropicVersion != null && !anthropicVersion.trim().isEmpty()) {
                this.anthropicVersion = anthropicVersion;
            }
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences == null ? null : new ArrayList<String>(stopSequences);
            return this;
        }

        public Builder customParameter(String name, Object value) {
            if (name != null) this.customParameters.put(name, value);
            return this;
        }

        public Builder customHeader(String name, String value) {
            if (name != null && value != null) this.customHeaders.put(name, value);
            return this;
        }

        public Builder httpTransport(HttpTransport httpTransport) {
            if (httpTransport == null) throw new IllegalArgumentException("httpTransport must not be null");
            this.httpTransport = httpTransport;
            return this;
        }

        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public AnthropicChatModel build() {
            return new AnthropicChatModel(this);
        }
    }
}
