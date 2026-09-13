package com.changlu.agentforge.llm.openai;

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
 * OpenAI Chat Completions implementation of {@link ChatModel}.
 *
 * <p>The base URL is configurable, so OpenAI-compatible providers can reuse the
 * same adapter when they implement the Chat Completions protocol.</p>
 */
public final class OpenAiChatModel implements ChatModel {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String baseUrl;
    private final String apiKey;
    private final DefaultChatRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private OpenAiChatModel(Builder builder) {
        this.baseUrl = trimTrailingSlash(builder.baseUrl);
        this.apiKey = builder.apiKey;
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

        Map<String, Object> payload = buildPayload(chatRequest, parameters);
        HttpRequest request = HttpRequest.builder()
                .url(baseUrl + "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .headers(customHeaders)
                .body(Json.stringify(payload))
                .connectTimeoutMillis(connectTimeoutMillis)
                .readTimeoutMillis(readTimeoutMillis)
                .build();

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            request = copyWithHeader(request, "Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse response = httpTransport.execute(request);
            if (!response.isSuccessful()) {
                throw new LlmException("OpenAI request failed with HTTP " + response.statusCode(),
                        response.statusCode(), response.body());
            }
            return parseResponse(response.body());
        } catch (IOException e) {
            throw new LlmException("OpenAI request failed", e);
        }
    }

    private static Map<String, Object> buildPayload(ChatRequest request, ChatRequestParameters parameters) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        if (parameters.customParameters() != null) {
            payload.putAll(parameters.customParameters());
        }
        payload.put("model", parameters.modelName());
        payload.put("messages", toMessages(request.messages()));
        putIfNotNull(payload, "temperature", parameters.temperature());
        putIfNotNull(payload, "max_tokens", parameters.maxTokens());
        putIfNotNull(payload, "top_p", parameters.topP());
        putIfNotNull(payload, "stop", parameters.stopSequences());
        return payload;
    }

    private static List<Map<String, Object>> toMessages(List<ChatMessage> messages) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>(messages.size());
        for (ChatMessage message : messages) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", toOpenAiRole(message.type()));
            item.put("content", message.text());
            result.add(item);
        }
        return result;
    }

    private static String toOpenAiRole(ChatMessageType type) {
        if (type == ChatMessageType.SYSTEM) return "system";
        if (type == ChatMessageType.USER) return "user";
        if (type == ChatMessageType.AI) return "assistant";
        throw new IllegalArgumentException("Unsupported message type: " + type);
    }

    private static ChatResponse parseResponse(String body) {
        Map<String, Object> root = Json.parseObject(body);
        List<Object> choices = Json.array(root.get("choices"));
        if (choices == null || choices.isEmpty()) {
            throw new LlmException("OpenAI response does not contain choices", null, body);
        }
        Map<String, Object> choice = Json.object(choices.get(0));
        Map<String, Object> message = choice == null ? null : Json.object(choice.get("message"));
        if (message == null) {
            throw new LlmException("OpenAI response does not contain a message", null, body);
        }

        String text = extractContent(message.get("content"));
        ChatResponse.Builder response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text == null ? "" : text))
                .finishReason(mapFinishReason(choice.get("finish_reason")))
                .metadata("id", root.get("id"))
                .metadata("model", root.get("model"))
                .metadata("created", root.get("created"));

        Map<String, Object> usage = Json.object(root.get("usage"));
        if (usage != null) {
            long input = Json.longValue(usage.get("prompt_tokens"), 0L);
            long output = Json.longValue(usage.get("completion_tokens"), 0L);
            long total = Json.longValue(usage.get("total_tokens"), input + output);
            response.tokenUsage(new TokenUsage(input, output, total));
        }
        return response.build();
    }

    private static String extractContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        List<Object> blocks = Json.array(content);
        if (blocks == null) return String.valueOf(content);
        StringBuilder result = new StringBuilder();
        for (Object blockValue : blocks) {
            Map<String, Object> block = Json.object(blockValue);
            if (block == null) continue;
            Object text = block.get("text");
            if (text != null) result.append(String.valueOf(text));
        }
        return result.toString();
    }

    private static FinishReason mapFinishReason(Object reasonValue) {
        String reason = Json.string(reasonValue);
        if (reason == null) return null;
        if ("stop".equals(reason)) return FinishReason.STOP;
        if ("length".equals(reason)) return FinishReason.LENGTH;
        if ("tool_calls".equals(reason) || "function_call".equals(reason)) return FinishReason.TOOL_EXECUTION;
        if ("content_filter".equals(reason)) return FinishReason.CONTENT_FILTER;
        return FinishReason.OTHER;
    }

    private static HttpRequest copyWithHeader(HttpRequest source, String name, String value) {
        return HttpRequest.builder()
                .url(source.url())
                .method(source.method())
                .headers(source.headers())
                .header(name, value)
                .body(source.body())
                .connectTimeoutMillis(source.connectTimeoutMillis())
                .readTimeoutMillis(source.readTimeoutMillis())
                .build();
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static void requireModelName(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalStateException("OpenAI modelName must be configured on the model or request");
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
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
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

        public OpenAiChatModel build() {
            return new OpenAiChatModel(this);
        }
    }
}
