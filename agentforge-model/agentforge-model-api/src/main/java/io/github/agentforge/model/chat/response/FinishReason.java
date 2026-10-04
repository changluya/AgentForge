package io.github.agentforge.model.chat.response;

public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_EXECUTION,
    CONTENT_FILTER,
    OTHER
}
