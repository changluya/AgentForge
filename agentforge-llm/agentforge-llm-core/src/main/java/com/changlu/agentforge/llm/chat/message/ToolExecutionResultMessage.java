package com.changlu.agentforge.llm.chat.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the result returned by a tool execution.
 *
 * <p>This first AgentForge implementation keeps tool results text based so it fits
 * the current LLM core without introducing the multimodal Content hierarchy yet.
 * The API already carries the important LangChain4j-style metadata needed by later
 * tool-calling work: tool call id, tool name, error state and arbitrary attributes.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolExecutionResultMessage implements ChatMessage {

    private final String id;
    private final String toolName;
    private final String text;
    private final Boolean isError;
    private final Map<String, Object> attributes;

    public ToolExecutionResultMessage(String id, String toolName, String text) {
        this(builder().id(id).toolName(toolName).text(text));
    }

    public ToolExecutionResultMessage(Builder builder) {
        Objects.requireNonNull(builder, "builder");
        this.id = builder.id;
        this.toolName = builder.toolName;
        this.text = Objects.requireNonNull(builder.text, "text");
        this.isError = builder.isError;
        this.attributes = immutableCopy(builder.attributes);
    }

    public String id() {
        return id;
    }

    public String toolName() {
        return toolName;
    }

    @Override
    public String text() {
        return text;
    }

    /**
     * @return true when execution failed, false when successful, null when unknown
     */
    public Boolean isError() {
        return isError;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.TOOL_EXECUTION_RESULT;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ToolExecutionResultMessage other = (ToolExecutionResultMessage) obj;
        return Objects.equals(id, other.id)
                && Objects.equals(toolName, other.toolName)
                && Objects.equals(text, other.text)
                && Objects.equals(isError, other.isError)
                && Objects.equals(attributes, other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, toolName, text, isError, attributes);
    }

    @Override
    public String toString() {
        return "ToolExecutionResultMessage{"
                + "id='" + id + '\''
                + ", toolName='" + toolName + '\''
                + ", text='" + text + '\''
                + ", isError=" + isError
                + ", attributes=" + attributes
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .id(id)
                .toolName(toolName)
                .text(text)
                .isError(isError)
                .attributes(attributes);
    }

    public static ToolExecutionResultMessage from(String id, String toolName, String toolExecutionResult) {
        return new ToolExecutionResultMessage(id, toolName, toolExecutionResult);
    }

    public static ToolExecutionResultMessage toolExecutionResultMessage(
            String id, String toolName, String toolExecutionResult) {
        return from(id, toolName, toolExecutionResult);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
    }

    public static final class Builder {

        private String id;
        private String toolName;
        private String text;
        private Boolean isError;
        private Map<String, Object> attributes;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder isError(Boolean isError) {
            this.isError = isError;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public ToolExecutionResultMessage build() {
            return new ToolExecutionResultMessage(this);
        }
    }
}
