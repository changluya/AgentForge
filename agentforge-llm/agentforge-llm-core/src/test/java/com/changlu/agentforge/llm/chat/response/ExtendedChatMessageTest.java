package com.changlu.agentforge.llm.chat.response;

import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.CustomMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExtendedChatMessageTest {

    @Test
    public void shouldCreateToolExecutionResultMessageWithFactory() {
        ToolExecutionResultMessage message = ToolExecutionResultMessage.from(
                "call-1", "getWeather", "sunny");

        assertEquals("call-1", message.id());
        assertEquals("getWeather", message.toolName());
        assertEquals("sunny", message.text());
        assertNull(message.isError());
        assertTrue(message.attributes().isEmpty());
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, message.type());
    }

    @Test
    public void shouldBuildToolExecutionResultMessageWithMetadata() {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("traceId", "trace-1");

        ToolExecutionResultMessage message = ToolExecutionResultMessage.builder()
                .id("call-2")
                .toolName("search")
                .text("failed")
                .isError(Boolean.TRUE)
                .attributes(attributes)
                .build();

        assertEquals(Boolean.TRUE, message.isError());
        assertEquals("trace-1", message.attributes().get("traceId"));
        assertThrows(UnsupportedOperationException.class,
                () -> message.attributes().put("newKey", "newValue"));

        attributes.put("traceId", "changed-after-build");
        assertEquals("trace-1", message.attributes().get("traceId"));
    }

    @Test
    public void shouldCreateModifiedCopyWithToBuilder() {
        ToolExecutionResultMessage original = ToolExecutionResultMessage.builder()
                .id("call-3")
                .toolName("calculator")
                .text("4")
                .isError(Boolean.FALSE)
                .build();

        ToolExecutionResultMessage modified = original.toBuilder()
                .text("5")
                .build();

        assertEquals("4", original.text());
        assertEquals("5", modified.text());
        assertEquals(original.id(), modified.id());
        assertEquals(original.toolName(), modified.toolName());
        assertFalse(modified.isError());
    }

    @Test
    public void shouldRequireToolResultText() {
        assertThrows(NullPointerException.class, () -> ToolExecutionResultMessage.builder()
                .id("call-4")
                .toolName("search")
                .build());
    }

    @Test
    public void shouldCreateImmutableCustomMessage() {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("provider", "demo");
        attributes.put("payload", "value");

        CustomMessage message = CustomMessage.from(attributes);

        assertEquals(ChatMessageType.CUSTOM, message.type());
        assertEquals("demo", message.attributes().get("provider"));
        assertThrows(UnsupportedOperationException.class,
                () -> message.attributes().put("newKey", "newValue"));
        assertThrows(UnsupportedOperationException.class, message::text);

        attributes.put("provider", "changed-after-build");
        assertEquals("demo", message.attributes().get("provider"));
    }

    @Test
    public void shouldSupportCustomMessageValueEqualityAndEmptyAttributes() {
        Map<String, Object> left = new LinkedHashMap<String, Object>();
        left.put("a", 1);
        Map<String, Object> right = new LinkedHashMap<String, Object>();
        right.put("a", 1);

        assertEquals(CustomMessage.from(left), CustomMessage.customMessage(right));
        assertTrue(new CustomMessage(null).attributes().isEmpty());
    }

    @Test
    public void shouldExposeMessageClassFromType() {
        assertEquals(ToolExecutionResultMessage.class,
                ChatMessageType.TOOL_EXECUTION_RESULT.messageClass());
        assertEquals(CustomMessage.class, ChatMessageType.CUSTOM.messageClass());
    }
}
