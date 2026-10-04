package cloud.changlu.agentforge.agent.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description 按memoryId提供会话记忆
 * @author changlu
 * @date 2026/9/16
 */
public interface ChatMemoryProvider {

    /** 获取（不存在则创建）某个会话的记忆 */
    ChatMemory get(Object memoryId);

    /**
     * 构造一个进程内的窗口记忆提供者，同一memoryId复用同一个 {@link WindowChatMemory}
     *
     * @param maxMessages 单个会话保留的最大消息数，小于等于0表示不限制
     */
    static ChatMemoryProvider windowChatMemoryProvider(final int maxMessages) {
        final Map<Object, ChatMemory> memories = new ConcurrentHashMap<Object, ChatMemory>();
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                ChatMemory memory = memories.get(memoryId);
                if (memory == null) {
                    memory = new WindowChatMemory(memoryId, maxMessages);
                    ChatMemory previous = memories.putIfAbsent(memoryId, memory);
                    if (previous != null) {
                        memory = previous;
                    }
                }
                return memory;
            }
        };
    }
}
