package com.changlu.agentforge.llm.tool.error;

/**
 * Handler for {@link ToolArgumentsException}s thrown while preparing tool arguments from the LLM's
 * JSON (for example the JSON cannot be parsed or a value is of the wrong type).
 *
 * 
 *
 * <p>There are two ways to handle an error:</p>
 * <ol>
 *   <li>Return {@link ToolErrorHandlerResult#text(String)} — the message is sent back to the LLM
 *       as the tool result, allowing it to correct the arguments and retry.</li>
 *   <li>Throw an exception — this stops the tool loop and propagates to the caller.</li>
 * </ol>
 *
 * @author changlu
 * @since 2026-09-13
 */
@FunctionalInterface
public interface ToolArgumentsErrorHandler {

    ToolErrorHandlerResult handle(Throwable error, ToolErrorContext context);
}