package com.changlu.agentforge.llm.chat.message;

/**
 * Supported chat message kinds.
 *
 * @author changlu
 * @since 2026-09-13
 */
public enum ChatMessageType {

    SYSTEM(SystemMessage.class),
    USER(UserMessage.class),
    AI(AiMessage.class),
    TOOL_EXECUTION_RESULT(ToolExecutionResultMessage.class),
    CUSTOM(CustomMessage.class);

    private final Class<? extends ChatMessage> messageClass;

    ChatMessageType(Class<? extends ChatMessage> messageClass) {
        this.messageClass = messageClass;
    }

    public Class<? extends ChatMessage> messageClass() {
        return messageClass;
    }
}
