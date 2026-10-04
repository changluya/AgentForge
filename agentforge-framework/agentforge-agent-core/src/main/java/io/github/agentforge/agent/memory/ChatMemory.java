package io.github.agentforge.agent.memory;

import io.github.agentforge.model.chat.message.ChatMessage;

import java.util.List;

/**
 * @description 会话记忆，Agent每一轮think的输入消息都从这里读取，模型回复与工具结果都写回这里
 * @author changlu
 * @date 2026/9/16
 */
public interface ChatMemory {

    /** 会话ID */
    Object id();

    /** 追加一条消息 */
    void add(ChatMessage message);

    /** 当前记忆中的全部消息，用于发起下一次模型调用 */
    List<ChatMessage> messages();

    /** 清空会话上下文 */
    void clear();
}
