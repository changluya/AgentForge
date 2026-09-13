package com.changlu.agentforge.llm.chat.message;

/**
 * Abstract base interface for message content, following LangChain4j's {@code Content}.
 *
 * <p>Concrete content types such as {@link TextContent} carry a single piece of
 * multimodal input that can be attached to a {@link UserMessage}. The kind of a
 * content is defined by {@link #type()} and can be used to cast it to the correct
 * concrete type.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public interface Content {

    /**
     * Returns the {@link ContentType} of this content.
     *
     * @return the content type
     */
    ContentType type();
}