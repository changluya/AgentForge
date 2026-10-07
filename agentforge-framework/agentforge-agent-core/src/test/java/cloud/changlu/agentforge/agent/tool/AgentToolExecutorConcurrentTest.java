package cloud.changlu.agentforge.agent.tool;

import cloud.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import cloud.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.memory.WindowChatMemory;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * @description 工具执行器并发/顺序分支用例：并发时触发并发中间件回调且结果保序，关闭并发时回退顺序回调
 * @author changlu
 * @date 2026/10/07
 */
public class AgentToolExecutorConcurrentTest {

    private static AgentChatContext context() {
        return AgentChatContext.builder()
                .request(AgentRequest.builder().memoryId("m-1").question("q").build())
                .chatMemory(new WindowChatMemory("m-1", 50))
                .build();
    }

    private static ToolService toolService() {
        ToolService toolService = new ToolService();
        toolService.tools(Collections.<Object>singletonList(new WeatherTools()));
        return toolService;
    }

    private static List<ToolExecutionRequest> batch() {
        return Arrays.asList(
                ToolExecutionRequest.from("c1", "getWeather", "{\"city\":\"Hangzhou\"}"),
                ToolExecutionRequest.from("c2", "getWeatherAdvice", "{\"city\":\"Hangzhou\"}"));
    }

    @Test
    public void concurrentPathUsesConcurrentCallbacksAndKeepsOrder() {
        AgentChatContext context = context();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        RecordingMiddleware middleware = new RecordingMiddleware();
        manager.register(middleware);

        AgentToolExecutor executor = new AgentToolExecutor(toolService());
        List<ToolExecutionResultMessage> messages =
                executor.execute(1, batch(), context, null, manager, true);

        assertEquals(1, middleware.beforeConcurrent.get());
        assertEquals(2, middleware.batchSize);
        assertEquals(2, middleware.complete.get());
        assertEquals(1, middleware.afterConcurrent.get());
        assertEquals(0, middleware.beforeTool.get());
        assertEquals(0, middleware.afterTool.get());

        assertEquals(2, messages.size());
        assertEquals("c1", messages.get(0).id());
        assertEquals("c2", messages.get(1).id());
        assertEquals(2, context.getChatMemory().messages().size());
    }

    @Test
    public void sequentialPathUsesSingleToolCallbacksWhenConcurrentDisabled() {
        AgentChatContext context = context();
        AgentMiddlewareManager manager = new AgentMiddlewareManager();
        RecordingMiddleware middleware = new RecordingMiddleware();
        manager.register(middleware);

        AgentToolExecutor executor = new AgentToolExecutor(toolService());
        List<ToolExecutionResultMessage> messages =
                executor.execute(1, batch(), context, null, manager, false);

        assertEquals(0, middleware.beforeConcurrent.get());
        assertEquals(0, middleware.afterConcurrent.get());
        assertEquals(2, middleware.beforeTool.get());
        assertEquals(2, middleware.afterTool.get());
        assertEquals(2, messages.size());
    }

    private static final class RecordingMiddleware implements IAgentMiddleware {

        private final AtomicInteger beforeConcurrent = new AtomicInteger();
        private final AtomicInteger complete = new AtomicInteger();
        private final AtomicInteger afterConcurrent = new AtomicInteger();
        private final AtomicInteger beforeTool = new AtomicInteger();
        private final AtomicInteger afterTool = new AtomicInteger();
        private volatile int batchSize;

        @Override
        public void beforeConcurrentToolExecution(
                List<ToolExecutionRequest> toolRequests, AgentChatContext chatContext) {
            beforeConcurrent.incrementAndGet();
            batchSize = toolRequests.size();
        }

        @Override
        public void onConcurrentToolExecutionComplete(
                ToolExecutionRequest toolRequest,
                String toolResult,
                Throwable error,
                AgentChatContext chatContext) {
            complete.incrementAndGet();
        }

        @Override
        public List<String> afterConcurrentToolExecution(
                List<ToolExecutionRequest> toolRequests,
                List<String> toolResults,
                AgentChatContext chatContext) {
            afterConcurrent.incrementAndGet();
            return toolResults;
        }

        @Override
        public void beforeToolExecution(
                ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
            beforeTool.incrementAndGet();
        }

        @Override
        public String afterToolExecution(
                ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
            afterTool.incrementAndGet();
            return toolResult;
        }
    }
}
