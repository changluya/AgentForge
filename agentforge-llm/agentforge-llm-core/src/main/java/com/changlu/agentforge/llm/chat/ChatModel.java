package com.changlu.agentforge.llm.chat;

import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral chat model abstraction.
 *
 * <p>This is intentionally the lowest stable LLM boundary in AgentForge. Higher-level
 * agent capabilities should depend on this interface instead of a provider SDK.</p>
 */
@FunctionalInterface
public interface ChatModel {

    /**
     * Main API for interacting with a chat-capable language model.
     *
     * @param chatRequest complete provider-neutral request
     * @return normalized chat response
     */
    ChatResponse chat(ChatRequest chatRequest);

    /**
     * Convenience API for the most common one-turn user message.
     */
    default String chat(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        ChatResponse response = chat(ChatRequest.builder()
                .message(UserMessage.from(userMessage))
                .build());
        return response.aiMessage().text();
    }

    /**
     * Convenience API for a sequence of messages.
     */
    default ChatResponse chat(ChatMessage... messages) {
        Objects.requireNonNull(messages, "messages");
        return chat(Arrays.asList(messages));
    }

    /**
     * Convenience API for a list of messages.
     */
    default ChatResponse chat(List<? extends ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        return chat(ChatRequest.builder().messages(messages).build());
    }
}
