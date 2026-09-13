package com.changlu.agentforge.llm.chat.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a message from a user, typically an end user of the application.
 *
 * <p>Following LangChain4j's {@code UserMessage}, a user message can either contain a single
 * text (a {@code String}) or multiple {@link Content}s. It optionally carries a
 * {@link #name()} of the user. Models that do not support names may simply ignore it.</p>
 *
 * <p>The legacy text-first API is preserved through several back-compatible accessors:</p>
 * <ul>
 *   <li>{@link #text()} - returns the text when the message has a single {@link TextContent}.</li>
 *   <li>{@link #contents()} - returns the full list of contents.</li>
 *   <li>{@link #singleText()} - stricter accessor for exactly one {@link TextContent}.</li>
 *   <li>{@link #hasSingleText()} - convenience predicate.</li>
 * </ul>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class UserMessage implements ChatMessage {

    private final String name;
    private final List<Content> contents;

    public UserMessage(String text) {
        this((String) null, TextContent.from(text));
    }

    public UserMessage(String name, String text) {
        this(name, TextContent.from(text));
    }

    public UserMessage(Content... contents) {
        this(null, contents);
    }

    public UserMessage(String name, Content... contents) {
        this.name = name;
        this.contents = immutableList(contents);
    }

    public UserMessage(List<Content> contents) {
        this(null, contents);
    }

    public UserMessage(String name, List<Content> contents) {
        this.name = name;
        this.contents = immutableList(contents);
    }

    /**
     * Returns the name of the user, or {@code null} when not set.
     *
     * @return the user name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the contents of the message.
     *
     * @return immutable list of contents
     */
    public List<Content> contents() {
        return contents;
    }

    /**
     * Returns the text payload of this message when it is build from a single
     * {@link TextContent}. Compatible with the historical text-first API.
     *
     * @return message text
     * @throws UnsupportedOperationException when the message has no single textual payload
     */
    @Override
    public String text() {
        if (hasSingleText()) {
            return ((TextContent) contents.get(0)).text();
        }
        throw new UnsupportedOperationException(
                "UserMessage does not expose a single text payload; use contents() instead");
    }

    /**
     * Checks whether this message contains a single {@link TextContent}.
     *
     * @return {@code true} when the message is a single text message
     */
    public boolean hasSingleText() {
        return contents.size() == 1 && contents.get(0) instanceof TextContent;
    }

    /**
     * Returns the text from a single {@link TextContent}. Use only when certain that the
     * message contains exactly one text content; otherwise throws an exception.
     *
     * @return the single text
     */
    public String singleText() {
        if (hasSingleText()) {
            return ((TextContent) contents.get(0)).text();
        }
        throw new IllegalArgumentException("Expecting a single text content, but got: " + contents);
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.USER;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        UserMessage other = (UserMessage) obj;
        return Objects.equals(name, other.name) && Objects.equals(contents, other.contents);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, contents);
    }

    @Override
    public String toString() {
        return "UserMessage{"
                + "name='" + name + '\''
                + ", contents=" + contents
                + '}';
    }

    /**
     * Creates a new user message from a single text.
     */
    public static UserMessage from(String text) {
        return new UserMessage(text);
    }

    /**
     * Creates a new user message from a user name and a single text.
     */
    public static UserMessage from(String name, String text) {
        return new UserMessage(name, text);
    }

    /**
     * Creates a new user message from one or more contents.
     */
    public static UserMessage from(Content... contents) {
        return new UserMessage(contents);
    }

    /**
     * Creates a new user message from a user name and one or more contents.
     */
    public static UserMessage from(String name, Content... contents) {
        return new UserMessage(name, contents);
    }

    /**
     * Creates a new user message from a list of contents.
     */
    public static UserMessage from(List<Content> contents) {
        return new UserMessage(contents);
    }

    /**
     * Creates a new user message from a user name and a list of contents.
     */
    public static UserMessage from(String name, List<Content> contents) {
        return new UserMessage(name, contents);
    }

    private static List<Content> immutableList(Content[] contents) {
        if (contents == null || contents.length == 0) {
            throw new IllegalArgumentException("UserMessage must contain at least one content");
        }
        ArrayList<Content> list = new ArrayList<Content>(contents.length);
        for (Content content : contents) {
            list.add(Objects.requireNonNull(content, "content"));
        }
        return Collections.unmodifiableList(list);
    }

    private static List<Content> immutableList(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("UserMessage must contain at least one content");
        }
        ArrayList<Content> list = new ArrayList<Content>(contents.size());
        for (Content content : contents) {
            list.add(Objects.requireNonNull(content, "content"));
        }
        return Collections.unmodifiableList(list);
    }
}