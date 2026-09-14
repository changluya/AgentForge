package com.changlu.agentforge.llm.openai;

import com.changlu.agentforge.llm.tool.spec.ToolParameters;
import com.changlu.agentforge.llm.tool.spec.ToolSpecification;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies OpenAI Chat Completions function-calling support: request-side tool
 * declarations, response-side {@code tool_calls} parsing and the assistant/tool
 * message round-trip.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class OpenAiFunctionCallTest {

    @Test
    public void shouldParseSingleToolCallIntoAiMessage() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"id\":\"chatcmpl-9\",\"model\":\"gpt-test\",\"choices\":[{" +
                        "\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" +
                        "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{" +
                        "\"name\":\"getWeather\",\"arguments\":\"{\\\"city\\\":\\\"hangzhou\\\"}\"}}]}," +
                        "\"finish_reason\":\"tool_calls\"}]}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("What is the weather in Hangzhou?"))
                .build());

        AiMessage aiMessage = response.aiMessage();
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertNull("content is null for a pure tool call", aiMessage.text());
        assertEquals(1, aiMessage.toolExecutionRequests().size());

        ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(0);
        assertEquals("call_1", request.id());
        assertEquals("getWeather", request.name());
        assertEquals("{\"city\":\"hangzhou\"}", request.arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
    }

    @Test
    public void shouldParseMultipleToolCallsAndMixedText() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{" +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"two cities\"," +
                        "\"tool_calls\":[" +
                        "{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"hangzhou\\\"}\"}}," +
                        "{\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"{\\\"city\\\":\\\"beijing\\\"}\"}}" +
                        "]},\"finish_reason\":\"tool_calls\"}]}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("weather for two cities"))
                .build());

        AiMessage aiMessage = response.aiMessage();
        assertEquals("two cities", aiMessage.text());
        assertEquals(2, aiMessage.toolExecutionRequests().size());
        assertEquals("call_a", aiMessage.toolExecutionRequests().get(0).id());
        assertEquals("{\"city\":\"hangzhou\"}", aiMessage.toolExecutionRequests().get(0).arguments());
        assertEquals("call_b", aiMessage.toolExecutionRequests().get(1).id());
        assertEquals("{\"city\":\"beijing\"}", aiMessage.toolExecutionRequests().get(1).arguments());
    }

    @Test
    public void shouldSerializeToolsAndToolChoiceOnRequest() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));

        ToolParameters parameters = ToolParameters.builder()
                .addProperty("city", "string", "city name", true)
                .addProperty("unit", "string", "celsius or fahrenheit", false)
                .build();
        ToolSpecification weather = ToolSpecification.builder()
                .name("getWeather")
                .description("query weather of a city")
                .parameters(parameters)
                .build();

        model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .parameters(DefaultChatRequestParameters.builder()
                        .tools(Collections.singletonList(weather))
                        .toolChoice(ToolChoice.REQUIRED)
                        .build())
                .build());

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        List<Object> tools = Json.array(payload.get("tools"));
        assertEquals(1, tools.size());

        Map<String, Object> tool = Json.object(tools.get(0));
        assertEquals("function", tool.get("type"));
        Map<String, Object> function = Json.object(tool.get("function"));
        assertEquals("getWeather", function.get("name"));
        assertEquals("query weather of a city", function.get("description"));

        Map<String, Object> schema = Json.object(function.get("parameters"));
        assertEquals("object", schema.get("type"));
        Map<String, Object> properties = Json.object(schema.get("properties"));
        assertTrue(properties.containsKey("city"));
        assertTrue(properties.containsKey("unit"));
        assertEquals(Arrays.asList("city"), Json.array(schema.get("required")));

        assertEquals("required", payload.get("tool_choice"));
    }

    @Test
    public void shouldSerializeSpecificToolChoice() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));

        model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .parameters(DefaultChatRequestParameters.builder()
                        .tool(ToolSpecification.builder().name("getWeather").build())
                        .toolChoice(ToolChoice.SPECIFIC)
                        .toolChoiceName("getWeather")
                        .build())
                .build());

        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        Map<String, Object> toolChoice = Json.object(payload.get("tool_choice"));
        assertEquals("function", toolChoice.get("type"));
        assertEquals("getWeather", Json.object(toolChoice.get("function")).get("name"));
    }

    @Test
    public void shouldRoundTripAssistantToolCallsAndToolResults() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"sunny\"},\"finish_reason\":\"stop\"}]}"));

        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("getWeather")
                .arguments("{\"city\":\"hangzhou\"}")
                .build();

        model(transport).chat(ChatRequest.builder()
                .message(SystemMessage.from("You are helpful"))
                .message(UserMessage.from("weather?"))
                .message(AiMessage.from(request))
                .message(ToolExecutionResultMessage.from("call_1", "getWeather", "{\"temperature\":22}"))
                .build());

        List<Object> messages = Json.array(Json.parseObject(transport.lastRequest.body()).get("messages"));
        assertEquals(4, messages.size());

        Map<String, Object> assistant = Json.object(messages.get(2));
        assertEquals("assistant", assistant.get("role"));
        assertNull(assistant.get("content"));
        List<Object> toolCalls = Json.array(assistant.get("tool_calls"));
        assertEquals(1, toolCalls.size());
        Map<String, Object> toolCall = Json.object(toolCalls.get(0));
        assertEquals("call_1", toolCall.get("id"));
        assertEquals("function", toolCall.get("type"));
        Map<String, Object> function = Json.object(toolCall.get("function"));
        assertEquals("getWeather", function.get("name"));
        assertEquals("{\"city\":\"hangzhou\"}", function.get("arguments"));

        Map<String, Object> toolResult = Json.object(messages.get(3));
        assertEquals("tool", toolResult.get("role"));
        assertEquals("call_1", toolResult.get("tool_call_id"));
        assertEquals("{\"temperature\":22}", toolResult.get("content"));
    }

    @Test
    public void shouldKeepPlainTextBehaviourWhenNoToolCalls() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"plain\"},\"finish_reason\":\"stop\"}]}"));

        ChatResponse response = model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("hi"))
                .build());

        assertFalse(response.aiMessage().hasToolExecutionRequests());
        assertEquals("plain", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    public void shouldSerializeMultiContentUserMessage() {
        CapturingTransport transport = new CapturingTransport(new HttpResponse(200,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"));

        model(transport).chat(ChatRequest.builder()
                .message(UserMessage.from("alice", TextContent.from("describe"), TextContent.from(" this image")))
                .build());

        List<Object> messages = Json.array(Json.parseObject(transport.lastRequest.body()).get("messages"));
        Map<String, Object> user = Json.object(messages.get(0));
        assertEquals("user", user.get("role"));
        assertEquals("alice", user.get("name"));
        List<Object> content = Json.array(user.get("content"));
        assertEquals(2, content.size());
        assertEquals("text", Json.object(content.get(0)).get("type"));
        assertEquals("describe", Json.object(content.get(0)).get("text"));
        assertEquals(" this image", Json.object(content.get(1)).get("text"));
    }

    private static OpenAiChatModel model(HttpTransport transport) {
        return OpenAiChatModel.builder()
                .apiKey("test-key")
                .modelName("gpt-test")
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