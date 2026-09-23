package com.changlu.agentforge.ai.agent;

import com.changlu.agentforge.ai.agent.domain.AgentRequest;
import com.changlu.agentforge.ai.agent.step.ChatResult;
import com.changlu.agentforge.ai.agent.stream.TokenStream;

/**
 * @description IAgent agent接口实现
 * @author changlu
 * @date 2026/9/16
 */
public interface IAgent {

    /**
     * 任务运行
     *
     * @param request AgentRequest
     * @return ChatResult
     */
    ChatResult run(AgentRequest request);

    /**
     * 任务流式执行
     *
     * @param request AgentRequest
     * @return TokenStream
     */
    TokenStream runStream(AgentRequest request);

    /**
     * 取消正在执行的任务
     *
     * @param memoryId 会话ID，用于标识要取消的任务
     * @return boolean 是否成功取消
     */
    boolean cancel(Object memoryId);

    /**
     * 清理会话上下文，释放资源
     *
     * @param memoryId 会话ID
     * @return boolean 是否清理成功
     */
    boolean clearContext(Object memoryId);
}
