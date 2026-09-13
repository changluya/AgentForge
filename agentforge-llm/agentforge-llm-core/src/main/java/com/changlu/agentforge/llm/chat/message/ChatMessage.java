package com.changlu.agentforge.llm.chat.message;

/**
 * A provider-neutral textual message exchanged with a chat model.
 *
 * <p>AgentForge starts with text-only messages on purpose. Multimodal content,
 * tool messages and structured content can be added without changing ChatModel.</p>
 */
public interface ChatMessage {

    ChatMessageType type();

    String text();
}
