package com.changlu.agentforge.ai.agent.component.middleware;

import com.changlu.agentforge.ai.agent.domain.AgentChatContext;
import com.changlu.agentforge.ai.agent.domain.AgentRequest;
import com.changlu.agentforge.ai.agent.domain.StopResult;
import com.changlu.agentforge.ai.agent.step.StepResult;
import com.changlu.agentforge.ai.agent.step.StopResultState;
import com.changlu.agentforge.ai.agent.stream.PartialThinking;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description 中间件管理器用例：注册顺序、流式过滤、结果链式改写、中断语义与异常隔离，全部离线执行
 * @author changlu
 * @date 2026/9/16
 */
public class AgentMiddlewareManagerTest {

    private static final AgentChatContext CONTEXT = context();

    private static AgentChatContext context() {
        return AgentChatContext.builder()
                .request(AgentRequest.builder().memoryId("m-1").question("q").build())
                .build();
    }

    private static ToolExecutionRequest toolRequest() {
        return ToolExecutionRequest.from("call_1", "getWeather", "{\"city\":\"Hangzhou\"}");
    }

    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    @Test
    public void shouldRegisterInOrderAndExposeReadOnlyViews() {
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        IAgentMiddleware first = new NoopMiddleware("first");
        IAgentMiddleware second = new NoopMiddleware("second");

        manager.register(first).register(second).registerAll(Arrays.asList(null, second));

        // 注册顺序即执行顺序，重复实例允许注册；null 被忽略
        assertEquals(3, manager.getMiddlewares().size());
        assertSame(first, manager.getMiddlewares().get(0));

        try {
            manager.getMiddlewares().add(new NoopMiddleware("x"));
            fail("只读视图不应支持修改");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected instanceof UnsupportedOperationException);
        }

        manager.unregister(second);
        // 只移除一个实例，重复注册的第二个second仍在链上
        assertEquals(2, manager.getMiddlewares().size());
        manager.unregister(second);
        assertEquals(1, manager.getMiddlewares().size());

