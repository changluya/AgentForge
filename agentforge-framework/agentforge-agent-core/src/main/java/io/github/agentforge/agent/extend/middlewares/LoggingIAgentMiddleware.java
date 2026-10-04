package io.github.agentforge.agent.extend.middlewares;

import io.github.agentforge.agent.component.middleware.IAgentMiddleware;
import io.github.agentforge.agent.domain.AgentChatContext;
import io.github.agentforge.agent.domain.StopResult;
import io.github.agentforge.agent.step.StopResultState;
import io.github.agentforge.model.chat.message.ToolExecutionRequest;
import io.github.agentforge.model.chat.request.ChatRequest;
import io.github.agentforge.model.chat.response.ChatResponse;
import io.github.agentforge.model.chat.response.FinishReason;
import io.github.agentforge.model.chat.response.TokenUsage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @description 日志打印Agent中间件：打印Agent执行过程中的关键信息，对超长内容做截断， 并统计每轮会话的模型耗时、工具耗时与token使用量
 * @author changlu
 * @date 2026/9/16
 */
public class LoggingIAgentMiddleware implements IAgentMiddleware {

    private static final Logger logger = Logger.getLogger(LoggingIAgentMiddleware.class.getName());

    // 截断长度限制
    private static final int MAX_CONTENT_LENGTH = 500;

    private static final int MAX_TOOL_RESULT_LENGTH = 1000;

    // 会话统计信息，按memoryId隔离
    private final Map<Object, SessionStats> sessionStatsMap =
            new ConcurrentHashMap<Object, SessionStats>();

    @Override
    public void onInitComplete(AgentChatContext chatContext) {
        stats(chatContext).sessionStartTime = System.currentTimeMillis();
        logger.info(
                "[会话开始] memoryId: "
                        + memoryId(chatContext)
                        + ", question: "
                        + truncate(question(chatContext), MAX_CONTENT_LENGTH));
    }

    @Override
    public void onAiCallRetry(
            int currentStep,
            ChatRequest chatRequest,
            AgentChatContext chatContext,
            int retryCount,
            int maxRetries,
            long delayMs,
            Exception lastException) {
        logger.warning(
                "[AI调用重试] 步骤: "
                        + currentStep
                        + ", 重试次数: "
                        + retryCount
                        + "/"
                        + maxRetries
                        + ", 等待: "
                        + delayMs
                        + "ms"
                        + ", 异常: "
                        + (lastException != null
                                ? lastException.getClass().getSimpleName()
                                : "null")
                        + ", memoryId: "
                        + memoryId(chatContext)
                        + ", error: "
                        + (lastException != null
                                ? truncate(lastException.getMessage(), 200)
                                : "null"));
    }

    @Override
    public void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        logger.info(
                "[工具执行开始] 工具: "
                        + toolRequest.name()
                        + ", 参数: "
                        + truncate(toolRequest.arguments(), MAX_CONTENT_LENGTH)
                        + ", memoryId: "
                        + memoryId(chatContext));
        stats(chatContext).toolStartMap.put(toolKey(toolRequest), System.currentTimeMillis());
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        Long start = stats(chatContext).toolStartMap.remove(toolKey(toolRequest));
        long cost = start == null ? 0L : System.currentTimeMillis() - start;
        stats(chatContext).totalToolExecutionTime.addAndGet(cost);

