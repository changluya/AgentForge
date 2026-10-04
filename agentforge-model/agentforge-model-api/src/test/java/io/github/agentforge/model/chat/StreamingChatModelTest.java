package io.github.agentforge.model.chat;

import io.github.agentforge.model.chat.message.AiMessage;
import io.github.agentforge.model.chat.message.ChatMessage;
import io.github.agentforge.model.chat.message.ChatMessageType;
import io.github.agentforge.model.chat.message.SystemMessage;
import io.github.agentforge.model.chat.message.UserMessage;
import io.github.agentforge.model.chat.request.ChatRequest;
import io.github.agentforge.model.chat.response.ChatResponse;
import io.github.agentforge.model.chat.response.StreamingChatResponseHandler;

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
        assertThrows(
                NullPointerException.class,
                () -> model.chat((java.util.List<ChatMessage>) null, handler));
        assertThrows(NullPointerException.class, () -> model.chat(handler, (ChatMessage[]) null));
        assertThrows(NullPointerException.class, () -> model.chat("hello", null));
    }

    private static StreamingChatModel capturingModel(final AtomicReference<ChatRequest> captured) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                captured.set(chatRequest);
                handler.onPartialResponse("po");
                handler.onPartialResponse("ng");
                handler.onCompleteResponse(
                        ChatResponse.builder().aiMessage(AiMessage.from("pong")).build());
            }
        };
    }

    private static final class RecordingHandler implements StreamingChatResponseHandler {
        private ChatResponse completeResponse;

        @Override
        public void onPartialResponse(String partialResponse) {}

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
