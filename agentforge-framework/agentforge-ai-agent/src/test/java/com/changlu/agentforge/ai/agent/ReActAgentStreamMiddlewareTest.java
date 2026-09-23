package com.changlu.agentforge.ai.agent;

import com.changlu.agentforge.ai.agent.component.middleware.IAgentMiddleware;
import com.changlu.agentforge.ai.agent.domain.AgentRequest;
import com.changlu.agentforge.ai.agent.domain.AgentSettings;
import com.changlu.agentforge.ai.agent.exception.AgentException;
import com.changlu.agentforge.ai.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.ai.agent.support.RecordingMiddleware;
import com.changlu.agentforge.ai.agent.support.ScriptedStreamingChatModel;
import com.changlu.agentforge.ai.agent.support.WeatherTools;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.tool.execution.ToolService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description 中间件在流式ReAct循环中的触发时机用例：增量事件、工具轮、中断语义，全部离线执行
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgentStreamMiddlewareTest {

    private static final Object MEMORY_ID = "stream-middleware-session";

    @Test
    public void shouldFireHooksInOrderAcrossStreamingToolRound() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueText("杭州", "今天22度");
        RecordingMiddleware middleware = new RecordingMiddleware();

        runStream(streamingModel, middleware);

        assertEquals(Arrays.asList(
                "init",
                "beforeLoop:1",
                "beforeModel:1",
                "afterModel:1:1",
                "beforeTool:getWeather",
                "afterTool:getWeather",
                "intermediate:1",
                "afterLoop:1:FINISHED",
                "beforeLoop:2",
                "beforeModel:2",
                "partial:2:杭州",
                "partial:2:今天22度",
                "afterModel:2:0",
                "afterLoop:2:STOP",
                "stop:2:NORMAL"), middleware.events());
        assertTrue(middleware.afterModelSawRequest());
        assertEquals(1, middleware.initCount());
    }

    @Test
    public void shouldForwardThinkingDeltasToMiddleware() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel()
                .enqueueReasonedText(new String[]{"让我", "想想"}, "杭州", "今天22度");
        RecordingMiddleware middleware = new RecordingMiddleware();

        runStream(streamingModel, middleware);

        assertEquals(Arrays.asList(
                "init",
                "beforeLoop:1",
                "beforeModel:1",
                "think:1:让我",
                "think:1:想想",
                "partial:1:杭州",
                "partial:1:今天22度",
                "afterModel:1:0",
                "afterLoop:1:STOP",
                "stop:1:NORMAL"), middleware.events());
    }

    @Test
    public void shouldDeliverThinkingAndIntermediateToStreamHandler() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel()
                .enqueueToolCall("call_1", "getWeather", "{\"city\":\"Hangzhou\"}")
                .enqueueReasonedText(new String[]{"先查一下"}, "杭州今天22度");
        final List<String> thoughts = new ArrayList<String>();
        final AtomicReference<ChatResponse> intermediate = new AtomicReference<ChatResponse>();

        agent(streamingModel, new RecordingMiddleware()).runStream(request("杭州天气怎么样？"))
                .onPartialThinking(new Consumer<com.changlu.agentforge.ai.agent.stream.PartialThinking>() {
                    @Override
                    public void accept(com.changlu.agentforge.ai.agent.stream.PartialThinking partialThinking) {
                        thoughts.add(partialThinking.text());
                    }
                })
                .onIntermediateResponse(new Consumer<ChatResponse>() {
                    @Override
                    public void accept(ChatResponse response) {
                        intermediate.set(response);
                    }
                })
                .start();

        assertEquals(Arrays.asList("先查一下"), thoughts);
        assertNotNull(intermediate.get());
        assertEquals(1, intermediate.get().aiMessage().toolExecutionRequests().size());
        assertEquals("getWeather", intermediate.get().aiMessage().toolExecutionRequests().get(0).name());
    }

    @Test
    public void shouldAbortStreamingModelCallFromMiddleware() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel().enqueueText("never");
        RecordingMiddleware middleware = new RecordingMiddleware().abortModelCall();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        runStream(streamingModel, error, middleware);

        assertEquals(0, streamingModel.callCount());
        assertTrue(error.get() instanceof AgentException);
        assertEquals("模型调用被中间件中断", error.get().getMessage());
        assertEquals(Arrays.asList("init", "beforeLoop:1", "beforeModel:1",
                "loopError:1", "stop:1:CANCEL"), middleware.events());
    }

    @Test
    public void shouldSkipPartialEventsForNonStreamingMiddleware() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel()
                .enqueueText("Age", "ntForge");
        RecordingMiddleware middleware = new RecordingMiddleware().streamingEvents(false);

        runStream(streamingModel, middleware);

        // 非流式中间件不再接收增量事件，其余回调保持触发
        assertEquals(Arrays.asList("init", "beforeLoop:1", "beforeModel:1",
                "afterModel:1:0", "afterLoop:1:STOP", "stop:1:NORMAL"), middleware.events());
    }

    @Test
    public void shouldKeepStreamingWhenMiddlewareThrows() {
        ScriptedStreamingChatModel streamingModel = new ScriptedStreamingChatModel().enqueueText("ok");
        final List<String> partials = new ArrayList<String>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();

        ReActAgent agent = agent(streamingModel, new IAgentMiddleware() {
            @Override
            public void beforeLoop(int currentStep,
                                   com.changlu.agentforge.ai.agent.domain.AgentChatContext chatContext) {
                throw new IllegalStateException("middleware down");
            }
        });
        agent.runStream(request("介绍一下AgentForge"))
                .onPartialResponse(new Consumer<String>() {
                    @Override
                    public void accept(String partial) {
                        partials.add(partial);
                    }
                })
                .onCompleteResponse(new Consumer<ChatResponse>() {
                    @Override
                    public void accept(ChatResponse response) {
                        complete.set(response);
                    }
                })
                .onError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        fail("中间件异常不应中断流式执行: " + throwable.getMessage());
                    }
                })
                .start();

        assertEquals("ok", complete.get().aiMessage().text());
    }

    private static void runStream(ScriptedStreamingChatModel streamingModel,
                                  IAgentMiddleware middleware) {
        runStream(streamingModel, new AtomicReference<Throwable>(), middleware);
    }

    private static void runStream(ScriptedStreamingChatModel streamingModel,
                                  AtomicReference<Throwable> error,
                                  IAgentMiddleware middleware) {
        List<String> partials = new ArrayList<String>();
        agent(streamingModel, middleware).runStream(request("杭州天气怎么样？"))
                .onPartialResponse(capture(partials))
                .onCompleteResponse(new Consumer<ChatResponse>() {
                    @Override
                    public void accept(ChatResponse response) {
                        // 断言在调用方按事件顺序完成
                    }
                })
                .onError(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        error.set(throwable);
                    }
                })
                .start();
    }

    private static Consumer<String> capture(final List<String> sink) {
        return new Consumer<String>() {
            @Override
            public void accept(String partial) {
                sink.add(partial);
            }
        };
    }

    private static AgentRequest request(String question) {
        return AgentRequest.builder().memoryId(MEMORY_ID).question(question).build();
    }

    private static ReActAgent agent(ScriptedStreamingChatModel streamingModel,
                                    IAgentMiddleware middleware) {
        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());
        return ReActAgent.builder()
                .agentName("stream-middleware-agent")
                .systemPrompt("You are a weather assistant.")
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(100))
                .toolService(toolService)
                .agentSettings(AgentSettings.builder().maxSteps(3).build())
                .middleware(middleware)
                .build();
    }
}
