package com.changlu.agentforge.llm.agent.tool;

import java.util.Objects;

/**
 * Represents the value returned by a tool execution, wrapping both the raw object result and the
 * text payload that will be sent back to the LLM, plus an {@code isError} flag.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.service.tool.ToolExecutionResult} (simplified to a
 * text/raw result, since AgentForge core currently exposes text-oriented tool results).</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolExecutionResult {

    private final boolean isError;
    private final Object result;
    private final String text;

    private ToolExecutionResult(Builder builder) {
        this.isError = builder.isError;
        this.result = builder.result;
        this.text = builder.text;
    }

    /**
     * @return {@code true} when the tool execution failed
     */
    public boolean isError() {
        return isError;
    }

    /**
     * @return the raw object returned by the tool method, not sent to the LLM
     */
    public Object result() {
        return result;
    }

    /**
     * @return the text payload that will be sent to the LLM
     */
    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ToolExecutionResult other = (ToolExecutionResult) obj;
        return isError == other.isError
                && Objects.equals(result, other.result)
                && Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isError, result, text);
    }

    @Override
    public String toString() {
        return "ToolExecutionResult{"
                + "isError=" + isError
                + ", result=" + result
                + ", text='" + text + '\''
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Shortcut for a successful text result.
     */
    public static ToolExecutionResult success(String text) {
        return builder().text(text).build();
    }

    /**
     * Shortcut for a failed execution.
     */
    public static ToolExecutionResult failure(String text, Throwable cause) {
        return builder().isError(true).result(cause).text(text).build();
    }

    public static final class Builder {

        private boolean isError;
        private Object result;
        private String text;

        private Builder() {
        }

        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        public Builder result(Object result) {
            this.result = result;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public ToolExecutionResult build() {
            return new ToolExecutionResult(this);
        }
    }
}