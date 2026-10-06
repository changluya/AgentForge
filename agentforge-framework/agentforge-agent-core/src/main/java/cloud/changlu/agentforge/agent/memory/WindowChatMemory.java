package cloud.changlu.agentforge.agent.memory;

import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @description 窗口会话记忆：超过maxMessages时淘汰最早的非系统消息，系统提示词始终保留
 * @author changlu
 * @date 2026/9/16
 */
public class WindowChatMemory implements ChatMemory {

    private final Object id;
    private final int maxMessages;
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public WindowChatMemory(Object id, int maxMessages) {
        this.id = id;
        this.maxMessages = maxMessages <= 0 ? Integer.MAX_VALUE : maxMessages;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message == null) {
            return;
        }
        messages.add(message);
        evict();
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
    }

    private void evict() {
        while (messages.size() > maxMessages) {
            int removable = firstNonSystemIndex();
            if (removable < 0) {
                return;
            }
            messages.remove(removable);
        }
    }

    private int firstNonSystemIndex() {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).type() != ChatMessageType.SYSTEM) {
                return i;
            }
        }
        return -1;
    }
}
