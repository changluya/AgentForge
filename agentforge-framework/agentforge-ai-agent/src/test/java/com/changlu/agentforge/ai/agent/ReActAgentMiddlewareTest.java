package com.changlu.agentforge.ai.agent;

import com.changlu.agentforge.ai.agent.component.middleware.IAgentMiddleware;
import com.changlu.agentforge.ai.agent.domain.AgentChatContext;
import com.changlu.agentforge.ai.agent.domain.AgentRequest;
import com.changlu.agentforge.ai.agent.domain.AgentRunState;
import com.changlu.agentforge.ai.agent.domain.AgentSettings;
import com.changlu.agentforge.ai.agent.extend.middlewares.LoggingIAgentMiddleware;
import com.changlu.agentforge.ai.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.ai.agent.step.ChatResult;
import com.changlu.agentforge.ai.agent.support.RecordingMiddleware;
import com.changlu.agentforge.ai.agent.support.ScriptedChatModel;
import com.changlu.agentforge.ai.agent.support.SideEffectTools;
import com.changlu.agentforge.ai.agent.support.WeatherTools;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.ChatMessageType;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.tool.execution.ToolService;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @description 中间件在非流式ReAct循环中的触发时机与语义用例，全部离线执行
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgentMiddlewareTest {

    private static final Object MEMORY_ID = "middleware-session";

    @Test
    public void shouldFireHooksInOrderForToolRound() {
        ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueText("Hangzhou今天22度，晴。");
        RecordingMiddleware middleware = new RecordingMiddleware();

        ChatResult result = agent(chatModel, settings(0), middleware)
                .run(request("杭州天气怎么样？"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals(Arrays.asList(
                "init",
                "beforeLoop:1",
                "beforeModel:1",
                "afterModel:1:1",
                "beforeTool:getWeather",
                "afterTool:getWeather",
                "afterLoop:1:FINISHED",
                "beforeLoop:2",
                "beforeModel:2",
                "afterModel:2:0",
                "afterLoop:2:STOP",
                "stop:2:NORMAL"), middleware.events());
        // 模型调用后的中间件能拿到本轮真实请求
        assertTrue(middleware.afterModelSawRequest());
        assertEquals(1, middleware.initCount());
    }

    @Test
    public void shouldRewriteToolResultBeforeWritingMemory() {
        ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueText("done");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        RecordingMiddleware middleware = new RecordingMiddleware().toolResultSuffix("[masked]");

        agent(chatModel, memoryProvider, settings(0), toolService(), middleware)
                .run(request("杭州天气怎么样？"));

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        ChatMessage toolMessage = messageOfType(messages, ChatMessageType.TOOL_EXECUTION_RESULT);
        // 中间件改写后的结果才会写回记忆并进入下一轮请求
        assertTrue(toolMessage.text().endsWith("[masked]"));
        assertTrue(toolMessage.text().contains("22 degrees Celsius"));
        assertTrue(chatModel.requests().get(1).messages().toString().contains("[masked]"));
    }

    @Test
    public void shouldAbortModelCallFromMiddleware() {
        ScriptedChatModel chatModel = new ScriptedChatModel().enqueueText("never called");
        RecordingMiddleware middleware = new RecordingMiddleware().abortModelCall();

        ChatResult result = agent(chatModel, settings(0), middleware).run(request("杭州天气怎么样？"));

        assertEquals(0, chatModel.callCount());
        assertEquals("模型调用被中间件中断", result.getRes());
        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals(Arrays.asList("init", "beforeLoop:1", "beforeModel:1",
                "afterLoop:1:STOP", "stop:1:NORMAL"), middleware.events());
    }

    @Test
    public void shouldReportRetryAndModelCallError() {
        ScriptedChatModel chatModel = new ScriptedChatModel().alwaysFail("upstream 500");
        RecordingMiddleware middleware = new RecordingMiddleware();

        ChatResult result = agent(chatModel, settings(2), middleware).run(request("杭州天气怎么样？"));

        assertEquals(AgentRunState.MODEL_CALL_ERROR, result.getRunState());
        assertTrue(result.getRes().contains("模型调用失败"));
        assertEquals(Arrays.asList(
                "init",
                "beforeLoop:1",
                "beforeModel:1",
                "retry:1:1/2",
                "retry:1:2/2",
                "modelError:1",
                "afterLoop:1:STOP",
                "stop:1:NORMAL"), middleware.events());
    }

    @Test
    public void shouldFireCancelHooks() {
        SideEffectTools sideEffectTool = new SideEffectTools();
        ToolService toolService = new ToolService();
        toolService.tools(sideEffectTool);
        final ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "sideEffect", "{\"reason\":\"user stopped\"}")
                .enqueueText("never reached");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        RecordingMiddleware middleware = new RecordingMiddleware();
        final ReActAgent agent = agent(chatModel, memoryProvider, settings(0), toolService, middleware);
        sideEffectTool.onExecute(new Runnable() {
            @Override
            public void run() {
                agent.cancel(MEMORY_ID);
            }
        });

        ChatResult result = agent.run(request("一直查天气"));

        assertEquals(AgentRunState.CANCEL, result.getRunState());
        List<String> events = middleware.events();
        assertTrue(events.contains("loopError:2"));
        assertEquals("stop:2:CANCEL", events.get(events.size() - 1));
        assertTrue(events.indexOf("beforeLoop:1") < events.indexOf("beforeTool:sideEffect"));
    }

    @Test
    public void shouldFireMaxStepsStop() {
        ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueToolCall("call_2", "getWeather", "{\"city\":\"Beijing\"}")
                .enqueueToolCall("call_3", "getWeather", "{\"city\":\"Shanghai\"}");
        RecordingMiddleware middleware = new RecordingMiddleware();

        ChatResult result = agent(chatModel, settings(0, 2), middleware).run(request("一直查天气"));

        assertEquals(AgentRunState.MAX_STEPS, result.getRunState());
        List<String> events = middleware.events();
        assertEquals("stop:3:MAX_STEPS", events.get(events.size() - 1));
        assertEquals(2, Collections_count(events, "beforeModel"));
    }

    @Test
    public void shouldKeepRunningWhenMiddlewareThrows() {
        ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueText("Hangzhou今天22度，晴。");

        ChatResult result = agent(chatModel, settings(0), new IAgentMiddleware() {
            @Override
            public void beforeLoop(int currentStep, AgentChatContext chatContext) {
                throw new IllegalStateException("middleware down");
            }

            @Override
            public ChatResponse afterModelCall(int currentStep,
                                               ChatRequest chatRequest,
                                               ChatResponse chatResponse,
                                               AgentChatContext chatContext) {
                throw new IllegalStateException("middleware down");
            }
        }).run(request("杭州天气怎么样？"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("Hangzhou今天22度，晴。", result.getRes());
    }

    @Test
    public void shouldRunWithLoggingMiddleware() {
        ScriptedChatModel chatModel = new ScriptedChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueText("Hangzhou今天22度，晴。");

        ChatResult result = agent(chatModel, settings(0), new LoggingIAgentMiddleware())
                .run(request("杭州天气怎么样？"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("Hangzhou今天22度，晴。", result.getRes());
    }

    private static int Collections_count(List<String> events, String prefix) {
        int count = 0;
        for (String event : events) {
            if (event.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static ChatMessage messageOfType(List<ChatMessage> messages, ChatMessageType type) {
        for (ChatMessage message : messages) {
            if (message.type() == type) {
                return message;
            }
        }
        throw new AssertionError("no message with type " + type);
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder().memoryId(MEMORY_ID).question(question).build();
    }

    private static AgentSettings settings(int aiCallRetry) {
        return settings(aiCallRetry, 5);
    }

    private static AgentSettings settings(int aiCallRetry, int maxSteps) {
        return AgentSettings.builder()
                .maxSteps(maxSteps)
                .aiCallRetry(aiCallRetry)
                .aiCallRetryDelay(0)
                .build();
    }

    private static ReActAgent agent(ScriptedChatModel chatModel, AgentSettings settings,
                                    IAgentMiddleware... middlewares) {
        return agent(chatModel, ChatMemoryProvider.windowChatMemoryProvider(100), settings,
                toolService(), middlewares);
    }

    private static ToolService toolService() {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return toolService;
    }

    private static ReActAgent agent(ScriptedChatModel chatModel, ChatMemoryProvider memoryProvider,
                                    AgentSettings settings, ToolService toolService,
                                    IAgentMiddleware... middlewares) {
        ReActAgent.ReActAgentBuilder builder = ReActAgent.builder()
                .agentName("middleware-agent")
                .systemPrompt("You are a weather assistant.")
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .agentSettings(settings)
                .middlewares(Arrays.asList(middlewares));
        return builder.build();
    }
}
