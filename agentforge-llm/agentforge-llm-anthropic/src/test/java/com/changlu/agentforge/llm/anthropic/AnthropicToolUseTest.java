package com.changlu.agentforge.llm.anthropic;

import com.changlu.agentforge.llm.agent.tool.ToolParameters;
import com.changlu.agentforge.llm.agent.tool.ToolSpecification;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.SystemMessage;
import com.changlu.agentforge.llm.chat.message.TextContent;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.request.ToolChoice;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import com.changlu.agentforge.llm.http.HttpRequest;
import com.changlu.agentforge.llm.http.HttpResponse;
import com.changlu.agentforge.llm.http.HttpTransport;
import com.changlu.agentforge.llm.internal.json.Json;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies Anthropic Messages API {@code tool_use} / {@code tool_result} support in the
 * blocking {@link AnthropicChatModel}.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class AnthropicToolUseTest {

    @Test
    public void shouldParseToolUseBlockIntoAiMessage() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"id\":\"msg_1\",\"type\":\"message\",\"model\":\"claude-test\"," +
                        "\"content\":[" +
                        "{\"type\":\"text\",\"text\":\"I will check.\"}," +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"," +
                        "\"input\":{\"city\":\"hangzhou\",\"unit\":\"celsius\"}}" +
                        "],\"stop_reason\":\"tool_use\"}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("weather in Hangzhou?"))
                .build());

        AiMessage aiMessage = response.aiMessage();
        assertEquals("I will check.", aiMessage.text());
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertEquals(1, aiMessage.toolExecutionRequests().size());

        ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(0);
        assertEquals("toolu_1", request.id());
        assertEquals("get_weather", request.name());
        // input object is normalized into a JSON string
        assertEquals("{\"city\":\"hangzhou\",\"unit\":\"celsius\"}", request.arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
    }

    @Test
    public void shouldParseMultipleToolUses() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[" +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_a\",\"name\":\"weather\",\"input\":{\"city\":\"hz\"}}," +
                        "{\"type\":\"tool_use\",\"id\":\"toolu_b\",\"name\":\"weather\",\"input\":{\"city\":\"bj\"}}" +
                        "],\"stop_reason\":\"tool_use\"}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("two cities"))
                .build());

        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("toolu_a", requests.get(0).id());
        assertEquals("{\"city\":\"hz\"}", requests.get(0).arguments());
        assertEquals("toolu_b", requests.get(1).id());
        assertEquals("{\"city\":\"bj\"}", requests.get(1).arguments());
        assertNull(response.aiMessage().text());
    }

    @Test
    public void shouldSerializeAssistantToolUseAndToolResult() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"22 degrees\"}],\"stop_reason\":\"end_turn\"}"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("toolu_1")
                .name("get_weather")
                .arguments("{\"city\":\"hangzhou\"}")
                .build();

        model(transport).chat(ChatRequest.builder()
                .message(SystemMessage.from("You are helpful"))
                .message(UserMessage.from("weather?"))
                .message(AiMessage.from(request))
                .message(ToolExecutionResultMessage.from("toolu_1", "get_weather", "{\"temperature\":22}"))
                .build());

        List<Object> messages = Json.array(Json.parseObject(transport.lastRequest.body()).get("messages"));
        // user text, assistant tool_use, user tool_result
        assertEquals(3, messages.size());

        Map<String, Object> assistant = Json.object(messages.get(1));
        assertEquals("assistant", assistant.get("role"));
        List<Object> assistantContent = Json.array(assistant.get("content"));
        assertEquals(1, assistantContent.size());
        Map<String, Object> toolUse = Json.object(assistantContent.get(0));
        assertEquals("tool_use", toolUse.get("type"));
        assertEquals("toolu_1", toolUse.get("id"));
        assertEquals("get_weather", toolUse.get("name"));
        Map<String, Object> input = Json.object(toolUse.get("input"));
        assertEquals("hangzhou", input.get("city"));

        Map<String, Object> toolResultHolder = Json.object(messages.get(2));
        assertEquals("user", toolResultHolder.get("role"));
        List<Object> resultContent = Json.array(toolResultHolder.get("content"));
        Map<String, Object> toolResult = Json.object(resultContent.get(0));
        assertEquals("tool_result", toolResult.get("type"));
        assertEquals("toolu_1", toolResult.get("tool_use_id"));
        assertEquals("{\"temperature\":22}", toolResult.get("content"));
    }

    @Test
    public void shouldSerializeToolsAndToolChoice() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}"));

        ToolParameters parameters = ToolParameters.builder()
                .addProperty("city", "string", "city name", true)
                .build();

        model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .parameters(DefaultChatRequestParameters.builder()
                        .tool(ToolSpecification.builder()
                                .name("get_weather")
                                .description("query weather")
                                .parameters(parameters)
                                .build())
                        .toolChoice(ToolChoice.SPECIFIC)
                        .toolChoiceName("get_weather")
                        .build())
                .build());

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        List<Object> tools = Json.array(payload.get("tools"));
        assertEquals(1, tools.size());
        Map<String, Object> tool = Json.object(tools.get(0));
        assertEquals("get_weather", tool.get("name"));
        assertEquals("query weather", tool.get("description"));
        Map<String, Object> inputSchema = Json.object(tool.get("input_schema"));
        assertEquals("object", inputSchema.get("type"));
        assertTrue(Json.object(inputSchema.get("properties")).containsKey("city"));
        assertEquals(Arrays.asList("city"), Json.array(inputSchema.get("required")));

        Map<String, Object> toolChoice = Json.object(payload.get("tool_choice"));
        assertEquals("tool", toolChoice.get("type"));
        assertEquals("get_weather", toolChoice.get("name"));
    }

    @Test
    public void shouldSerializeMultiContentUserMessage() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\"}"));

        model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from(TextContent.from("line one"), TextContent.from("line two")))
                .build());

        List<Object> messages = Json.array(Json.parseObject(transport.lastRequest.body()).get("messages"));
        Map<String, Object> user = Json.object(messages.get(0));
        assertEquals("user", user.get("role"));
        List<Object> content = Json.array(user.get("content"));
        assertEquals(2, content.size());
        assertEquals("text", Json.object(content.get(0)).get("type"));
        assertEquals("line one", Json.object(content.get(0)).get("text"));
        assertEquals("line two", Json.object(content.get(1)).get("text"));
    }

    @Test
    public void shouldKeepPlainTextBehaviourWhenNoToolUse() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"stop_reason\":\"end_turn\"}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .build());

        assertFalse(response.aiMessage().hasToolExecutionRequests());
        assertEquals("hello", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    private static AnthropicChatModel model(HttpTransport transport) {
        return AnthropicChatModel.builder()
                .apiKey("secret")
                .modelName("claude-test")
                .httpTransport(transport)
                .build();
    }

    private static final class CapturingTransport implements HttpTransport {
        private final HttpResponse response;
        private HttpRequest lastRequest;

        private CapturingTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.lastRequest = request;
            return response;
        }
    }
}