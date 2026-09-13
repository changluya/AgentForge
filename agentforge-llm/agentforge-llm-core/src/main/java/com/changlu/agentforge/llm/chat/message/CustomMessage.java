package com.changlu.agentforge.llm.chat.message;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a provider-specific custom message.
 *
 * <p>A custom message deliberately does not define a text payload. Providers that
 * support custom messages can interpret {@link #attributes()} according to their
 * own protocol. Unsupported providers should reject this message explicitly.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class CustomMessage implements ChatMessage {

    private final Map<String, Object> attributes;

    public CustomMessage(Map<String, Object> attributes) {
        this.attributes = immutableCopy(attributes);
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    @Override
    public ChatMessageType type() {
        return ChatMessageType.CUSTOM;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        CustomMessage other = (CustomMessage) obj;
        return Objects.equals(attributes, other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributes);
    }

    @Override
    public String toString() {
        return "CustomMessage{" + "attributes=" + attributes + '}';
    }

    public static CustomMessage from(Map<String, Object> attributes) {
        return new CustomMessage(attributes);
    }

    public static CustomMessage customMessage(Map<String, Object> attributes) {
        return from(attributes);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
    }
}
