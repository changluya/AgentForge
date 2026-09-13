package com.changlu.agentforge.llm.agent.tool;

/**
 * Indicates that something went wrong while executing the tool itself.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.exception.ToolExecutionException}.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ToolExecutionException(Throwable cause) {
        super(cause);
    }
}