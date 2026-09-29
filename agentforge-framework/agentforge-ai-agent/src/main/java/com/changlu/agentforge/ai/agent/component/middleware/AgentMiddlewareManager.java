package com.changlu.agentforge.ai.agent.component.middleware;

import com.changlu.agentforge.ai.agent.domain.AgentChatContext;
import com.changlu.agentforge.ai.agent.domain.StopResult;
import com.changlu.agentforge.ai.agent.step.StepResult;
import com.changlu.agentforge.ai.agent.stream.PartialThinking;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @description Agent中间件管理器，负责多个中间件的注册与按注册顺序执行，同时负责流式事件的分发
 * @author changlu
 * @date 2026/9/16
 */
public class AgentMiddlewareManager {

    private static final Logger log = Logger.getLogger(AgentMiddlewareManager.class.getName());

    // 中间件列表，使用CopyOnWriteArrayList保证并发注册时的读安全
    private final List<IAgentMiddleware> middlewares;
    private final List<IAgentMiddleware> sortedMiddlewares;
    private final List<IStreamingIAgentMiddleware> streamingMiddlewares;

    public AgentMiddlewareManager() {
        this.middlewares = new CopyOnWriteArrayList<IAgentMiddleware>();
        this.sortedMiddlewares = new ArrayList<IAgentMiddleware>();
        this.streamingMiddlewares = new ArrayList<IStreamingIAgentMiddleware>();
    }

    /**
     * 注册中间件
     *
     * @param middleware 中间件实例
     * @return 当前管理器实例（支持链式调用）
     */
    public AgentMiddlewareManager register(IAgentMiddleware middleware) {
        if (middleware != null) {
            middlewares.add(middleware);
            sortMiddlewares();
            log.fine("注册中间件: " + name(middleware));
        }
        return this;
    }

    /**
     * 批量注册中间件
     *
     * @param middlewares 中间件列表
     * @return 当前管理器实例
     */
    public AgentMiddlewareManager registerAll(List<IAgentMiddleware> middlewares) {
        if (middlewares != null) {
            for (IAgentMiddleware middleware : middlewares) {
                if (middleware != null) {
                    this.middlewares.add(middleware);
                }
            }
            sortMiddlewares();
            log.fine("批量注册 " + middlewares.size() + " 个中间件");
        }
        return this;
    }

    /**
     * 移除中间件
     *
     * @param middleware 中间件实例
     */
    public void unregister(IAgentMiddleware middleware) {
        if (middleware != null) {
            middlewares.remove(middleware);
            sortMiddlewares();
            log.fine("移除中间件: " + name(middleware));
        }
    }

    /** 清空所有中间件 */
    public void clear() {
        middlewares.clear();
        sortedMiddlewares.clear();
        streamingMiddlewares.clear();
        log.fine("清空所有中间件");
    }

    /**
     * 获取所有中间件（只读）
     *
     * @return 中间件列表的不可修改视图
     */
    public List<IAgentMiddleware> getMiddlewares() {
        return Collections.unmodifiableList(sortedMiddlewares);
    }

    /**
     * 获取所有流式中间件（只读）
     *
     * @return 流式中间件列表的不可修改视图
     */
    public List<IStreamingIAgentMiddleware> getStreamingMiddlewares() {
        return Collections.unmodifiableList(streamingMiddlewares);
    }

    /** 按注册顺序整理中间件，并挑出需要接收流式事件的中间件 */
    private void sortMiddlewares() {
        sortedMiddlewares.clear();
        streamingMiddlewares.clear();

        for (IAgentMiddleware middleware : middlewares) {
            sortedMiddlewares.add(middleware);
            if (middleware instanceof IStreamingIAgentMiddleware) {
                IStreamingIAgentMiddleware streamingMiddleware =
                        (IStreamingIAgentMiddleware) middleware;
                if (streamingMiddleware.handlesStreamingEvents()) {
                    streamingMiddlewares.add(streamingMiddleware);
                }
            }
        }
    }

    // ===================== 初始化相关方法 ===================== //

