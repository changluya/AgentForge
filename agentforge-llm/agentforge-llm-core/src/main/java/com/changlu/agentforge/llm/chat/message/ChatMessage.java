package com.changlu.agentforge.llm.chat.message;

/**
 * Represents a provider-neutral chat message.
 *
 * <p>The message kind is defined by {@link #type()}. Text-based messages such as
 * {@link SystemMessage}, {@link UserMessage}, {@link AiMessage} and
 * {@link ToolExecutionResultMessage} expose textual content through {@link #text()}.
 * {@link CustomMessage} is attribute-based and therefore has no textual payload.</p>
 *
 * <p>The default {@code text()} method is kept as a small AgentForge compatibility
 * bridge for the current text-first LLM API. New message implementations are not
 * required to be text based.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public interface ChatMessage {

    ChatMessageType type();

    /**
     * Returns the text payload when this message is text based.
     *
     * @return message text
     * @throws UnsupportedOperationException when this message has no textual payload
     */
    default String text() {
        throw new UnsupportedOperationException(
                "Message type " + type() + " does not expose a text payload");
    }
}
