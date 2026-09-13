package com.changlu.agentforge.llm.chat.message;

public final class UserMessage extends AbstractTextMessage {

    public UserMessage(String text) {
        super(text);
    }

    public static UserMessage from(String text) {
        return new UserMessage(text);
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.USER;
    }
}
