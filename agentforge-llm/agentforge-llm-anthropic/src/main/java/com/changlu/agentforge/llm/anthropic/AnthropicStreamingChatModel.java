package com.changlu.agentforge.llm.anthropic;

import com.changlu.agentforge.llm.chat.StreamingChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
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
 * Anthropic Messages API streaming implementation of {@link StreamingChatModel}.
 *
 * <p>The request is sent with {@code stream=true} and the Anthropic SSE event protocol is
 * interpreted as follows:</p>
 * <ul>
 *   <li>{@code message_start}: message metadata and input tokens</li>
 *   <li>{@code content_block_start}: opens a text or {@code tool_use} block</li>
 *   <li>{@code content_block_delta}: {@code text_delta} is forwarded to
 *       {@link StreamingChatResponseHandler#onPartialResponse(String)};
 *       {@code input_json_delta} fragments are appended to the tool call of the same index</li>
 *   <li>{@code message_delta}: stop reason and output tokens</li>
 * </ul>
 *
 * <p>Like LangChain4j, partial tool calls are merged and only exposed as complete
 * {@link ToolExecutionRequest}s on the final {@link ChatResponse}.</p>
 *
 * @author changlu
 * @date 2026-09-13
 */
public class AnthropicStreamingChatModel implements StreamingChatModel {

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

    private AnthropicStreamingChatModel(Builder builder) {
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
        requireApiKey(apiKey);

        HttpRequest request = HttpRequest.builder()
                .url(baseUrl + "/v1/messages")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .headers(customHeaders)
                .body(Json.stringify(buildPayload(chatRequest, parameters)))
                .connectTimeoutMillis(connectTimeoutMillis)
                .readTimeoutMillis(readTimeoutMillis)
                .build();

        final AnthropicStreamState state = new AnthropicStreamState(handler);
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
        payload.put("max_tokens", parameters.maxTokens() == null ? DEFAULT_MAX_TOKENS : parameters.maxTokens());
        putIfNotNull(payload, "temperature", parameters.temperature());
        putIfNotNull(payload, "top_p", parameters.topP());
        putIfNotNull(payload, "stop_sequences", parameters.stopSequences());

        String system = AnthropicProtocol.collectSystemMessages(request.messages());
        if (!system.isEmpty()) {
            payload.put("system", system);
        }
        payload.put("messages", AnthropicProtocol.serializeMessages(request.messages()));
        if (parameters.tools() != null && !parameters.tools().isEmpty()) {
            payload.put("tools", AnthropicProtocol.serializeTools(parameters.tools()));
        }
        putIfNotNull(payload, "tool_choice", AnthropicProtocol.toolChoice(parameters));

        // Streaming is mandatory for this model.
        payload.put("stream", Boolean.TRUE);
        return payload;
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

    /**
     * Builder for {@link AnthropicStreamingChatModel}.
     *
     * @author changlu
     * @date 2026-09-13
     */
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

        public AnthropicStreamingChatModel build() {
            return new AnthropicStreamingChatModel(this);
        }
    }

    /**
     * Accumulates Anthropic SSE events into AgentForge streaming callbacks.
     *
     * @author changlu
     * @date 2026-09-13
     */
    private static final class AnthropicStreamState implements StreamingHttpResponseHandler {

        private final StreamingChatResponseHandler handler;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder errorBody = new StringBuilder();
        private final StringBuilder pendingData = new StringBuilder();
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        private final Map<Integer, ToolUseAccumulator> toolUseAccumulators =
                new LinkedHashMap<Integer, ToolUseAccumulator>();

        private int statusCode = -1;
        private FinishReason finishReason;
        private TokenUsage tokenUsage;
        private boolean terminated;
        private String pendingEvent;

        private AnthropicStreamState(StreamingChatResponseHandler handler) {
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

            if (line.trim().isEmpty()) {
                dispatchFrame();
                return;
            }
            if (line.startsWith(":")) {
                return;
            }
            if (line.startsWith("event:")) {
                pendingEvent = line.substring("event:".length()).trim();
                return;
            }
            if (line.startsWith("data:")) {
                String data = line.substring("data:".length()).trim();
                if (pendingData.length() > 0) pendingData.append('\n');
                pendingData.append(data);
                // Anthropic terminates every frame with a blank line. When no event name was
                // provided, dispatch eagerly so single-line frames still work.
                if (pendingEvent == null) {
                    dispatchFrame();
                }
            }
        }

        @Override
        public synchronized void onComplete() {
            if (terminated) return;
            if (!isSuccessfulStatus(statusCode)) {
                fail(new LlmException("Anthropic streaming request failed with HTTP " + statusCode,
                        statusCode, errorBody.toString()));
                return;
            }
            dispatchFrame();
            complete();
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (error instanceof LlmException) {
                fail(error);
            } else {
                fail(new LlmException("Anthropic streaming request failed", error));
            }
        }

        private void dispatchFrame() {
            String eventName = pendingEvent;
            String data = pendingData.length() == 0 ? null : pendingData.toString().trim();
            pendingEvent = null;
            pendingData.setLength(0);

            if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }
            try {
                applyEvent(eventName, Json.parseObject(data));
            } catch (Throwable error) {
                fail(new LlmException("Failed to parse Anthropic streaming response", error));
            }
        }

        private void applyEvent(String eventName, Map<String, Object> root) {
            if (eventName == null) {
                eventName = Json.string(root.get("type"));
            }

            if ("message_start".equals(eventName)) {
                applyMessageStart(root);
            } else if ("content_block_start".equals(eventName)) {
                applyContentBlockStart(root);
            } else if ("content_block_delta".equals(eventName)) {
                applyContentBlockDelta(root);
            } else if ("message_delta".equals(eventName)) {
                applyMessageDelta(root);
            } else if ("error".equals(eventName)) {
                Map<String, Object> error = Json.object(root.get("error"));
                fail(new LlmException("Anthropic streaming error", null,
                        error == null ? Json.stringify(root) : Json.stringify(error)));
            }
            // content_block_stop / message_stop require no handling.
        }

        private void applyMessageStart(Map<String, Object> root) {
            putMetadata("type", root.get("type"));
            Map<String, Object> message = Json.object(root.get("message"));
            if (message == null) {
                return;
            }
            putMetadata("id", message.get("id"));
            putMetadata("model", message.get("model"));
            applyUsage(Json.object(message.get("usage")));
        }

        private void applyContentBlockStart(Map<String, Object> root) {
            Map<String, Object> block = Json.object(root.get("content_block"));
            if (block == null || !"tool_use".equals(Json.string(block.get("type")))) {
                return;
            }
            ToolUseAccumulator accumulator = new ToolUseAccumulator();
            accumulator.id = Json.string(block.get("id"));
            accumulator.name = Json.string(block.get("name"));
            Object input = block.get("input");
            if (input instanceof Map && !((Map<?, ?>) input).isEmpty()) {
                accumulator.arguments = Json.stringify(input);
            }
            toolUseAccumulators.put(index(root), accumulator);
        }

        private void applyContentBlockDelta(Map<String, Object> root) {
            Map<String, Object> delta = Json.object(root.get("delta"));
            if (delta == null) {
                return;
            }
            String deltaType = Json.string(delta.get("type"));
            if ("text_delta".equals(deltaType)) {
                String partial = Json.string(delta.get("text"));
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                text.append(partial);
                try {
                    handler.onPartialResponse(partial);
                } catch (Throwable callbackError) {
                    fail(callbackError);
                }
                return;
            }
            if ("input_json_delta".equals(deltaType)) {
                ToolUseAccumulator accumulator = toolUseAccumulators.get(index(root));
                String partial = Json.string(delta.get("partial_json"));
                if (accumulator == null || partial == null) {
                    return;
                }
                accumulator.appendArguments(partial);
            }
        }

        private void applyMessageDelta(Map<String, Object> root) {
            Map<String, Object> delta = Json.object(root.get("delta"));
            if (delta != null) {
                FinishReason mapped = mapFinishReason(delta.get("stop_reason"));
                if (mapped != null) {
                    finishReason = mapped;
                }
            }
            applyUsage(Json.object(root.get("usage")));
        }

        private void applyUsage(Map<String, Object> usage) {
            if (usage == null) {
                return;
            }
            long input = Json.longValue(usage.get("input_tokens"),
                    tokenUsage == null ? 0L : tokenUsage.inputTokens());
            long output = Json.longValue(usage.get("output_tokens"),
                    tokenUsage == null ? 0L : tokenUsage.outputTokens());
            tokenUsage = TokenUsage.of(input, output);
        }

        private static int index(Map<String, Object> root) {
            Object value = root.get("index");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        }

        private void putMetadata(String key, Object value) {
            if (value != null) metadata.put(key, value);
        }

        private void complete() {
            if (terminated) return;
            terminated = true;
            List<ToolExecutionRequest> toolExecutionRequests = buildToolExecutionRequests();
            AiMessage aiMessage = toolExecutionRequests.isEmpty()
                    ? AiMessage.from(text.toString())
                    : AiMessage.from(text.length() == 0 ? null : text.toString(), toolExecutionRequests);
            ChatResponse.Builder response = ChatResponse.builder()
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
            for (ToolUseAccumulator accumulator : toolUseAccumulators.values()) {
                requests.add(ToolExecutionRequest.builder()
                        .id(accumulator.id)
                        .name(accumulator.name)
                        .arguments(accumulator.arguments())
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
    }

    /**
     * Holds one streamed {@code tool_use} block until the stream completes.
     */
    private static final class ToolUseAccumulator {
        private String id;
        private String name;
        private String arguments;

        private void appendArguments(String fragment) {
            arguments = (arguments == null ? "" : arguments) + fragment;
        }

        private String arguments() {
            return arguments == null || arguments.trim().isEmpty() ? "{}" : arguments;
        }
    }
}