package com.changlu.agentforge.llm.chat.message;

import java.util.Objects;

abstract class AbstractTextMessage implements ChatMessage {

    private final String text;

    AbstractTextMessage(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    @Override
    public final String text() {
        return text;
    }

    @Override
    public String toString() {
        return type() + ": " + text;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), text);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChatMessage)) {
            return false;
        }
        ChatMessage other = (ChatMessage) obj;
        return type() == other.type() && text.equals(other.text());
    }
}
