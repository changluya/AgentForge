package com.changlu.agentforge.llm.chat.message;

public final class SystemMessage extends AbstractTextMessage {

    public SystemMessage(String text) {
        super(text);
    }

    public static SystemMessage from(String text) {
        return new SystemMessage(text);
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.SYSTEM;
    }
}
