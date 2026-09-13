package com.changlu.agentforge.llm.chat;

import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral abstraction for chat models that stream their response incrementally.
 *
 * <p>The callback-based API deliberately stays compatible with JDK 8. Provider modules
 * are responsible for translating their native streaming protocol into partial text,
 * a normalized final response, and errors.</p>
 *
 * @author changlu
 * @date 2026/09/13
 */
public interface StreamingChatModel {

    /**
     * Starts a streaming chat request.
     *
     * @param chatRequest complete provider-neutral request
     * @param handler handler receiving partial text, the final response, or an error
     */
    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);

    /**
     * Convenience API for a one-turn user message.
     */
    default void chat(String userMessage, StreamingChatResponseHandler handler) {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(handler, "handler");
        chat(ChatRequest.builder()
                .message(UserMessage.from(userMessage))
                .build(), handler);
    }

    /**
     * Convenience API for a sequence of messages.
     */
    default void chat(StreamingChatResponseHandler handler, ChatMessage... messages) {
        Objects.requireNonNull(messages, "messages");
        chat(Arrays.asList(messages), handler);
    }

    /**
     * Convenience API for a list of messages.
     */
    default void chat(List<? extends ChatMessage> messages, StreamingChatResponseHandler handler) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(handler, "handler");
        chat(ChatRequest.builder().messages(messages).build(), handler);
    }
}
