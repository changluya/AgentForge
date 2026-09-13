package com.changlu.agentforge.llm.chat.message;

public final class AiMessage extends AbstractTextMessage {

    public AiMessage(String text) {
        super(text);
    }

    public static AiMessage from(String text) {
        return new AiMessage(text);
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.AI;
    }
}
