package com.changlu.agentforge.llm.chat.response;

import com.changlu.agentforge.llm.chat.message.AiMessage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Normalized response returned by all AgentForge chat model providers.
 */
public final class ChatResponse {

    private final AiMessage aiMessage;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;

    private ChatResponse(Builder builder) {
        this.aiMessage = Objects.requireNonNull(builder.aiMessage, "aiMessage");
        this.tokenUsage = builder.tokenUsage;
        this.finishReason = builder.finishReason;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.metadata));
    }

    public static Builder builder() {
        return new Builder();
    }

    public AiMessage aiMessage() {
        return aiMessage;
    }

    public TokenUsage tokenUsage() {
        return tokenUsage;
    }

    public FinishReason finishReason() {
        return finishReason;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public static final class Builder {
        private AiMessage aiMessage;
        private TokenUsage tokenUsage;
        private FinishReason finishReason;
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        public Builder aiMessage(AiMessage aiMessage) {
            this.aiMessage = aiMessage;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public Builder finishReason(FinishReason finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null && value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public ChatResponse build() {
            return new ChatResponse(this);
        }
    }
}
