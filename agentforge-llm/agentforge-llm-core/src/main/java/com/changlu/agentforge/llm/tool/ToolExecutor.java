package com.changlu.agentforge.llm.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import java.util.Objects;
import com.changlu.agentforge.llm.tool.execution.ToolExecutionResult;

/**
 * Low-level executor/handler of a {@link ToolExecutionRequest}.
 *
 * 
 *
 * @author changlu
 * @since 2026-09-13
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Executes a tool request and returns the raw text result that will be sent to the LLM.
     *
     * @param request  the tool execution request, containing tool name and arguments
     * @param memoryId the chat-memory id, or {@code null} when not applicable
     * @return the tool execution result in text form
     */
    String execute(ToolExecutionRequest request, Object memoryId);

    /**
     * Executes a tool request and returns a structured {@link ToolExecutionResult}, allowing the
     * implementer to also carry the raw object result and an {@code isError} flag.
     *
     * <p>The default implementation delegates to {@link #execute(ToolExecutionRequest, Object)} and
     * wraps the returned text into a successful {@link ToolExecutionResult}.</p>
     *
     * @param request  the tool execution request
     * @param memoryId the chat-memory id, or {@code null}
     * @return a structured execution result
     */
    default ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        Objects.requireNonNull(request, "request");
        String text = execute(request, memoryId);
        return ToolExecutionResult.builder().text(text).build();
    }
}