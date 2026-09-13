package com.changlu.agentforge.llm.chat;

import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/**
 * Tests the provider-neutral streaming chat API.
 *
 * @author changlu
 * @date 2026/09/13
 */
public class StreamingChatModelTest {

    @Test
    public void shouldCreateSingleUserMessageForStringConvenienceApi() {
        final AtomicReference<ChatRequest> captured = new AtomicReference<ChatRequest>();
        StreamingChatModel model = capturingModel(captured);
        RecordingHandler handler = new RecordingHandler();

        model.chat("ping", handler);

        assertNotNull(captured.get());
        assertEquals(1, captured.get().messages().size());
        assertEquals(ChatMessageType.USER, captured.get().messages().get(0).type());
        assertEquals("ping", captured.get().messages().get(0).text());
        assertEquals("pong", handler.completeResponse.aiMessage().text());
    }

    @Test
    public void shouldForwardListAndVarargsMessages() {
        final AtomicReference<ChatRequest> captured = new AtomicReference<ChatRequest>();
        StreamingChatModel model = capturingModel(captured);
        RecordingHandler handler = new RecordingHandler();

        ChatMessage system = SystemMessage.from("You are helpful");
        ChatMessage user = UserMessage.from("Hello");
        model.chat(Arrays.asList(system, user), handler);
        assertEquals(Arrays.asList(system, user), captured.get().messages());

        model.chat(handler, user);
        assertEquals(Arrays.asList(user), captured.get().messages());
    }

    @Test
    public void shouldRejectNullConvenienceArguments() {
        final StreamingChatModel model = capturingModel(new AtomicReference<ChatRequest>());
        final RecordingHandler handler = new RecordingHandler();

        assertThrows(NullPointerException.class, () -> model.chat((String) null, handler));
        assertThrows(NullPointerException.class,
                () -> model.chat((java.util.List<ChatMessage>) null, handler));
        assertThrows(NullPointerException.class,
                () -> model.chat(handler, (ChatMessage[]) null));
        assertThrows(NullPointerException.class,
                () -> model.chat("hello", null));
    }

    private static StreamingChatModel capturingModel(final AtomicReference<ChatRequest> captured) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                captured.set(chatRequest);
                handler.onPartialResponse("po");
                handler.onPartialResponse("ng");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("pong"))
                        .build());
            }
        };
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private ChatResponse completeResponse;

        @Override
        public void onPartialResponse(String partialResponse) {
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            this.completeResponse = completeResponse;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }
    }
}
