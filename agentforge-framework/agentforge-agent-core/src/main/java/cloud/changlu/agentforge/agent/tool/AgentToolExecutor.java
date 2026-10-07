package cloud.changlu.agentforge.agent.tool;

import cloud.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolExecution;
import cloud.changlu.agentforge.model.tool.execution.ToolExecutionResult;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @description act阶段工具批次的执行器：查找执行器、执行工具、把结果写回ChatMemory，供流式与非流式共用
 * @author changlu
 * @date 2026/9/16
 */
public class AgentToolExecutor {

    private static final String EMPTY_RESULT_TEXT = "tool exec success, but no result";

    /** 并发执行工具时的最大线程数，避免单批次请求打满线程 */
    private static final int MAX_CONCURRENT_TOOLS = 8;

    private final ToolService toolService;

    public AgentToolExecutor(ToolService toolService) {
        this.toolService = toolService;
    }

    /**
     * 顺序执行本轮模型请求的全部工具
     *
     * @param currentStep 当前步骤
     * @param toolRequests 模型返回的工具调用请求
     * @param chatContext 对话上下文
     * @param listener 每个工具执行完成后的回调，可为null
     * @return 工具执行结果消息列表
     */
    public List<ToolExecutionResultMessage> execute(
            int currentStep,
            List<ToolExecutionRequest> toolRequests,
            AgentChatContext chatContext,
            Consumer<ToolExecution> listener) {
        return execute(currentStep, toolRequests, chatContext, listener, null, false);
    }

    /**
     * 顺序执行本轮模型请求的全部工具，并在每个工具前后触发中间件回调
     *
     * @param currentStep 当前步骤
     * @param toolRequests 模型返回的工具调用请求
     * @param chatContext 对话上下文
     * @param listener 每个工具执行完成后的回调，可为null
     * @param middlewareManager 中间件管理器，可为null
     * @return 工具执行结果消息列表
     */
    public List<ToolExecutionResultMessage> execute(
            int currentStep,
            List<ToolExecutionRequest> toolRequests,
            AgentChatContext chatContext,
            Consumer<ToolExecution> listener,
            AgentMiddlewareManager middlewareManager) {
        return execute(currentStep, toolRequests, chatContext, listener, middlewareManager, false);
    }

    /**
     * 执行本轮模型请求的全部工具：当开启并发执行且工具数量大于 1 时并发执行，否则顺序执行。 并发执行时会触发 beforeConcurrentToolExecution /
     * onConcurrentToolExecutionComplete / afterConcurrentToolExecution 回调；顺序执行时触发
     * beforeToolExecution / afterToolExecution。
     *
     * @param currentStep 当前步骤
     * @param toolRequests 模型返回的工具调用请求
     * @param chatContext 对话上下文
     * @param listener 每个工具执行完成后的回调，可为null
     * @param middlewareManager 中间件管理器，可为null
     * @param enableConcurrent 是否启用并发执行
     * @return 工具执行结果消息列表
     */
    public List<ToolExecutionResultMessage> execute(
            int currentStep,
            List<ToolExecutionRequest> toolRequests,
            AgentChatContext chatContext,
            Consumer<ToolExecution> listener,
            AgentMiddlewareManager middlewareManager,
            boolean enableConcurrent) {
        if (enableConcurrent
                && toolRequests != null
                && toolRequests.size() > 1
                && chatContext != null) {
            return executeConcurrently(
                    currentStep, toolRequests, chatContext, listener, middlewareManager);
        }
        return executeSequentially(
                currentStep, toolRequests, chatContext, listener, middlewareManager);
    }

    private List<ToolExecutionResultMessage> executeSequentially(
            int currentStep,
            List<ToolExecutionRequest> toolRequests,
            AgentChatContext chatContext,
            Consumer<ToolExecution> listener,
            AgentMiddlewareManager middlewareManager) {
        List<ToolExecutionResultMessage> toolMessages = new ArrayList<ToolExecutionResultMessage>();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return toolMessages;
        }
        Map<String, ToolExecutor> executorMap = toolService.toolExecutors();

