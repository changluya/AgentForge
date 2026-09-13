package com.changlu.agentforge.llm.chat;

import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class ChatModelTest {

    @Test
    public void shouldCreateSingleUserMessageForStringConvenienceApi() {
        final AtomicReference<ChatRequest> captured = new AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("pong")).build();
            }
        };

        String answer = model.chat("ping");

        assertEquals("pong", answer);
        assertNotNull(captured.get());
        assertEquals(1, captured.get().messages().size());
        assertEquals(ChatMessageType.USER, captured.get().messages().get(0).type());
        assertEquals("ping", captured.get().messages().get(0).text());
    }

    @Test
    public void shouldForwardVarargsAndListMessages() {
        final AtomicReference<ChatRequest> captured = new AtomicReference<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.set(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };

        ChatMessage system = SystemMessage.from("You are helpful");
        ChatMessage user = UserMessage.from("Hello");
        model.chat(system, user);
        assertEquals(Arrays.asList(system, user), captured.get().messages());

        model.chat(Arrays.asList(user));
        assertEquals(Arrays.asList(user), captured.get().messages());
    }

    @Test
    public void shouldRejectNullConvenienceArguments() {
        final ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("unused")).build();
            }
        };

        assertThrows(NullPointerException.class, () -> model.chat((String) null));
        assertThrows(NullPointerException.class, () -> model.chat((ChatMessage[]) null));
        assertThrows(NullPointerException.class, () -> model.chat((java.util.List<ChatMessage>) null));
    }
}
