package com.changlu.agentforge.llm.chat.response;

import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.CustomMessage;
import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class MessageAndResponseTest {

    @Test
    public void shouldExposeMessageTypesAndValueEquality() {
        assertEquals(ChatMessageType.SYSTEM, SystemMessage.from("system").type());
        assertEquals(ChatMessageType.USER, UserMessage.from("user").type());
        assertEquals(ChatMessageType.AI, AiMessage.from("assistant").type());
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT,
                ToolExecutionResultMessage.from("call-1", "weather", "sunny").type());
        assertEquals(ChatMessageType.CUSTOM,
                CustomMessage.from(Collections.<String, Object>singletonMap("kind", "provider-specific")).type());
        assertEquals(UserMessage.from("same"), UserMessage.from("same"));
    }

    @Test
    public void shouldBuildNormalizedResponseAndImmutableMetadata() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("done"))
                .tokenUsage(TokenUsage.of(7, 3))
                .finishReason(FinishReason.STOP)
                .metadata("requestId", "req-1")
                .build();

        assertEquals("done", response.aiMessage().text());
        assertEquals(7L, response.tokenUsage().inputTokens());
        assertEquals(3L, response.tokenUsage().outputTokens());
        assertEquals(10L, response.tokenUsage().totalTokens());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals("req-1", response.metadata().get("requestId"));
        assertThrows(UnsupportedOperationException.class,
                () -> response.metadata().put("newKey", "newValue"));
    }

    @Test
    public void shouldRequireAiMessageInResponse() {
        assertThrows(NullPointerException.class, () -> ChatResponse.builder().build());
    }
}
