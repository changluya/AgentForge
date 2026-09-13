package com.changlu.agentforge.llm.chat.response;

public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_EXECUTION,
    CONTENT_FILTER,
    OTHER
}
