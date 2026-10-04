package com.changlu.agentforge.agent;

import com.changlu.agentforge.agent.domain.AgentRequest;
import com.changlu.agentforge.agent.domain.AgentSettings;
import com.changlu.agentforge.agent.exception.AgentException;
import com.changlu.agentforge.agent.exception.CancelException;
import com.changlu.agentforge.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.agent.stream.TokenStream;
import com.changlu.agentforge.agent.support.ScriptedStreamingChatModel;
import com.changlu.agentforge.agent.support.SideEffectTools;
import com.changlu.agentforge.agent.support.WeatherTools;
import com.changlu.agentforge.model.chat.message.ChatMessage;
import com.changlu.agentforge.model.chat.message.ChatMessageType;
import com.changlu.agentforge.model.chat.response.ChatResponse;
import com.changlu.agentforge.model.tool.execution.ToolExecution;
import com.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description 流式ReAct用例：增量回调、工具轮自动续跑、错误与取消，全部离线执行
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgentStreamTest {

    private static final Object MEMORY_ID = "stream-session";

    @Test
    public void shouldStreamToolRoundThenFinalAnswer() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                        .enqueueText("杭州", "今天22度", "，晴。");
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent = agent(streamingModel, memoryProvider, AgentSettings.builder().build());

        final List<String> partials = new ArrayList<String>();
        final List<ToolExecution> executions = new ArrayList<ToolExecution>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        agent.runStream(request("杭州天气怎么样？"))
                .onPartialResponse(
                        new Consumer<String>() {
                            @Override
                            public void accept(String partial) {
                                partials.add(partial);
                            }
                        })
                .onToolExecuted(
                        new Consumer<ToolExecution>() {
                            @Override
                            public void accept(ToolExecution execution) {
                                executions.add(execution);
                            }
                        })
                .onCompleteResponse(
                        new Consumer<ChatResponse>() {
                            @Override
                            public void accept(ChatResponse response) {
                                complete.set(response);
                            }
                        })
                .onError(
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                error.set(throwable);
                            }
                        })
                .start();

        assertNull("不应出现错误: " + error.get(), error.get());
        assertEquals("杭州今天22度，晴。", String.join("", partials.toArray(new String[0])));
        assertEquals("杭州今天22度，晴。", complete.get().aiMessage().text());
        assertEquals(2, streamingModel.callCount());

        assertEquals(1, executions.size());
        assertEquals("getWeather", executions.get(0).request().name());
        assertTrue(executions.get(0).resultText().contains("22 degrees Celsius"));
        assertFalse(executions.get(0).hasFailed());

        List<ChatMessage> messages = memoryProvider.get(MEMORY_ID).messages();
        assertEquals(ChatMessageType.SYSTEM, messages.get(0).type());
        assertEquals(ChatMessageType.USER, messages.get(1).type());
        assertEquals(ChatMessageType.AI, messages.get(2).type());
        assertEquals(ChatMessageType.TOOL_EXECUTION_RESULT, messages.get(3).type());
        assertEquals(ChatMessageType.AI, messages.get(4).type());
        // 工具声明同样下发给流式模型
        assertEquals(2, streamingModel.requests().get(0).parameters().tools().size());
    }

    @Test
    public void shouldStreamFinalAnswerWithoutToolCall() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel().enqueueText("Age", "ntForge");
        AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent(
                        streamingModel,
                        ChatMemoryProvider.windowChatMemoryProvider(100),
                        AgentSettings.builder().build())
                .runStream(request("AgentForge是什么？"))
                .onCompleteResponse(consumer(complete))
                .start();

        assertEquals("AgentForge", complete.get().aiMessage().text());
        assertEquals(1, streamingModel.callCount());
    }

    @Test
    public void shouldReportStreamingError() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueError(new IllegalStateException("stream boom"));
        ChatMemoryProvider memoryProvider = ChatMemoryProvider.windowChatMemoryProvider(100);
        ReActAgent agent = agent(streamingModel, memoryProvider, AgentSettings.builder().build());
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent.runStream(request("一个问题"))
                .onError(
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                error.set(throwable);
                            }
                        })
                .onCompleteResponse(consumer(complete))
                .start();

        assertTrue(error.get() instanceof IllegalStateException);
        assertTrue(error.get().getMessage().contains("stream boom"));
        assertNull(complete.get());
        // 错误结束后取消标志被清理
        assertFalse(agent.isCancelled(MEMORY_ID));
    }

    @Test
    public void shouldIgnoreErrorsWhenRequested() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel().enqueueError(new IllegalStateException("ignored"));

        agent(
                        streamingModel,
                        ChatMemoryProvider.windowChatMemoryProvider(100),
                        AgentSettings.builder().build())
                .runStream(request("一个问题"))
                .ignoreErrors()
                .start();

        assertEquals(1, streamingModel.callCount());
    }

    @Test
    public void shouldStopStreamingAtMaxSteps() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}");
        AgentSettings settings = AgentSettings.builder().maxSteps(1).build();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        agent(streamingModel, ChatMemoryProvider.windowChatMemoryProvider(100), settings)
                .runStream(request("杭州天气怎么样？"))
                .onError(
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                error.set(throwable);
                            }
                        })
                .start();

        assertTrue(error.get() instanceof AgentException);
        assertTrue(error.get().getMessage().contains("执行步长达到限制"));
        assertEquals(1, streamingModel.callCount());
    }

    @Test
    public void shouldCancelStreamingRunAfterToolExecution() {
        SideEffectTools sideEffectTools = new SideEffectTools();
        ToolService toolService = new ToolService();
        toolService.tools(sideEffectTools);
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel()
                        .enqueueToolCall("call_1", "sideEffect", "{\"reason\":\"user stopped\"}")
                        .enqueueText("不该被调用");
        ReActAgent agent =
                ReActAgent.builder()
                        .systemPrompt("You are a test assistant.")
                        .streamingChatModel(streamingModel)
                        .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(100))
                        .toolService(toolService)
                        .agentSettings(AgentSettings.builder().build())
                        .build();
        sideEffectTools.onExecute(
                new Runnable() {
                    @Override
                    public void run() {
                        agent.cancel(MEMORY_ID);
                    }
                });
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        agent.runStream(request("查一下然后取消"))
                .onError(capture(error))
                .onCompleteResponse(consumer(complete))
                .start();

        assertTrue(error.get() instanceof CancelException);
        assertNull(complete.get());
        assertEquals(1, streamingModel.callCount());
    }

    @Test
    public void shouldRejectRepeatedStart() {
        ScriptedStreamingChatModel streamingModel =
                new ScriptedStreamingChatModel().enqueueText("done");
        TokenStream tokenStream =
                agent(
                                streamingModel,
                                ChatMemoryProvider.windowChatMemoryProvider(100),
                                AgentSettings.builder().build())
                        .runStream(request("一个问题"));
        tokenStream.start();

        try {
            tokenStream.start();
            fail("Expected a repeated start error");
        } catch (AgentException e) {
            assertTrue(e.getMessage().contains("已经启动"));
        }
    }

    private static Consumer<Throwable> capture(final AtomicReference<Throwable> target) {
        return new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) {
                target.set(throwable);
            }
        };
    }

    private static Consumer<ChatResponse> consumer(final AtomicReference<ChatResponse> target) {
        return new Consumer<ChatResponse>() {
            @Override
            public void accept(ChatResponse response) {
                target.set(response);
            }
        };
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder().memoryId(MEMORY_ID).question(question).build();
    }

    private static ReActAgent agent(
            ScriptedStreamingChatModel streamingModel,
            ChatMemoryProvider memoryProvider,
            AgentSettings settings) {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return ReActAgent.builder()
                .systemPrompt("You are a weather assistant. Use the weather tools to answer.")
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProvider)
                .toolService(toolService)
                .agentSettings(settings)
                .build();
    }
}
