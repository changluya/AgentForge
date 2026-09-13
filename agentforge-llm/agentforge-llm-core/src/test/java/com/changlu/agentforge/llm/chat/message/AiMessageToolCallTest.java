package com.changlu.agentforge.llm.chat.message;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AiMessage} {@link ToolExecutionRequest} support and the
 * LangChain4j-style {@code UserMessage} {@link Content} modeling.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class AiMessageToolCallTest {

    private static ToolExecutionRequest weather(String id) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("getWeather")
                .arguments("{\"city\":\"hangzhou\"}")
                .build();
    }

    @Test
    public void shouldCreateToolOnlyAiMessage() {
        AiMessage message = AiMessage.from(
                weather("call_1"), weather("call_2"));

        assertTrue(message.hasToolExecutionRequests());
        assertEquals(2, message.toolExecutionRequests().size());
        assertNull(message.text());
        assertEquals("getWeather", message.toolExecutionRequests().get(0).name());
        assertEquals("{\"city\":\"hangzhou\"}", message.toolExecutionRequests().get(0).arguments());
    }

    @Test
    public void shouldCreateTextPlusToolAiMessage() {
        ToolExecutionRequest request = weather("call_1");
        AiMessage message = AiMessage.from("Let me check the weather", Collections.singletonList(request));

        assertTrue(message.hasToolExecutionRequests());
        assertEquals("Let me check the weather", message.text());
        assertEquals(Arrays.asList(request), message.toolExecutionRequests());
    }

    @Test
    public void shouldReportNoToolRequestsForPlainText() {
        AiMessage message = AiMessage.from("Hello");
        assertFalse(message.hasToolExecutionRequests());
        assertTrue(message.toolExecutionRequests().isEmpty());
        assertEquals("Hello", message.text());
    }

    @Test
    public void shouldSupportThinkingAndAttributes() {
        AiMessage message = AiMessage.builder()
                .text("answer")
                .thinking("reasoning trace")
                .toolExecutionRequests(Collections.singletonList(weather("call_1")))
                .attributes(Collections.<String, Object>singletonMap("provider", "openai"))
                .build();

        assertEquals("answer", message.text());
        assertEquals("reasoning trace", message.thinking());
        assertEquals("openai", message.attributes().get("provider"));
        assertThrows(UnsupportedOperationException.class, () -> message.attributes().put("x", "y"));
    }

    @Test
    public void shouldRespectValueEqualityAndImmutability() {
        AiMessage a = AiMessage.from(weather("call_1"));
        AiMessage b = AiMessage.from(weather("call_1"));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertThrows(UnsupportedOperationException.class,
                () -> AiMessage.from(weather("x")).toolExecutionRequests().clear());
    }

    @Test
    public void shouldBuildUserMessageFromText() {
        UserMessage message = UserMessage.from("hello");
        assertEquals("user", message.type().name().toLowerCase());
        assertTrue(message.hasSingleText());
        assertEquals("hello", message.text());
        assertEquals("hello", message.singleText());
        assertEquals(1, message.contents().size());
        assertEquals(TextContent.from("hello"), message.contents().get(0));
    }

    @Test
    public void shouldBuildUserMessageWithNameAndContents() {
        UserMessage message = UserMessage.from("alice", TextContent.from("hi"), TextContent.from(" again"));

        assertEquals("alice", message.name());
        assertFalse(message.hasSingleText());
        assertEquals(2, message.contents().size());
        assertThrows(IllegalArgumentException.class, message::singleText);
    }

    @Test
    public void shouldBuildUserMessageFromContentList() {
        List<Content> contents = Arrays.<Content>asList(TextContent.from("a"), TextContent.from("b"));
        UserMessage message = UserMessage.from(contents);
        assertEquals(2, message.contents().size());
        assertEquals(TextContent.from("a"), message.contents().get(0));
    }

    @Test
    public void shouldBuildToolExecutionRequestAndRoundTripFields() {
        ToolExecutionRequest request = ToolExecutionRequest.from("id-1", "getOrder", "{\"id\":9}");
        assertEquals("id-1", request.id());
        assertEquals("getOrder", request.name());
        assertEquals("{\"id\":9}", request.arguments());
        assertEquals(request, ToolExecutionRequest.builder()
                .id("id-1").name("getOrder").arguments("{\"id\":9}").build());
        assertNotNull(request.toString());
    }
}