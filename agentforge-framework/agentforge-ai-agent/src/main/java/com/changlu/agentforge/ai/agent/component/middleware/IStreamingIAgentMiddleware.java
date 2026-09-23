package com.changlu.agentforge.ai.agent.component.middleware;

import com.changlu.agentforge.ai.agent.domain.AgentChatContext;
import com.changlu.agentforge.ai.agent.stream.PartialThinking;
import com.changlu.agentforge.llm.chat.response.ChatResponse;

/**
 * @description 流式Agent中间件接口，提供对模型流式输出的监听能力
 * 实现此接口的中间件可以接收和处理模型流式输出的各类事件
 * @author changlu
 * @date 2026/9/16
 */
public interface IStreamingIAgentMiddleware extends IAgentMiddleware {

    /**
     * 当接收到模型的流式文本响应片段时调用，每次模型生成一个文本token时会触发此方法
     *
     * @param currentStep     当前步骤号
     * @param partialResponse 流式响应的文本片段
     * @param chatContext     聊天上下文
     */
    default void onPartialResponse(int currentStep, String partialResponse, AgentChatContext chatContext) {}

    /**
     * 当接收到模型的流式思考/推理内容片段时调用，每次模型生成一个思考token时会触发此方法
     *
     * @param currentStep      当前步骤号
     * @param partialThinking  流式思考内容片段
     * @param chatContext      聊天上下文
     */
    default void onPartialThinking(int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {}

    /**
     * 当接收到模型的中间响应时调用
     * 中间响应通常包含工具调用请求，在工具执行后、进入下一轮思考前触发
     *
     * @param currentStep           当前步骤号
     * @param intermediateResponse  中间响应（通常包含ToolExecutionRequest）
     * @param chatContext           聊天上下文
     */
    default void onIntermediateResponse(int currentStep, ChatResponse intermediateResponse,
                                        AgentChatContext chatContext) {}

    /**
     * 标记当前中间件是否处理流式事件。默认返回true，对于只关心非流式事件的中间件可重写返回false以提高性能
     *
     * @return 是否处理流式事件
     */
    default boolean handlesStreamingEvents() {
        return true;
    }
}
