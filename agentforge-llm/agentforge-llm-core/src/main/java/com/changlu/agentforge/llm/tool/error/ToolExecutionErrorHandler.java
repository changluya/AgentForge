package com.changlu.agentforge.llm.tool.error;

/**
 * Handler for {@link ToolExecutionException}s thrown while executing a tool.
 *
 * 
 *
 * <p>There are two ways to handle an error:</p>
 * <ol>
 *   <li>Return {@link ToolErrorHandlerResult#text(String)} — the message is sent back to the LLM
 *       as the tool result.</li>
 *   <li>Throw an exception — this stops the tool loop and propagates to the caller.</li>
 * </ol>
 *
 * @author changlu
 * @since 2026-09-13
 */
@FunctionalInterface
public interface ToolExecutionErrorHandler {

    ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context);
}