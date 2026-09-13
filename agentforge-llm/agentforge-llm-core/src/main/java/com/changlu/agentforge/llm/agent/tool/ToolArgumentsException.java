package com.changlu.agentforge.llm.agent.tool;

/**
 * Indicates that something is wrong with the tool arguments produced by the LLM — for example the
 * arguments JSON cannot be parsed, or an argument is of the wrong type.
 *
 * <p>Mirrors LangChain4j's {@code dev.langchain4j.exception.ToolArgumentsException}.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public class ToolArgumentsException extends RuntimeException {

    public ToolArgumentsException(String message) {
        super(message);
    }

    public ToolArgumentsException(String message, Throwable cause) {
        super(message, cause);
    }

    public ToolArgumentsException(Throwable cause) {
        super(cause);
    }
}