    /**
     * 触发所有中间件的初始化完成回调，在所有中间件注册完成后、Agent开始执行前调用
     *
     * @param chatContext 聊天上下文（已完整构建）
     */
    public void triggerOnInitComplete(AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.info("触发中间件初始化完成回调，共 " + sortedMiddlewares.size() + " 个中间件");
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onInitComplete(chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onInitComplete 时出错", e);
                // 单个中间件失败不中断主流程
            }
        }
    }

    /** 触发单轮loop开始回调 */
    public void triggerBeforeLoop(int currentStep, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发loop开始中间件，步骤: " + currentStep);
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.beforeLoop(currentStep, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 beforeLoop 时出错", e);
            }
        }
    }

    /** 触发单轮loop结束回调 */
    public void triggerAfterLoop(
            int currentStep, StepResult stepResult, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine(
                "触发loop结束中间件，步骤: "
                        + currentStep
                        + ", 状态: "
                        + (stepResult != null ? stepResult.getState() : "null"));
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.afterLoop(currentStep, stepResult, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 afterLoop 时出错", e);
            }
        }
    }

    /** 触发单轮loop异常回调 */
    public void triggerOnLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发loop异常中间件，步骤: " + currentStep);
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onLoopError(currentStep, error, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onLoopError 时出错", e);
            }
        }
    }

    // ===================== 流式事件相关方法 ===================== //

    /**
     * 触发流式响应片段事件
     *
     * @param currentStep 当前步骤号
     * @param partialResponse 流式响应片段
     * @param chatContext 聊天上下文
     */
    public void triggerOnPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        if (streamingMiddlewares.isEmpty()) {
            return;
        }

        for (IStreamingIAgentMiddleware middleware : streamingMiddlewares) {
            try {
                middleware.onPartialResponse(currentStep, partialResponse, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onPartialResponse 时出错", e);
                // 继续执行其他中间件，不中断流程
            }
        }
    }

    /**
     * 触发流式思考内容片段事件
     *
     * @param currentStep 当前步骤号
     * @param partialThinking 流式思考内容片段
     * @param chatContext 聊天上下文
     */
    public void triggerOnPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        if (streamingMiddlewares.isEmpty()) {
            return;
        }

        for (IStreamingIAgentMiddleware middleware : streamingMiddlewares) {
            try {
                middleware.onPartialThinking(currentStep, partialThinking, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onPartialThinking 时出错", e);
                // 继续执行其他中间件，不中断流程
            }
        }
    }

    /**
     * 触发中间响应事件（该轮模型返回了工具调用请求）
     *
     * @param currentStep 当前步骤号
     * @param intermediateResponse 中间响应（通常包含ToolExecutionRequest）
     * @param chatContext 聊天上下文
     */
    public void triggerOnIntermediateResponse(
            int currentStep, ChatResponse intermediateResponse, AgentChatContext chatContext) {
        if (streamingMiddlewares.isEmpty()) {
            return;
        }

        for (IStreamingIAgentMiddleware middleware : streamingMiddlewares) {
            try {
                middleware.onIntermediateResponse(currentStep, intermediateResponse, chatContext);
            } catch (Exception e) {
                log.log(
                        Level.SEVERE,
                        "中间件 " + name(middleware) + " 执行 onIntermediateResponse 时出错",
                        e);
                // 继续执行其他中间件，不中断流程
            }
        }
    }

    // ===================== 工具执行相关方法 ===================== //

    /** 触发工具执行前的中间件（单个工具） */
    public void triggerBeforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发工具执行前的中间件，工具: " + toolRequest.name());
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.beforeToolExecution(toolRequest, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 beforeToolExecution 时出错", e);
            }
        }
    }

    /** 触发工具执行后的中间件（单个工具），结果会沿中间件链依次传递 */
    public String triggerAfterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return toolResult;
        }

        log.fine("触发工具执行后的中间件，工具: " + toolRequest.name());
        String res = toolResult;
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                res = middleware.afterToolExecution(toolRequest, res, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 afterToolExecution 时出错", e);
                // 继续执行其他中间件，不中断流程
            }
        }
        return res;
    }

    /** 触发工具执行异常回调 */
    public void triggerOnToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onToolExecutionError(toolRequest, error, chatContext);
            } catch (Exception e) {
                log.log(
                        Level.SEVERE,
                        "中间件 " + name(middleware) + " 执行 onToolExecutionError 时出错",
                        e);
            }
        }
    }

    // ===================== 模型调用相关方法 ===================== //

    /**
     * 触发模型调用前的中间件
     *
     * @return 处理后的ChatRequest，如果某个中间件返回null则中断后续调用
     */
    public ChatRequest triggerBeforeModelCall(
            int currentStep, ChatRequest chatRequest, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return chatRequest;
        }

        log.fine("触发模型调用前的中间件，步骤: " + currentStep);
        ChatRequest processedRequest = chatRequest;

        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                processedRequest =
                        middleware.beforeModelCall(currentStep, processedRequest, chatContext);
                if (processedRequest == null) {
                    log.info("中间件 " + name(middleware) + " 中断了模型调用");
                    // 中断调用
                    return null;
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 beforeModelCall 时出错", e);
                // 继续执行其他中间件
            }
        }

        return processedRequest;
    }

    /** 触发模型调用后的中间件 */
    public ChatResponse triggerAfterModelCall(
            int currentStep,
            ChatRequest chatRequest,
            ChatResponse chatResponse,
            AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty() || chatResponse == null) {
            return chatResponse;
        }

        log.fine("触发模型调用后的中间件，步骤: " + currentStep);
        ChatResponse processedResponse = chatResponse;

        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                processedResponse =
                        middleware.afterModelCall(
                                currentStep, chatRequest, processedResponse, chatContext);
                if (processedResponse == null) {
                    log.fine("中间件 " + name(middleware) + " 返回null响应，将使用原始响应");
                    return chatResponse;
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 afterModelCall 时出错", e);
                // 继续执行其他中间件
            }
        }

        return processedResponse;
    }

    /** 触发模型调用错误的中间件 */
    public void triggerOnModelCallError(
            int currentStep,
            ChatRequest chatRequest,
            Throwable error,
            AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发模型调用错误的中间件，步骤: " + currentStep);
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onModelCallError(currentStep, chatRequest, error, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onModelCallError 时出错", e);
            }
        }
    }

    /** 触发AI调用重试的中间件 */
    public void triggerOnAiCallRetry(
            int currentStep,
            ChatRequest chatRequest,
            AgentChatContext chatContext,
            int retryCount,
            int maxRetries,
            long delayMs,
            Exception lastException) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发AI调用重试的中间件，步骤: " + currentStep + ", 重试次数: " + retryCount + "/" + maxRetries);
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onAiCallRetry(
                        currentStep,
                        chatRequest,
                        chatContext,
                        retryCount,
                        maxRetries,
                        delayMs,
                        lastException);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onAiCallRetry 时出错", e);
            }
        }
    }

    // ===================== Agent停止相关方法 ===================== //

    /**
     * 触发Agent正常停止的中间件
     *
     * @param currentStep 当前步骤号
     * @param res 停止结果（由上层构建）
     * @param chatContext 聊天上下文
     */
    public void triggerOnStop(int currentStep, StopResult res, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine(
                "触发Agent停止的中间件，步骤: "
                        + currentStep
                        + ", 状态: "
                        + (res != null ? res.getStopResultState() : "null"));
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onStop(currentStep, res, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onStop 时出错", e);
            }
        }
    }

    /** 触发Agent错误停止的中间件 */
    public void triggerOnStopWithError(
            int currentStep, Throwable error, AgentChatContext chatContext) {
        if (sortedMiddlewares.isEmpty()) {
            return;
        }

        log.fine("触发Agent错误停止的中间件，步骤: " + currentStep);
        for (IAgentMiddleware middleware : sortedMiddlewares) {
            try {
                middleware.onStopWithError(currentStep, error, chatContext);
            } catch (Exception e) {
                log.log(Level.SEVERE, "中间件 " + name(middleware) + " 执行 onStopWithError 时出错", e);
            }
        }
    }

    private static String name(IAgentMiddleware middleware) {
        return middleware.getClass().getSimpleName();
    }
}