        logger.info(
                "[工具执行完成] 工具: "
                        + toolRequest.name()
                        + ", 耗时: "
                        + cost
                        + "ms"
                        + ", 结果: "
                        + truncate(toolResult, MAX_TOOL_RESULT_LENGTH)
                        + ", memoryId: "
                        + memoryId(chatContext));
        // 只观察不改写
        return toolResult;
    }

    @Override
    public void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {
        stats(chatContext).toolStartMap.remove(toolKey(toolRequest));
        logger.log(
                Level.WARNING,
                "[工具执行异常] 工具: " + toolRequest.name() + ", memoryId: " + memoryId(chatContext),
                error);
    }

    @Override
    public ChatRequest beforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        if (chatRequest != null) {
            stats(chatContext).modelStartMap.put(modelKey(currentStep), System.currentTimeMillis());
            logger.info(
                    "[模型调用开始] 步骤: "
                            + currentStep
                            + ", 消息数: "
                            + chatRequest.messages().size()
                            + ", memoryId: "
                            + memoryId(chatContext));
        }
        return chatRequest;
    }

    @Override
    public ChatResponse afterModelCall(
            int currentStep,
            ChatRequest chatRequest,
            ChatResponse chatResponse,
            AgentChatContext chatContext) {
        if (chatResponse == null) {
            return null;
        }
        SessionStats stats = stats(chatContext);
        Long start = stats.modelStartMap.remove(modelKey(currentStep));
        long cost = start == null ? 0L : System.currentTimeMillis() - start;
        stats.totalModelCallTime.addAndGet(cost);

        StringBuilder builder = new StringBuilder();
        builder.append("[模型调用完成] 步骤: ")
                .append(currentStep)
                .append(", 耗时: ")
                .append(cost)
                .append("ms");
        TokenUsage tokenUsage = chatResponse.tokenUsage();
        if (tokenUsage != null) {
            stats.totalInputTokens.addAndGet((int) tokenUsage.inputTokens());
            stats.totalOutputTokens.addAndGet((int) tokenUsage.outputTokens());
            stats.totalTokens.addAndGet((int) tokenUsage.totalTokens());
            builder.append(", token: ")
                    .append(tokenUsage.inputTokens())
                    .append("/")
                    .append(tokenUsage.outputTokens())
                    .append("/")
                    .append(tokenUsage.totalTokens());
        }
        FinishReason finishReason = chatResponse.finishReason();
        if (finishReason != null) {
            builder.append(", finishReason: ").append(finishReason);
        }
        if (chatResponse.aiMessage() != null) {
            builder.append(", 工具调用数: ")
                    .append(chatResponse.aiMessage().toolExecutionRequests().size());
            builder.append(", 文本: ")
                    .append(truncate(chatResponse.aiMessage().text(), MAX_CONTENT_LENGTH));
        }
        builder.append(", memoryId: ").append(memoryId(chatContext));
        logger.info(builder.toString());

        // 只观察不改写
        return chatResponse;
    }

    @Override
    public void onModelCallError(
            int currentStep,
            ChatRequest chatRequest,
            Throwable error,
            AgentChatContext chatContext) {
        stats(chatContext).modelStartMap.remove(modelKey(currentStep));
        logger.log(
                Level.WARNING,
                "[模型调用异常] 步骤: " + currentStep + ", memoryId: " + memoryId(chatContext),
                error);
    }

    @Override
    public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        SessionStats stats = stats(chatContext);
        StopResultState state = stopResult == null ? null : stopResult.getStopResultState();
        logger.info(
                "[会话结束] 步骤: "
                        + currentStep
                        + ", 状态: "
                        + (state == null ? "null" : state.getDescription())
                        + ", 总耗时: "
                        + (System.currentTimeMillis() - stats.sessionStartTime)
                        + "ms"
                        + ", 模型耗时: "
                        + stats.totalModelCallTime.get()
                        + "ms"
                        + ", 工具耗时: "
                        + stats.totalToolExecutionTime.get()
                        + "ms"
                        + ", token(输入/输出/总计): "
                        + stats.totalInputTokens.get()
                        + "/"
                        + stats.totalOutputTokens.get()
                        + "/"
                        + stats.totalTokens.get()
                        + ", runRes: "
                        + truncate(
                                stopResult == null ? null : stopResult.getRunRes(),
                                MAX_CONTENT_LENGTH)
                        + ", memoryId: "
                        + memoryId(chatContext));
        // 会话结束后释放统计，避免长生命周期Agent实例累积
        sessionStatsMap.remove(memoryId(chatContext));
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
        SessionStats stats = stats(chatContext);
        logger.log(
                Level.WARNING,
                "[会话异常结束] 步骤: "
                        + currentStep
                        + ", 模型耗时: "
                        + stats.totalModelCallTime.get()
                        + "ms"
                        + ", 工具耗时: "
                        + stats.totalToolExecutionTime.get()
                        + "ms"
                        + ", memoryId: "
                        + memoryId(chatContext),
                error);
        sessionStatsMap.remove(memoryId(chatContext));
    }

    private SessionStats stats(AgentChatContext chatContext) {
        Object memoryId = memoryId(chatContext);
        SessionStats stats = sessionStatsMap.get(memoryId);
        if (stats == null) {
            stats = new SessionStats();
            SessionStats previous = sessionStatsMap.putIfAbsent(memoryId, stats);
            if (previous != null) {
                stats = previous;
            }
        }
        return stats;
    }

    private static Object memoryId(AgentChatContext chatContext) {
        return chatContext == null ? null : chatContext.getMemoryId();
    }

    private static String question(AgentChatContext chatContext) {
        return chatContext == null ? null : chatContext.getQuestion();
    }

    private static String toolKey(ToolExecutionRequest toolRequest) {
        return toolRequest.id() == null ? toolRequest.name() : toolRequest.id();
    }

    private static String modelKey(int currentStep) {
        return String.valueOf(currentStep);
    }

    // 超长内容截断，避免日志刷屏
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated:" + text.length() + ")";
    }

    /** 单个会话的统计信息 */
    private static class SessionStats {
        long sessionStartTime;

        final AtomicLong totalModelCallTime = new AtomicLong(0);

        final AtomicLong totalToolExecutionTime = new AtomicLong(0);

        final AtomicInteger totalInputTokens = new AtomicInteger(0);

        final AtomicInteger totalOutputTokens = new AtomicInteger(0);

        final AtomicInteger totalTokens = new AtomicInteger(0);

        final Map<String, Long> toolStartMap = new ConcurrentHashMap<String, Long>();

        final Map<String, Long> modelStartMap = new ConcurrentHashMap<String, Long>();
    }
}