        for (ToolExecutionRequest toolRequest : toolRequests) {
            // 触发工具执行前的中间件
            if (middlewareManager != null) {
                middlewareManager.triggerBeforeToolExecution(toolRequest, chatContext);
            }

            LocalDateTime startTime = LocalDateTime.now();
            ToolExecutionResult result =
                    executeTool(executorMap, toolRequest, chatContext, middlewareManager);
            LocalDateTime finishTime = LocalDateTime.now();

            String text = result.text();
            if (!result.isError() && middlewareManager != null) {
                // 触发工具执行后的中间件，改写后的结果才会写回记忆
                text = middlewareManager.triggerAfterToolExecution(toolRequest, text, chatContext);
            }
            if (text == null || text.trim().isEmpty()) {
                text = EMPTY_RESULT_TEXT;
            }

            ToolExecutionResultMessage toolMessage =
                    ToolExecutionResultMessage.builder()
                            .id(toolRequest.id())
                            .toolName(toolRequest.name())
                            .text(text)
                            .isError(result.isError())
                            .build();
            chatContext.getChatMemory().add(toolMessage);
            toolMessages.add(toolMessage);

            if (listener != null) {
                listener.accept(
                        ToolExecution.builder()
                                .request(toolRequest)
                                .result(
                                        ToolExecutionResult.builder()
                                                .isError(result.isError())
                                                .text(text)
                                                .build())
                                .startTime(startTime)
                                .finishTime(finishTime)
                                .memoryId(chatContext.getMemoryId())
                                .build());
            }
        }
        return toolMessages;
    }

    private ToolExecutionResult executeTool(
            Map<String, ToolExecutor> executorMap,
            ToolExecutionRequest toolRequest,
            AgentChatContext chatContext,
            AgentMiddlewareManager middlewareManager) {
        ToolExecutor toolExecutor = executorMap.get(toolRequest.name());
        if (toolExecutor == null) {
            return ToolExecutionResult.failure("未找到工具执行器: " + toolRequest.name(), null);
        }
        try {
            return toolExecutor.executeWithResult(toolRequest, chatContext.getMemoryId());
        } catch (Exception e) {
            // 触发工具执行异常的中间件
            if (middlewareManager != null) {
                middlewareManager.triggerOnToolExecutionError(toolRequest, e, chatContext);
            }
            return ToolExecutionResult.failure("工具执行出错: " + e.getMessage(), e);
        }
    }

    /** 并发执行本轮的全部工具：使用线程池并行执行，触发并发工具执行相关中间件回调， 最终按请求顺序把结果写回记忆。 */
    private List<ToolExecutionResultMessage> executeConcurrently(
            int currentStep,
            List<ToolExecutionRequest> toolRequests,
            AgentChatContext chatContext,
            Consumer<ToolExecution> listener,
            AgentMiddlewareManager middlewareManager) {
        List<ToolExecutionResultMessage> toolMessages = new ArrayList<ToolExecutionResultMessage>();
        Map<String, ToolExecutor> executorMap = toolService.toolExecutors();

        // 并发批次开始前的中间件回调
        if (middlewareManager != null) {
            middlewareManager.triggerBeforeConcurrentToolExecution(toolRequests, chatContext);
        }

        int threads = Math.min(toolRequests.size(), MAX_CONCURRENT_TOOLS);
        ExecutorService pool =
                Executors.newFixedThreadPool(
                        threads,
                        new ThreadFactory() {
                            private final AtomicInteger seq = new AtomicInteger();

                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "agentforge-tool-" + seq.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            }
                        });

        List<ToolTaskOutcome> outcomes = new ArrayList<ToolTaskOutcome>();
        try {
            List<Future<ToolTaskOutcome>> futures = new ArrayList<Future<ToolTaskOutcome>>();
            for (final ToolExecutionRequest toolRequest : toolRequests) {
                futures.add(
                        pool.submit(
                                new Callable<ToolTaskOutcome>() {
                                    @Override
                                    public ToolTaskOutcome call() {
                                        LocalDateTime start = LocalDateTime.now();
                                        ToolExecutionResult result =
                                                executeTool(
                                                        executorMap,
                                                        toolRequest,
                                                        chatContext,
                                                        middlewareManager);
                                        LocalDateTime finish = LocalDateTime.now();
                                        if (middlewareManager != null) {
                                            Throwable error =
                                                    result.isError()
                                                                    && result.result()
                                                                            instanceof Throwable
                                                            ? (Throwable) result.result()
                                                            : null;
                                            middlewareManager
                                                    .triggerConcurrentToolExecutionComplete(
                                                            toolRequest,
                                                            result.isError() ? null : result.text(),
                                                            error,
                                                            chatContext);
                                        }
                                        return new ToolTaskOutcome(
                                                toolRequest, result, start, finish);
                                    }
                                }));
            }

            for (Future<ToolTaskOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (Exception e) {
                    // executeTool 内部已兜底，这里仅防御性处理线程池异常
                    outcomes.add(null);
                }
            }

            List<String> rawResults = new ArrayList<String>();
            for (ToolTaskOutcome outcome : outcomes) {
                String text = outcome == null ? null : outcome.result.text();
                if (text == null || text.trim().isEmpty()) {
                    text = EMPTY_RESULT_TEXT;
                }
                rawResults.add(text);
            }

            List<String> processedResults = rawResults;
            if (middlewareManager != null) {
                List<String> processed =
                        middlewareManager.triggerAfterConcurrentToolExecution(
                                toolRequests, rawResults, chatContext);
                if (processed != null && processed.size() == toolRequests.size()) {
                    processedResults = processed;
                }
            }

            for (int i = 0; i < toolRequests.size(); i++) {
                ToolExecutionRequest toolRequest = toolRequests.get(i);
                ToolTaskOutcome outcome = outcomes.get(i);
                boolean isError = outcome != null && outcome.result.isError();

                String text = processedResults.get(i);
                if (text == null || text.trim().isEmpty()) {
                    text = EMPTY_RESULT_TEXT;
                }

                ToolExecutionResultMessage toolMessage =
                        ToolExecutionResultMessage.builder()
                                .id(toolRequest.id())
                                .toolName(toolRequest.name())
                                .text(text)
                                .isError(isError)
                                .build();
                chatContext.getChatMemory().add(toolMessage);
                toolMessages.add(toolMessage);

                if (listener != null && outcome != null) {
                    listener.accept(
                            ToolExecution.builder()
                                    .request(toolRequest)
                                    .result(
                                            ToolExecutionResult.builder()
                                                    .isError(isError)
                                                    .text(text)
                                                    .build())
                                    .startTime(outcome.startTime)
                                    .finishTime(outcome.finishTime)
                                    .memoryId(chatContext.getMemoryId())
                                    .build());
                }
            }
        } finally {
            pool.shutdown();
        }
        return toolMessages;
    }

    /** 并发批次中单个工具的执行结果与耗时 */
    private static final class ToolTaskOutcome {
        private final ToolExecutionRequest request;
        private final ToolExecutionResult result;
        private final LocalDateTime startTime;
        private final LocalDateTime finishTime;

        private ToolTaskOutcome(
                ToolExecutionRequest request,
                ToolExecutionResult result,
                LocalDateTime startTime,
                LocalDateTime finishTime) {
            this.request = request;
            this.result = result;
            this.startTime = startTime;
            this.finishTime = finishTime;
        }
    }
}
