package cloud.changlu.agentforge.agent;

import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.step.ChatResultState;
import cloud.changlu.agentforge.agent.support.ScriptedChatModel;
import cloud.changlu.agentforge.agent.support.SideEffectTools;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description 非流式ReAct循环用例：think + act + 步长上限 + 取消 + 重试，全部离线执行
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgentTest {

    private static final Object MEMORY_ID = "session-1";

    @Test
    public void shouldRunToolRoundAndReturnFinalAnswer() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("Hangzhou今天22度，晴。");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        chatModel,
                        toolService(new WeatherTools()),
                        memoryProvider,
                        defaultSettings());

        ChatResult result = agent.run(request("杭州天气怎么样？"));

        assertEquals(ChatResultState.FINISHED, result.getChatResultState());
        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("杭州天气怎么样？", result.getQuestion());
        assertEquals("Hangzhou今天22度，晴。", result.getRes());
        assertEquals(2, chatModel.callCount());

        // 工具声明随每一轮请求一起下发
        assertEquals(2, chatModel.requests().get(0).parameters().tools().size());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        assertEquals(ChatMessageType.SYSTEM, messages.get(0).type());
        assertEquals(ChatMessageType.USER, messages.get(1).type());
        assertEquals(ChatMessageType.AI, messages.get(2).type());
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, messages.get(3).type());
        assertTrue(messages.get(3).text().contains("22 degrees Celsius"));
        assertEquals(ChatMessageType.AI, messages.get(4).type());
        // 第二轮请求带上了工具执行结果
        assertTrue(chatModel.requests().get(1).messages().size() >= 4);
    }

    @Test
    public void shouldAnswerDirectlyWithoutAnyToolCall() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel().enqueueText("AgentForge是一个Java Agent框架。");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        chatModel,
                        toolService(new WeatherTools()),
                        memoryProvider,
                        defaultSettings());

        ChatResult result = agent.run(request("AgentForge是什么？"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("AgentForge是一个Java Agent框架。", result.getRes());
        assertEquals(1, chatModel.callCount());
        assertEquals(3, memoryProvider.get(MEMORY_ID).messages().size());
    }

    @Test
    public void shouldStopWhenReachingMaxSteps() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueToolCall("call_2", "getWeather", "{\"city\":\"Beijing\"}")
                        .enqueueToolCall("call_3", "getWeather", "{\"city\":\"Shanghai\"}");
        AgentSettings settings = AgentSettings.builder().maxSteps(2).aiCallRetry(0).build();

        ChatResult result =
                agent(
                                chatModel,
                                toolService(new WeatherTools()),
                                ChatMemoryProvider.windowChatMemoryProvider(100),
                                settings)
                        .run(request("一直查天气"));

        assertEquals(AgentRunState.MAX_STEPS, result.getRunState());
        assertEquals("执行步长达到限制，请重新询问你的问题！", result.getRes());
        assertEquals(Integer.valueOf(1003), result.getRunStateCode());
        assertEquals(2, chatModel.callCount());
    }

    @Test
    public void shouldReportModelCallErrorAndFinish() {
        ScriptedChatModel chatModel = new ScriptedChatModel().alwaysFail("connection reset");
        AgentSettings settings = AgentSettings.builder().aiCallRetry(0).aiCallRetryDelay(0).build();

        ChatResult result =
                agent(
                                chatModel,
                                toolService(new WeatherTools()),
                                ChatMemoryProvider.windowChatMemoryProvider(100),
                                settings)
                        .run(request("杭州天气怎么样？"));

        assertEquals(AgentRunState.MODEL_CALL_ERROR, result.getRunState());
        assertTrue(result.getRes().contains("模型调用失败"));
        assertTrue(result.getRes().contains("connection reset"));
        assertEquals(1, chatModel.callCount());
    }

    @Test
    public void shouldRetryModelCallBeforeSucceeding() {
        ScriptedChatModel chatModel = new ScriptedChatModel().failFirst(2).enqueueText("recovered");
        AgentSettings settings = AgentSettings.builder().aiCallRetry(2).aiCallRetryDelay(0).build();

        ChatResult result =
                agent(
                                chatModel,
                                toolService(new WeatherTools()),
                                ChatMemoryProvider.windowChatMemoryProvider(100),
                                settings)
                        .run(request("重试一次"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("recovered", result.getRes());
        assertEquals(3, chatModel.callCount());
    }

    @Test
    public void shouldCancelRunningLoop() {
        SideEffectTools sideEffectTool = new SideEffectTools();
        ToolService toolService = toolService(sideEffectTool);
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "sideEffect", "{\"reason\":\"user stopped\"}");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        final ReActAgent agent = agent(chatModel, toolService, memoryProvider, defaultSettings());
        sideEffectTool.onExecute(
                new Runnable() {
                    @Override
                    public void run() {
                        agent.cancel(MEMORY_ID);
                    }
                });

        ChatResult result = agent.run(request("查一下然后取消"));

        assertEquals(AgentRunState.CANCEL, result.getRunState());
        assertEquals("任务已被用户取消", result.getRes());
        assertEquals(Integer.valueOf(1002), result.getRunStateCode());
        // 运行结束后取消标志被清理，不会串扰下一轮
        assertFalse(agent.isCancelled(MEMORY_ID));
        assertEquals(1, chatModel.callCount());
    }

    @Test
    public void shouldWriteBackUnknownToolAsFailedResultAndContinue() {
        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall("call_1", "notRegistered", "{}")
                        .enqueueText("已经没有可用工具了");

        ChatResult result =
                agent(
                                chatModel,
                                toolService(new WeatherTools()),
                                ChatMemoryProvider.windowChatMemoryProvider(100),
                                defaultSettings())
                        .run(request("调用一个不存在的工具"));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertEquals("已经没有可用工具了", result.getRes());
        assertEquals(2, chatModel.callCount());
    }

    @Test
    public void shouldRefreshSystemPromptOnEveryRun() {
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        new ScriptedChatModel().enqueueText("first").enqueueText("second"),
                        toolService(new WeatherTools()),
                        memoryProvider,
                        defaultSettings());

        agent.run(request("第一轮"));
        agent.run(request("第二轮"));

        int systemMessages = 0;
        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.SYSTEM) {
                systemMessages++;
            }
        }
        assertEquals("系统提示词只保留最新的一条", 1, systemMessages);
        assertEquals(ChatMessageType.SYSTEM, messages.get(0).type());
        assertEquals(2, countType(messages, ChatMessageType.USER));
    }

    @Test
    public void shouldAppendConcurrentPromptWhenMultipleToolsAndSwitchOn() {
        ReActAgent agent =
                agent(
                        new ScriptedChatModel(),
                        toolService(new WeatherTools()),
                        ChatMemoryProvider.windowChatMemoryProvider(10),
                        defaultSettings());

        assertTrue(agent.getSystemPrompt().contains("Concurrent Tool Execution"));
    }

    @Test
    public void shouldNotAppendConcurrentPromptWhenSwitchOff() {
        AgentSettings settings =
                AgentSettings.builder()
                        .aiCallRetry(0)
                        .aiCallRetryDelay(0)
                        .enableConcurrentToolExecution(false)
                        .build();
        ReActAgent agent =
                agent(
                        new ScriptedChatModel(),
                        toolService(new WeatherTools()),
                        ChatMemoryProvider.windowChatMemoryProvider(10),
                        settings);

        assertFalse(agent.getSystemPrompt().contains("Concurrent Tool Execution"));
    }

    @Test
    public void shouldClearContextAndValidateRequest() {
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent =
                agent(
                        new ScriptedChatModel().enqueueText("done"),
                        toolService(new WeatherTools()),
                        memoryProvider,
                        defaultSettings());
        agent.run(request("一个问题"));
        assertFalse(memoryProvider.get(MEMORY_ID).messages().isEmpty());

        assertTrue(agent.clearContext(MEMORY_ID));
        assertTrue(memoryProvider.get(MEMORY_ID).messages().isEmpty());

        try {
            AgentRequest.builder().question("问题").build();
            fail("Expected memoryId validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("memoryId"));
        }

        try {
            AgentRequest.builder().memoryId(MEMORY_ID).question("   ").build();
            fail("Expected question validation");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("question"));
        }
    }

    private static int countType(List<ChatMessage> messages, ChatMessageType type) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.type() == type) {
                count++;
            }
        }
        return count;
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder().memoryId(MEMORY_ID).question(question).build();
    }

    private static AgentSettings defaultSettings() {
        return AgentSettings.builder().aiCallRetry(0).aiCallRetryDelay(0).build();
    }

    private static ToolService toolService(Object tools) {
        ToolService toolService = new ToolService();
        toolService.tools(tools);
        return toolService;
    }

    private static ReActAgent agent(
            ScriptedChatModel chatModel,
            ToolService toolService,
            ChatMemoryProvider memoryProvider,
            AgentSettings settings) {
        return ReActAgent.builder()
                .agentName("weather-agent")
                .description("weather demo agent")
                .systemPrompt("You are a weather assistant. Use the weather tools to answer.")
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .agentSettings(settings)
                .build();
    }
}
