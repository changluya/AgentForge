package com.changlu.agentforge.llm.openai;

import com.changlu.agentforge.llm.chat.StreamingChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequestParameters;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;
import com.changlu.agentforge.llm.chat.response.TokenUsage;
import com.changlu.agentforge.llm.exception.LlmException;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.http.JdkHttpTransport;
import com.changlu.agentforge.llm.http.StreamingHttpResponseHandler;
import com.changlu.agentforge.llm.internal.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions streaming implementation of {@link StreamingChatModel}.
 *
 * <p>The implementation follows the same high-level contract used by LangChain4j:
 * the request is sent with {@code stream=true}, token usage is requested through
 * {@code stream_options.include_usage=true}, every SSE delta is forwarded as a partial
 * response, and all chunks are accumulated into a normalized {@link ChatResponse}.</p>
 *
 * <p>The configurable base URL also allows OpenAI-compatible endpoints to reuse the
 * same implementation.</p>
 *
 * @author changlu
 * @date 2026/09/13
 */
public class OpenAiStreamingChatModel implements StreamingChatModel {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String baseUrl;
    private final String apiKey;
    private final DefaultChatRequestParameters defaultParameters;
    private final Map<String, String> customHeaders;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private OpenAiStreamingChatModel(Builder builder) {
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
        this.customHeaders = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(builder.customHeaders));
        this.httpTransport = builder.httpTransport;
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        if (chatRequest == null) {
            throw new IllegalArgumentException("chatRequest must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }

        DefaultChatRequestParameters parameters = DefaultChatRequestParameters.merge(
                defaultParameters, chatRequest.parameters());
        requireModelName(parameters.modelName());

        HttpRequest request = HttpRequest.builder()
                .url(baseUrl + "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .headers(customHeaders)
                .body(Json.stringify(buildPayload(chatRequest, parameters)))
                .connectTimeoutMillis(connectTimeoutMillis)
                .readTimeoutMillis(readTimeoutMillis)
                .build();

        if (apiKey != null && !apiKey.trim().isEmpty()) {
            request = copyWithHeader(request, "Authorization", "Bearer " + apiKey);
        }

        final OpenAiStreamState state = new OpenAiStreamState(handler);
        try {
            httpTransport.executeStreaming(request, state);
        } catch (Throwable error) {
            state.onError(error);
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

        // Streaming is mandatory for this model, regardless of custom parameter overrides.
        payload.put("stream", Boolean.TRUE);
        LinkedHashMap<String, Object> streamOptions = new LinkedHashMap<String, Object>();
        streamOptions.put("include_usage", Boolean.TRUE);
        payload.put("stream_options", streamOptions);
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
        if ("tool_calls".equals(reason) || "function_call".equals(reason)) {
            return FinishReason.TOOL_EXECUTION;
        }
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

    /**
     * Builder for {@link OpenAiStreamingChatModel}.
     *
     * @author changlu
     * @date 2026/09/13
     */
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

        public OpenAiStreamingChatModel build() {
            return new OpenAiStreamingChatModel(this);
        }
    }

    /**
     * Accumulates OpenAI SSE chunks and maps them into AgentForge streaming callbacks.
     *
     * @author changlu
     * @date 2026/09/13
     */
    private static final class OpenAiStreamState implements StreamingHttpResponseHandler {

        private final StreamingChatResponseHandler handler;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder errorBody = new StringBuilder();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        private int statusCode = -1;
        private FinishReason finishReason;
        private TokenUsage tokenUsage;
        private boolean terminated;

        private OpenAiStreamState(StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        @Override
        public synchronized void onOpen(int statusCode, Map<String, List<String>> headers) {
            if (terminated) return;
            this.statusCode = statusCode;
        }

        @Override
        public synchronized void onLine(String line) {
            if (terminated) return;

            if (!isSuccessfulStatus(statusCode)) {
                if (line != null) {
                    if (errorBody.length() > 0) errorBody.append('\n');
                    errorBody.append(line);
                }
                return;
            }

            if (line == null) return;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) return;
            if (!trimmed.startsWith("data:")) return;

            String data = trimmed.substring("data:".length()).trim();
            if (data.isEmpty()) return;
            if ("[DONE]".equals(data)) {
                complete();
                return;
            }

            try {
                applyChunk(Json.parseObject(data));
            } catch (Throwable error) {
                fail(new LlmException("Failed to parse OpenAI streaming response", error));
            }
        }

        @Override
        public synchronized void onComplete() {
            if (terminated) return;
            if (!isSuccessfulStatus(statusCode)) {
                fail(new LlmException("OpenAI streaming request failed with HTTP " + statusCode,
                        statusCode, errorBody.toString()));
                return;
            }
            complete();
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (error instanceof LlmException) {
                fail(error);
            } else {
                fail(new LlmException("OpenAI streaming request failed", error));
            }
        }

        private void applyChunk(Map<String, Object> root) {
            putMetadata("id", root.get("id"));
            putMetadata("model", root.get("model"));
            putMetadata("created", root.get("created"));

            Map<String, Object> usage = Json.object(root.get("usage"));
            if (usage != null) {
                long input = Json.longValue(usage.get("prompt_tokens"), 0L);
                long output = Json.longValue(usage.get("completion_tokens"), 0L);
                long total = Json.longValue(usage.get("total_tokens"), input + output);
                tokenUsage = new TokenUsage(input, output, total);
            }

            List<Object> choices = Json.array(root.get("choices"));
            if (choices == null || choices.isEmpty()) return;

            Map<String, Object> choice = Json.object(choices.get(0));
            if (choice == null) return;

            FinishReason mappedFinishReason = mapFinishReason(choice.get("finish_reason"));
            if (mappedFinishReason != null) {
                finishReason = mappedFinishReason;
            }

            Map<String, Object> delta = Json.object(choice.get("delta"));
            if (delta == null) return;

            String partial = extractContent(delta.get("content"));
            if (partial == null || partial.isEmpty()) return;

            text.append(partial);
            try {
                handler.onPartialResponse(partial);
            } catch (Throwable callbackError) {
                fail(callbackError);
            }
        }

        private void putMetadata(String key, Object value) {
            if (value != null) metadata.put(key, value);
        }

        private void complete() {
            if (terminated) return;
            terminated = true;
            ChatResponse.Builder response = ChatResponse.builder()
                    .aiMessage(AiMessage.from(text.toString()))
                    .finishReason(finishReason)
                    .metadata(metadata);
            if (tokenUsage != null) {
                response.tokenUsage(tokenUsage);
            }
            try {
                handler.onCompleteResponse(response.build());
            } catch (Throwable ignored) {
                // Completion callback has no further downstream error channel.
            }
        }

        private void fail(Throwable error) {
            if (terminated) return;
            terminated = true;
            try {
                handler.onError(error);
            } catch (Throwable ignored) {
                // Error callback failures are intentionally swallowed.
            }
        }

        private static boolean isSuccessfulStatus(int statusCode) {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
