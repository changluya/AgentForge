package com.changlu.agentforge.agent.component.middleware;

import com.changlu.agentforge.agent.domain.AgentChatContext;
import com.changlu.agentforge.agent.domain.StopResult;
import com.changlu.agentforge.agent.step.StepResult;
import com.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.model.chat.request.ChatRequest;
import com.changlu.agentforge.model.chat.response.ChatResponse;

/**
 * @description Agent中间件接口，用于在Agent执行的不同阶段插入自定义逻辑，参考LangChain的AgentMiddleware设计
 * @author changlu
 * @date 2026/9/16
 */
public interface IAgentMiddleware {

    /**
     * 在Agent初始化完成时调用，此时AgentChatContext已经构建完毕。 用于执行中间件的初始化操作，例如注册监听器、加载配置、记录会话开始信息
     *
     * @param chatContext 聊天上下文（已完整构建）
     */
    default void onInitComplete(AgentChatContext chatContext) {}

    // ===================== Loop生命周期相关回调 ===================== //

    /**
     * 在单轮loop开始前调用
     *
     * @param currentStep 当前步骤号
     * @param chatContext 聊天上下文
     */
    default void beforeLoop(int currentStep, AgentChatContext chatContext) {}

    /**
     * 在单轮loop结束后调用
     *
     * @param currentStep 当前步骤号
     * @param stepResult 当前轮执行结果
     * @param chatContext 聊天上下文
     */
    default void afterLoop(int currentStep, StepResult stepResult, AgentChatContext chatContext) {}

    /**
     * 在单轮loop执行异常时调用
     *
     * @param currentStep 当前步骤号
     * @param error 当前轮异常
     * @param chatContext 聊天上下文
     */
    default void onLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {}

    // ===================== AI调用重试相关回调 ===================== //

    /**
     * 当AI调用触发重试时调用
     *
     * @param currentStep 当前步骤号
     * @param chatRequest 聊天请求
     * @param chatContext 聊天上下文
     * @param retryCount 当前重试次数（从1开始，1表示第一次重试）
     * @param maxRetries 最大重试次数
     * @param delayMs 本次重试前的退避等待时间
     * @param lastException 上一次尝试的异常
     */
    default void onAiCallRetry(
            int currentStep,
            ChatRequest chatRequest,
            AgentChatContext chatContext,
            int retryCount,
            int maxRetries,
            long delayMs,
            Exception lastException) {}

    // ===================== 工具执行相关回调 ===================== //

    /**
     * 在工具执行之前调用（单个工具）
     *
     * @param toolRequest 工具执行请求
     * @param chatContext 聊天上下文
     */
    default void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {}

    /**
     * 在工具执行之后调用（单个工具），可改写写回记忆的工具结果
     *
     * @param toolRequest 工具执行请求
     * @param toolResult 工具执行结果
     * @param chatContext 聊天上下文
     * @return 处理后的工具执行结果
     */
    default String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        return toolResult;
    }

    /**
     * 在工具执行发生错误时调用
     *
     * @param toolRequest 工具执行请求
     * @param error 执行过程中抛出的异常
     * @param chatContext 聊天上下文
     */
    default void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {}

    // ===================== 模型调用相关回调 ===================== //

    /**
     * 在模型调用之前调用
     *
     * @param currentStep 当前步骤号
     * @param chatRequest 聊天请求
     * @param chatContext 聊天上下文
     * @return 处理后的聊天请求（返回null将阻止调用）
     */
    default ChatRequest beforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        return chatRequest;
    }

    /**
     * 在模型调用之后调用
     *
     * @param currentStep 当前步骤号
     * @param chatRequest 聊天请求
     * @param chatResponse 聊天响应
     * @param chatContext 聊天上下文
     * @return 处理后的聊天响应（返回null将使用原始响应）
     */
    default ChatResponse afterModelCall(
            int currentStep,
            ChatRequest chatRequest,
            ChatResponse chatResponse,
            AgentChatContext chatContext) {
        return chatResponse;
    }

    /**
     * 在模型调用发生错误时调用
     *
     * @param currentStep 当前步骤号
     * @param chatRequest 聊天请求
     * @param error 异常
     * @param chatContext 聊天上下文
     */
    default void onModelCallError(
            int currentStep,
            ChatRequest chatRequest,
            Throwable error,
            AgentChatContext chatContext) {}

    // ===================== Agent停止相关回调 ===================== //

    /**
     * 在Agent停止执行时调用（无论是正常结束、取消还是达到最大步数）
     *
     * @param currentStep 当前步骤号（停止时的步骤）
     * @param stopResult 停止结果（包含状态信息和运行结果）
     * @param chatContext 聊天上下文
     */
    default void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {}

    /**
     * 在Agent因错误而停止时调用
     *
     * @param currentStep 当前步骤号（错误发生时的步骤）
     * @param error 发生的异常
     * @param chatContext 聊天上下文
     */
    default void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {}
}
