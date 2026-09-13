package com.changlu.agentforge.llm.agent.tool;

import java.util.Objects;

/**
 * A structured value returned by a {@link ToolArgumentsErrorHandler} or
 * {@link ToolExecutionErrorHandler} instead of throwing, letting the error message be sent back
 * to the LLM so it can correct itself and retry.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.service.tool.ToolErrorHandlerResult}.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolErrorHandlerResult {

    private final String text;

    private ToolErrorHandlerResult(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

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
        return Objects.equals(text, ((ToolErrorHandlerResult) obj).text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "ToolErrorHandlerResult{text='" + text + '\'' + '}';
    }

    /**
     * Creates a result carrying a text message that will be sent back to the LLM as the tool result.
     *
     * @param text error message for the LLM
     * @return the error-handler result
     */
    public static ToolErrorHandlerResult text(String text) {
        return new ToolErrorHandlerResult(text);
    }
}