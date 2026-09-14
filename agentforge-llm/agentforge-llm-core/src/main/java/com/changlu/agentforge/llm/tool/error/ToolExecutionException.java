package com.changlu.agentforge.llm.tool.error;

/**
 * Indicates that something went wrong while executing the tool itself.
 *
 * 
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