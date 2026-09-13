package com.changlu.agentforge.llm.chat.message;

/**
 * The kind of content of a {@link Content}, e.g. text or image.
 *
 * <p>Maps to implementations of {@link Content}.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public enum ContentType {

    /**
     * Text content.
     */
    TEXT(TextContent.class);

    private final Class<? extends Content> contentClass;

    ContentType(Class<? extends Content> contentClass) {
        this.contentClass = contentClass;
    }

    /**
     * Returns the concrete {@link Content} class bound to this type.
     *
     * @return the content class
     */
    public Class<? extends Content> contentClass() {
        return contentClass;
    }
}