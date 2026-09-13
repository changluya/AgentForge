package com.changlu.agentforge.llm.chat.request;

import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class ChatRequestTest {

    @Test
    public void shouldRequireAtLeastOneMessage() {
        assertThrows(IllegalArgumentException.class, () -> ChatRequest.builder().build());
    }

    @Test
    public void shouldDefensivelyCopyMessagesAndExposeImmutableList() {
        List<ChatMessage> source = new ArrayList<ChatMessage>();
        source.add(UserMessage.from("first"));

        ChatRequest request = ChatRequest.builder().messages(source).build();
        source.add(UserMessage.from("second"));

        assertEquals(1, request.messages().size());
        assertEquals("first", request.messages().get(0).text());
        assertThrows(UnsupportedOperationException.class,
                () -> request.messages().add(UserMessage.from("third")));
    }

    @Test
    public void shouldKeepRequestParameters() {
        DefaultChatRequestParameters parameters = DefaultChatRequestParameters.builder()
                .modelName("demo-model")
                .temperature(0.3)
                .build();

        ChatRequest request = ChatRequest.builder()
                .message(UserMessage.from("hello"))
                .parameters(parameters)
                .build();

        assertSame(parameters, request.parameters());
    }

    @Test
    public void shouldRejectNullMessage() {
        assertThrows(NullPointerException.class,
                () -> ChatRequest.builder().message(null));
    }
}