        manager.clear();
        assertTrue(manager.getMiddlewares().isEmpty());
        assertTrue(manager.getStreamingMiddlewares().isEmpty());
    }

    @Test
    public void shouldFilterStreamingMiddlewaresByHandlesStreamingEvents() {
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(new StreamingMiddleware("on", true));
        manager.register(new StreamingMiddleware("off", false));
        manager.register(new NoopMiddleware("plain"));

        assertEquals(3, manager.getMiddlewares().size());
        assertEquals(1, manager.getStreamingMiddlewares().size());
        assertEquals("on", ((StreamingMiddleware) manager.getStreamingMiddlewares().get(0)).id);
    }

    @Test
    public void shouldChainToolResultThroughMiddlewares() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(suffixMiddleware("-a", called));
        manager.register(suffixMiddleware("-b", called));

        String result = manager.triggerAfterToolExecution(toolRequest(), "origin", CONTEXT);

        assertEquals("origin-a-b", result);
        assertEquals(Arrays.asList("a", "b"), called);
    }

    @Test
    public void shouldStopAtFirstMiddlewareThatAbortsModelCall() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(new ChainingMiddleware("a", called, false, false));
        manager.register(new ChainingMiddleware("b", called, true, false));
        manager.register(new ChainingMiddleware("c", called, false, false));

        ChatRequest request = ChatRequest.builder().message(AiMessage.from("hi")).build();
        assertNull(manager.triggerBeforeModelCall(1, request, CONTEXT));
        assertEquals(Arrays.asList("a", "b"), called);
    }

    @Test
    public void shouldKeepOriginalResponseWhenMiddlewareReturnsNull() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        ChatResponse original = response();
        manager.register(new ChainingMiddleware("a", called, false, true));
        manager.register(new ChainingMiddleware("b", called, false, false));

        assertSame(original, manager.triggerAfterModelCall(1, null, original, CONTEXT));
        // 返回null时回退原始响应并停止继续传递
        assertEquals(Collections.singletonList("a"), called);
    }

    @Test
    public void shouldIsolateMiddlewareFailures() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(new ThrowingMiddleware());
        manager.register(new Recording(called));

        manager.triggerOnInitComplete(CONTEXT);
        manager.triggerBeforeLoop(1, CONTEXT);
        manager.triggerAfterLoop(1, StepResult.toRunning(), CONTEXT);
        manager.triggerOnLoopError(1, new IllegalStateException("boom"), CONTEXT);
        manager.triggerBeforeToolExecution(toolRequest(), CONTEXT);
        assertEquals("t", manager.triggerAfterToolExecution(toolRequest(), "t", CONTEXT));
        manager.triggerOnToolExecutionError(toolRequest(), new IllegalStateException("boom"), CONTEXT);
        manager.triggerOnModelCallError(1, null, new IllegalStateException("boom"), CONTEXT);
        manager.triggerOnAiCallRetry(1, null, CONTEXT, 1, 2, 100L, new IllegalStateException("boom"));
        manager.triggerOnStop(1, StopResult.builder()
                .stopResultState(StopResultState.NORMAL).runRes("x").build(), CONTEXT);
        manager.triggerOnStopWithError(1, new IllegalStateException("boom"), CONTEXT);
        manager.triggerOnPartialResponse(1, "delta", CONTEXT);
        manager.triggerOnPartialThinking(1, new PartialThinking("reasoning"), CONTEXT);
        manager.triggerOnIntermediateResponse(1, response(), CONTEXT);

        // 抛异常的中间件不影响后续中间件
        assertEquals(Arrays.asList("init", "beforeLoop", "afterLoop", "loopError", "beforeTool",
                "afterTool", "toolError", "modelError", "retry", "stop", "stopError", "partial",
                "think", "intermediate"), called);
    }

    @Test
    public void shouldDispatchPartialResponseOnlyToStreamingMiddlewares() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(new Recording(called));
        manager.register(new StreamingMiddleware("s", true) {
            @Override
            public void onPartialResponse(int currentStep, String partialResponse,
                                          AgentChatContext chatContext) {
                called.add("partial:" + partialResponse);
            }
        });

        manager.triggerOnPartialResponse(1, "delta", CONTEXT);

        // Recording也实现了流式接口，因此两条都会收到
        assertEquals(Arrays.asList("partial", "partial:delta"), called);
    }

    @Test
    public void shouldDispatchThinkingAndIntermediateOnlyToStreamingMiddlewares() {
        final List<String> called = new ArrayList<String>();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        manager.register(new Recording(called));
        manager.register(new StreamingMiddleware("s", true) {
            @Override
            public void onPartialThinking(int currentStep, PartialThinking partialThinking,
                                          AgentChatContext chatContext) {
                called.add("think:" + partialThinking.text());
            }

            @Override
            public void onIntermediateResponse(int currentStep, ChatResponse intermediateResponse,
                                               AgentChatContext chatContext) {
                called.add("intermediate");
            }
        });

        manager.triggerOnPartialThinking(1, new PartialThinking("reasoning"), CONTEXT);
        manager.triggerOnIntermediateResponse(1, response(), CONTEXT);

        assertEquals(Arrays.asList("think", "think:reasoning", "intermediate", "intermediate"), called);
    }

    @Test
    public void shouldReturnInputsWhenNoMiddlewareRegistered() {
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        ChatRequest request = ChatRequest.builder().message(AiMessage.from("hi")).build();
        ChatResponse response = response();

        assertSame(request, manager.triggerBeforeModelCall(1, request, CONTEXT));
        assertSame(response, manager.triggerAfterModelCall(1, request, response, CONTEXT));
        assertEquals("t", manager.triggerAfterToolExecution(toolRequest(), "t", CONTEXT));

        manager.triggerOnInitComplete(CONTEXT);
        manager.triggerBeforeLoop(1, CONTEXT);
        manager.triggerAfterLoop(1, StepResult.toStop("x"), CONTEXT);
        manager.triggerOnStop(1, null, CONTEXT);
        manager.triggerOnPartialResponse(1, "d", CONTEXT);
        manager.triggerOnPartialThinking(1, new PartialThinking("r"), CONTEXT);
        manager.triggerOnIntermediateResponse(1, response(), CONTEXT);
    }

    private static IAgentMiddleware suffixMiddleware(final String suffix, final List<String> called) {
        return new IAgentMiddleware() {
            @Override
            public String afterToolExecution(ToolExecutionRequest toolRequest, String toolResult,
                                             AgentChatContext chatContext) {
                called.add(suffix.substring(1));
                return toolResult + suffix;
            }
        };
    }

    private static class NoopMiddleware implements IAgentMiddleware {
        private final String id;

        NoopMiddleware(String id) {
            this.id = id;
        }
    }

    private static class StreamingMiddleware implements IStreamingIAgentMiddleware {
        private final String id;
        private final boolean handles;

        StreamingMiddleware(String id, boolean handles) {
            this.id = id;
            this.handles = handles;
        }

        @Override
        public boolean handlesStreamingEvents() {
            return handles;
        }
    }

    private static class ChainingMiddleware implements IAgentMiddleware {
        private final String id;
        private final List<String> called;
        private final boolean abortModelCall;
        private final boolean nullResponse;

        ChainingMiddleware(String id, List<String> called, boolean abortModelCall, boolean nullResponse) {
            this.id = id;
            this.called = called;
            this.abortModelCall = abortModelCall;
            this.nullResponse = nullResponse;
        }

        @Override
        public ChatRequest beforeModelCall(int currentStep, ChatRequest chatRequest,
                                           AgentChatContext chatContext) {
            called.add(id);
            return abortModelCall ? null : chatRequest;
        }

        @Override
        public ChatResponse afterModelCall(int currentStep, ChatRequest chatRequest,
                                           ChatResponse chatResponse, AgentChatContext chatContext) {
            called.add(id);
            return nullResponse ? null : chatResponse;
        }
    }

    private static class ThrowingMiddleware implements IStreamingIAgentMiddleware {
        private void boom() {
            throw new IllegalStateException("middleware failure");
        }

        @Override
        public void onInitComplete(AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void beforeLoop(int currentStep, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void afterLoop(int currentStep, StepResult stepResult, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void beforeToolExecution(ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public String afterToolExecution(ToolExecutionRequest toolRequest, String toolResult,
                                         AgentChatContext chatContext) {
            boom();
            return toolResult;
        }

        @Override
        public void onToolExecutionError(ToolExecutionRequest toolRequest, Throwable error,
                                         AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onModelCallError(int currentStep, ChatRequest chatRequest, Throwable error,
                                     AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onAiCallRetry(int currentStep, ChatRequest chatRequest, AgentChatContext chatContext,
                                  int retryCount, int maxRetries, long delayMs, Exception lastException) {
            boom();
        }

        @Override
        public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onPartialResponse(int currentStep, String partialResponse,
                                      AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onPartialThinking(int currentStep, PartialThinking partialThinking,
                                      AgentChatContext chatContext) {
            boom();
        }

        @Override
        public void onIntermediateResponse(int currentStep, ChatResponse intermediateResponse,
                                           AgentChatContext chatContext) {
            boom();
        }
    }

    private static class Recording implements IStreamingIAgentMiddleware {
        private final List<String> called;

        Recording(List<String> called) {
            this.called = called;
        }

        @Override
        public void onInitComplete(AgentChatContext chatContext) {
            called.add("init");
        }

        @Override
        public void beforeLoop(int currentStep, AgentChatContext chatContext) {
            called.add("beforeLoop");
        }

        @Override
        public void afterLoop(int currentStep, StepResult stepResult, AgentChatContext chatContext) {
            called.add("afterLoop");
        }

        @Override
        public void onLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {
            called.add("loopError");
        }

        @Override
        public void beforeToolExecution(ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
            called.add("beforeTool");
        }

        @Override
        public String afterToolExecution(ToolExecutionRequest toolRequest, String toolResult,
                                         AgentChatContext chatContext) {
            called.add("afterTool");
            return toolResult;
        }

        @Override
        public void onToolExecutionError(ToolExecutionRequest toolRequest, Throwable error,
                                         AgentChatContext chatContext) {
            called.add("toolError");
        }

        @Override
        public void onModelCallError(int currentStep, ChatRequest chatRequest, Throwable error,
                                     AgentChatContext chatContext) {
            called.add("modelError");
        }

        @Override
        public void onAiCallRetry(int currentStep, ChatRequest chatRequest, AgentChatContext chatContext,
                                  int retryCount, int maxRetries, long delayMs, Exception lastException) {
            called.add("retry");
        }

        @Override
        public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
            called.add("stop");
        }

        @Override
        public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
            called.add("stopError");
        }

        @Override
        public void onPartialResponse(int currentStep, String partialResponse,
                                      AgentChatContext chatContext) {
            called.add("partial");
        }

        @Override
        public void onPartialThinking(int currentStep, PartialThinking partialThinking,
                                      AgentChatContext chatContext) {
            called.add("think");
        }

        @Override
        public void onIntermediateResponse(int currentStep, ChatResponse intermediateResponse,
                                           AgentChatContext chatContext) {
            called.add("intermediate");
        }
    }
}
