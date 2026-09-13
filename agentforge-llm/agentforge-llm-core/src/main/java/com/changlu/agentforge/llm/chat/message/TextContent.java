package com.changlu.agentforge.llm.chat.message;

import java.util.Objects;

/**
 * Represents a piece of text content, following LangChain4j's {@code TextContent}.
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class TextContent implements Content {

    private final String text;

    public TextContent(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    public String text() {
        return text;
    }

    @Override
    public ContentType type() {
        return ContentType.TEXT;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TextContent other = (TextContent) obj;
        return Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "TextContent{" + "text='" + text + '\'' + '}';
    }

    /**
     * Creates a new text content.
     *
     * @param text the text
     * @return the text content
     */
    public static TextContent from(String text) {
        return new TextContent(text);
    }
}