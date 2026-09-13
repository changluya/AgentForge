package com.changlu.agentforge.llm.chat.request;

/**
 * Specifies how a chat model should use the tools attached to a request.
 *
 * <p>Mirrors LangChain4j's {@code ToolChoice}. {@link #SPECIFIC} additionally carries the
 * name of the tool that must be used, exposed by {@link SpecificToolChoice}.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public enum ToolChoice {

    /**
     * The model decides whether and which tools to use.
     */
    AUTO,

    /**
     * The model must not call any tool.
     */
    NONE,

    /**
     * The model must call one or more tools.
     */
    REQUIRED,

    /**
     * The model must call one specific tool, whose name is configured on the request.
     */
    SPECIFIC
}