package io.github.agentforge.agent.memory;

import io.github.agentforge.model.chat.message.AiMessage;
import io.github.agentforge.model.chat.message.ChatMessage;
import io.github.agentforge.model.chat.message.ChatMessageType;
import io.github.agentforge.model.chat.message.SystemMessage;
import io.github.agentforge.model.chat.message.UserMessage;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description 窗口会话记忆与提供者用例
 * @author changlu
 * @date 2026/9/16
 */
public class WindowChatMemoryTest {

    @Test
    public void shouldEvictOldestNonSystemMessageOnly() {
        WindowChatMemory memory = new WindowChatMemory("s1", 3);
        memory.add(SystemMessage.from("system prompt"));
        memory.add(UserMessage.from("q1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("q2"));

        List<ChatMessage> messages = memory.messages();
        assertEquals(3, messages.size());
        assertEquals(ChatMessageType.SYSTEM, messages.get(0).type());
        assertEquals("a1", messages.get(1).text());
        assertEquals("q2", messages.get(2).text());
    }

    @Test
    public void shouldKeepEveryMessageWhenWindowIsUnlimited() {
        ChatMemory memory = new WindowChatMemory("s1", 0);
        for (int i = 0; i < 20; i++) {
            memory.add(UserMessage.from("m" + i));
        }
        assertEquals(20, memory.messages().size());
    }

    @Test
    public void shouldIgnoreNullAndSupportClear() {
        ChatMemory memory = new WindowChatMemory("s1", 10);
        memory.add(UserMessage.from("q1"));
        memory.add(null);
        assertEquals(1, memory.messages().size());

        memory.clear();
        assertTrue(memory.messages().isEmpty());
        assertEquals("s1", memory.id());
    }

    @Test
    public void shouldReturnAnImmutableSnapshot() {
        ChatMemory memory = new WindowChatMemory("s1", 10);
        memory.add(UserMessage.from("q1"));
        List<ChatMessage> snapshot = memory.messages();

        try {
            snapshot.add(UserMessage.from("q2"));
            fail("Expected an immutable message snapshot");
        } catch (UnsupportedOperationException e) {
            assertEquals(1, memory.messages().size());
        }
    }

    @Test
    public void shouldReuseMemoryPerSessionId() {
        ChatMemoryProvider provider = ChatMemoryProvider.windowChatMemoryProvider(50);

        ChatMemory first = provider.get("s1");
        assertSame(first, provider.get("s1"));
        assertTrue(provider.get("s2") != first);

        first.add(UserMessage.from("q1"));
        assertEquals(1, provider.get("s1").messages().size());
        assertTrue(provider.get("s2").messages().isEmpty());
    }
}
