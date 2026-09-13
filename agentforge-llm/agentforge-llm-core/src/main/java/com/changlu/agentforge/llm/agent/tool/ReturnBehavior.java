package com.changlu.agentforge.llm.agent.tool;

/**
 * Per-tool setting controlling what happens with a tool's result after execution.
 *
 * <p>Mirrors LangChain4j's {@code ReturnBehavior}.</p>
 *
 * <ul>
 *   <li>{@link #TO_LLM} (default): the tool result is appended to the conversation and sent back
 *       to the LLM for further processing — the tool loop runs another turn.</li>
 *   <li>{@link #IMMEDIATE}: the tool result is returned to the caller directly and the loop stops.
 *       Only meaningful when the caller is prepared to consume raw tool results.</li>
 *   <li>{@link #IMMEDIATE_IF_LAST}: the loop returns immediately if and only if this is the last
 *       tool call in the response (and no tool errored).</li>
 * </ul>
 *
 * <p>Immediate-return rule applied after each LLM response (any tool error forces reprocess):</p>
 * <pre>
 *   []                                                    -> reprocess (no tool calls)
 *   [TO_LLM, ...] with any TO_LLM                         -> reprocess
 *   [IMMEDIATE] | [IMMEDIATE, IMMEDIATE]                  -> return immediately
 *   [IMMEDIATE_IF_LAST]                                   -> return immediately
 *   [.., IMMEDIATE_IF_LAST] as the last tool              -> return immediately
 *   [.., TO_LLM, IMMEDIATE_IF_LAST]                       -> return immediately (last is IMMEDIATE_IF_LAST)
 * </pre>
 *
 * @author changlu
 * @since 2026-09-13
 */
public enum ReturnBehavior {

    /**
     * The tool result is sent back to the LLM for further processing — the tool loop continues.
     * This is the default behavior.
     */
    TO_LLM,

    /**
     * Return the tool result to the caller immediately, short-circuiting the loop.
     */
    IMMEDIATE,

    /**
     * Return the tool result to the caller only when this tool is the last one invoked.
     */
    IMMEDIATE_IF_LAST
}