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
import java.util.function.Consumer;

/**
 * @description act阶段工具批次的执行器：查找执行器、执行工具、把结果写回ChatMemory，供流式与非流式共用
 * @author changlu
 * @date 2026/9/16
 */
public class AgentToolExecutor {

    private static final String EMPTY_RESULT_TEXT = "tool exec success, but no result";

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
        return execute(currentStep, toolRequests, chatContext, listener, null);
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
}
