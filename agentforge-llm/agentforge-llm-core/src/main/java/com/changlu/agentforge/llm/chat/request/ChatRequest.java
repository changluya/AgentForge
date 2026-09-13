package com.changlu.agentforge.llm.chat.request;

import com.changlu.agentforge.llm.chat.message.ChatMessage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable provider-neutral chat request.
 */
public final class ChatRequest {

    private final List<ChatMessage> messages;
    private final ChatRequestParameters parameters;

    private ChatRequest(Builder builder) {
        if (builder.messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(builder.messages));
        this.parameters = builder.parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ChatMessage> messages() {
        return messages;
    }

    public ChatRequestParameters parameters() {
        return parameters;
    }

    public static final class Builder {
        private final List<ChatMessage> messages = new ArrayList<ChatMessage>();
        private ChatRequestParameters parameters;

        private Builder() {
        }

        public Builder message(ChatMessage message) {
            this.messages.add(Objects.requireNonNull(message, "message"));
            return this;
        }

        public Builder messages(Collection<? extends ChatMessage> messages) {
            Objects.requireNonNull(messages, "messages");
            for (ChatMessage message : messages) {
                message(message);
            }
            return this;
        }

        public Builder parameters(ChatRequestParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(this);
        }
    }
}
