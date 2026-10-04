package cloud.changlu.agentforge.model.openai;

import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.chat.message.AiMessage;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.ChatRequestParameters;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.chat.response.FinishReason;
import cloud.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;
import cloud.changlu.agentforge.model.chat.response.TokenUsage;
import cloud.changlu.agentforge.model.exception.ModelException;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.http.StreamingHttpResponseHandler;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions streaming implementation of {@link StreamingChatModel}.
 *
 * <p>The implementation follows the same high-level contract used by LangChain4j: the request is
 * sent with {@code stream=true}, token usage is requested through {@code
 * stream_options.include_usage=true}, every SSE delta is forwarded as a partial response, and all
 * chunks are accumulated into a normalized {@link ChatResponse}.
 *
 * <p>The configurable base URL also allows OpenAI-compatible endpoints to reuse the same
 * implementation.
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
        this.defaultParameters =
                DefaultChatRequestParameters.builder()
                        .modelName(builder.modelName)
                        .temperature(builder.temperature)
                        .maxTokens(builder.maxTokens)
                        .topP(builder.topP)
                        .stopSequences(builder.stopSequences)
                        .customParameters(builder.customParameters)
                        .build();
        this.customHeaders =
                Collections.unmodifiableMap(
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

        DefaultChatRequestParameters parameters =
                DefaultChatRequestParameters.merge(defaultParameters, chatRequest.parameters());
        requireModelName(parameters.modelName());

        HttpRequest request =
                HttpRequest.builder()
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

    private static Map<String, Object> buildPayload(
            ChatRequest request, ChatRequestParameters parameters) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        if (parameters.customParameters() != null) {
            payload.putAll(parameters.customParameters());
        }
        payload.put("model", parameters.modelName());
        payload.put("messages", OpenAiMessages.serialize(request.messages()));
        putIfNotNull(payload, "temperature", parameters.temperature());
        putIfNotNull(payload, "max_tokens", parameters.maxTokens());
        putIfNotNull(payload, "top_p", parameters.topP());
        putIfNotNull(payload, "stop", parameters.stopSequences());
        if (parameters.tools() != null && !parameters.tools().isEmpty()) {
            payload.put("tools", OpenAiMessages.serializeTools(parameters.tools()));
        }
        putIfNotNull(payload, "tool_choice", OpenAiMessages.toolChoice(parameters));

        // Streaming is mandatory for this model, regardless of custom parameter overrides.
        payload.put("stream", Boolean.TRUE);
        LinkedHashMap<String, Object> streamOptions = new LinkedHashMap<String, Object>();
        streamOptions.put("include_usage", Boolean.TRUE);
        payload.put("stream_options", streamOptions);
        return payload;
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
            throw new IllegalStateException(
                    "OpenAI modelName must be configured on the model or request");
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

        private Builder() {}

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
            this.stopSequences =
                    stopSequences == null ? null : new ArrayList<String>(stopSequences);
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
            if (httpTransport == null)
                throw new IllegalArgumentException("httpTransport must not be null");
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
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder errorBody = new StringBuilder();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        private final Map<Integer, ToolCallAccumulator> toolCallAccumulators =
                new LinkedHashMap<Integer, ToolCallAccumulator>();
        private int fallbackToolCallIndex;

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
                fail(new ModelException("Failed to parse OpenAI streaming response", error));
            }
        }

        @Override
        public synchronized void onComplete() {
            if (terminated) return;
            if (!isSuccessfulStatus(statusCode)) {
                fail(
                        new ModelException(
                                "OpenAI streaming request failed with HTTP " + statusCode,
                                statusCode,
                                errorBody.toString()));
                return;
            }
            complete();
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (error instanceof ModelException) {
                fail(error);
            } else {
                fail(new ModelException("OpenAI streaming request failed", error));
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

            String partial = OpenAiMessages.extractContent(delta.get("content"));
            if (partial != null && !partial.isEmpty()) {
                text.append(partial);
                try {
                    handler.onPartialResponse(partial);
                } catch (Throwable callbackError) {
                    fail(callbackError);
                    return;
                }
            }

            String partialThinking = extractThinking(delta);
            if (partialThinking != null && !partialThinking.isEmpty()) {
                thinking.append(partialThinking);
                try {
                    handler.onPartialThinking(partialThinking);
                } catch (Throwable callbackError) {
                    fail(callbackError);
                    return;
                }
            }

            accumulateToolCalls(Json.array(delta.get("tool_calls")));
        }

        /**
         * DeepSeek 及多数 OpenAI-compatible 推理模型把思考内容放在 {@code reasoning_content}，部分实现用 {@code
         * thinking}，这里两者都兼容。
         */
        private static String extractThinking(Map<String, Object> delta) {
            String thinking = Json.string(delta.get("reasoning_content"));
            if (thinking == null || thinking.isEmpty()) {
                thinking = Json.string(delta.get("thinking"));
            }
            return thinking;
        }

        private void accumulateToolCalls(List<Object> toolCallDeltas) {
            if (toolCallDeltas == null) {
                return;
            }
            for (Object deltaValue : toolCallDeltas) {
                Map<String, Object> toolCallDelta = Json.object(deltaValue);
                if (toolCallDelta == null) {
                    continue;
                }
                boolean hasIndex = toolCallDelta.get("index") instanceof Number;
                int index =
                        hasIndex
                                ? ((Number) toolCallDelta.get("index")).intValue()
                                : fallbackToolCallIndex;
                ToolCallAccumulator accumulator = toolCallAccumulators.get(index);
                if (accumulator == null) {
                    accumulator = new ToolCallAccumulator();
                    toolCallAccumulators.put(index, accumulator);
                }
                String id = Json.string(toolCallDelta.get("id"));
                if (id != null && !id.isEmpty() && !id.equals(accumulator.id)) {
                    // A different id without an explicit index marks the start of a new call.
                    if (!hasIndex && accumulator.id != null) {
                        index = ++fallbackToolCallIndex;
                        accumulator = new ToolCallAccumulator();
                        toolCallAccumulators.put(index, accumulator);
                    }
                    accumulator.setId(id);
                }
                Map<String, Object> function = Json.object(toolCallDelta.get("function"));
                if (function == null) {
                    continue;
                }
                if (function.get("name") != null) {
                    accumulator.name.append(Json.string(function.get("name")));
                }
                if (function.get("arguments") != null) {
                    accumulator.arguments.append(Json.string(function.get("arguments")));
                }
            }
        }

        private void putMetadata(String key, Object value) {
            if (value != null) metadata.put(key, value);
        }

        private void complete() {
            if (terminated) return;
            terminated = true;
            List<ToolExecutionRequest> toolExecutionRequests = buildToolExecutionRequests();
            String thinkingText = thinking.length() == 0 ? null : thinking.toString();
            AiMessage aiMessage;
            if (toolExecutionRequests.isEmpty()) {
                aiMessage =
                        thinkingText == null
                                ? AiMessage.from(text.toString())
                                : AiMessage.builder()
                                        .text(text.toString())
                                        .thinking(thinkingText)
                                        .build();
            } else {
                aiMessage =
                        AiMessage.builder()
                                .text(text.length() == 0 ? null : text.toString())
                                .thinking(thinkingText)
                                .toolExecutionRequests(toolExecutionRequests)
                                .build();
            }
            ChatResponse.Builder response =
                    ChatResponse.builder()
                            .aiMessage(aiMessage)
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

        private List<ToolExecutionRequest> buildToolExecutionRequests() {
            ArrayList<ToolExecutionRequest> requests = new ArrayList<ToolExecutionRequest>();
            for (ToolCallAccumulator accumulator : toolCallAccumulators.values()) {
                requests.add(
                        ToolExecutionRequest.builder()
                                .id(accumulator.id)
                                .name(accumulator.name.toString())
                                .arguments(accumulator.arguments.toString())
                                .build());
            }
            return requests;
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

        /** Accumulates one streamed tool call, merging id/name/arguments fragments. */
        private static final class ToolCallAccumulator {
            private String id;
            private final StringBuilder name = new StringBuilder();
            private final StringBuilder arguments = new StringBuilder();

            private void setId(String id) {
                if (id != null && !id.isEmpty()) {
                    this.id = id;
                }
            }
        }
    }
}
