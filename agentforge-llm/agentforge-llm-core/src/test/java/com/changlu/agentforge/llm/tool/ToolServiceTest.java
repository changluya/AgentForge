package com.changlu.agentforge.llm.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.changlu.agentforge.llm.tool.execution.ToolService;
import com.changlu.agentforge.llm.tool.spec.ToolSpecification;
import com.changlu.agentforge.llm.tool.spec.ToolParameters;
import com.changlu.agentforge.llm.tool.error.ToolExecutionException;

/**
 * Tests {@link ToolService} registration and the inference-and-tools loop with a fake {@link ChatModel}.
 *
 * @author changlu
 * @since 2026-09-13
 */
public class ToolServiceTest {

    public static class WeatherTools {

        @Tool(value = "Returns the weather for the given city", name = "getWeather")
        public String getWeather(String city) {
            return "Weather in " + city + ": 22C sunny";
        }
    }

    @Test
    public void shouldRegisterToolsFromObjectAndExposeSpecifications() {
        ToolService service = new ToolService();
        service.tools(new WeatherTools());

        List<ToolSpecification> specs = service.toolSpecifications();
        assertEquals(1, specs.size());
        assertEquals("getWeather", specs.get(0).name());
        assertTrue(specs.get(0).parameters().properties().containsKey("city"));
        assertTrue(service.toolExecutors().containsKey("getWeather"));
    }

    @Test
    public void shouldRunMultiRoundLoopAndExecuteTool() {
        // Round 1 returns a tool call; round 2 returns a final text answer.
        ChatModel model = new ScriptedChatModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.from(
                                "call_1", "getWeather", "{\"city\":\"Hangzhou\"}")))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("It is 22C and sunny in Hangzhou."))
                        .finishReason(FinishReason.STOP)
                        .build());

        ToolService service = new ToolService();
        service.tools(new WeatherTools());

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("What is the weather in Hangzhou?"));

        ToolService.ToolChatResult result = service.chat(model, null, messages);

        assertEquals("It is 22C and sunny in Hangzhou.", result.finalResponse().aiMessage().text());
        assertEquals(1, result.toolExecutions().size());
        assertEquals("getWeather", result.toolExecutions().get(0).request().name());
        assertFalse(result.toolExecutions().get(0).hasFailed());
        assertEquals(1, result.intermediateResponses().size());
    }

    @Test
    public void shouldSendToolResultBackToLlmViaSecondRound() {
        // capture the messages of the second model call
        CapturingModel model = new CapturingModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.from(
                                "call_1", "getWeather", "{\"city\":\"Hangzhou\"}")))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .finishReason(FinishReason.STOP)
                        .build());

        ToolService service = new ToolService();
        service.tools(new WeatherTools());

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("weather?"));

        service.chat(model, null, messages);

        // second round: user + assistant(tool call) + tool_result
        List<ChatMessage> secondRound = model.requests.get(1).messages();
        assertEquals(3, secondRound.size());
        ToolExecutionResultMessage resultMessage = (ToolExecutionResultMessage) secondRound.get(2);
        assertEquals("call_1", resultMessage.id());
        assertEquals("Weather in Hangzhou: 22C sunny", resultMessage.text());
    }

    @Test
    public void shouldForceReprocessOnToolErrorAndLetLlmRetry() {
        ChatModel model = new ScriptedChatModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.from(
                                "call_1", "getWeather", "{\"city\":\"\"}")))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build(),
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("I need a valid city."))
                        .finishReason(FinishReason.STOP)
                        .build());

        ToolService service = new ToolService();
        // Tool returns an error object -> execution error handler sends message back to LLM
        service.tool(ToolSpecification.builder().name("getWeather").build(),
                (request, memoryId) -> {
                    throw new ToolExecutionException("city cannot be empty");
                });

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("weather?"));

        ToolService.ToolChatResult result = service.chat(model, null, messages);

        assertEquals("I need a valid city.", result.finalResponse().aiMessage().text());
        assertEquals(1, result.toolExecutions().size());
        assertTrue(result.toolExecutions().get(0).hasFailed());
    }

    @Test
    public void shouldApplyImmediateReturnBehaviorShortCircuit() {
        ChatModel model = new ScriptedChatModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.from(
                                "call_1", "getWeather", "{\"city\":\"Hangzhou\"}")))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build());

        // IMMEDIATE: loop should stop after the single tool round, no second model call
        ToolService service = new ToolService();
        service.tool(ToolSpecification.builder().name("getWeather")
                        .description("Returns the weather for the given city")
                        .parameters(ToolParameters.builder()
                                .addProperty("city", "string", "city name", true)
                                .build())
                        .build(),
                (request, memoryId) -> "Weather in Hangzhou: 22C sunny",
                ReturnBehavior.IMMEDIATE);

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("weather?"));

        ToolService.ToolChatResult result = service.chat(model, null, messages);

        assertEquals(1, result.toolExecutions().size());
    }

    @Test
    public void shouldThrowWhenModelReturnsUnknownTool() {
        ChatModel model = new ScriptedChatModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.from(
                                "call_1", "unknownTool", "{}")))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build());

        ToolService service = new ToolService();
        service.tools(new WeatherTools());

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("hi"));

        assertThrows(IllegalArgumentException.class, () -> service.chat(model, null, messages));
    }

    @Test
    public void shouldSupportNonToolChatRequests() {
        ChatModel model = new ScriptedChatModel(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("plain answer"))
                        .finishReason(FinishReason.STOP)
                        .build());

        ToolService service = new ToolService();
        service.tools(new WeatherTools());

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(UserMessage.from("hello"));

        ToolService.ToolChatResult result = service.chat(model, null, messages);

        assertEquals("plain answer", result.finalResponse().aiMessage().text());
        assertTrue(result.toolExecutions().isEmpty());
    }

    /** Fake model replaying a fixed script of responses. */
    private static final class ScriptedChatModel implements ChatModel {
        private final List<ChatResponse> script;
        private int index;

        private ScriptedChatModel(ChatResponse... responses) {
            this.script = Arrays.asList(responses);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            ChatResponse response = script.get(index < script.size() ? index : script.size() - 1);
            index++;
            return response;
        }
    }

    /** Fake model that also records each request, for round-trip assertions. */
    private static final class CapturingModel implements ChatModel {
        private final List<ChatResponse> script;
        private final List<ChatRequest> requests = new ArrayList<ChatRequest>();

        private CapturingModel(ChatResponse... responses) {
            this.script = Arrays.asList(responses);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            int i = requests.size() - 1;
            return script.get(i < script.size() ? i : script.size() - 1);
        }
    }
}