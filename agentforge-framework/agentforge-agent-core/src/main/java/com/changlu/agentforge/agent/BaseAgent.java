package com.changlu.agentforge.agent;

import com.changlu.agentforge.agent.domain.AgentChatContext;
import com.changlu.agentforge.agent.domain.AgentRequest;
import com.changlu.agentforge.agent.exception.AgentException;
import com.changlu.agentforge.agent.memory.ChatMemory;
import com.changlu.agentforge.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.model.chat.ChatModel;
import com.changlu.agentforge.model.chat.message.ChatMessage;
import com.changlu.agentforge.model.chat.message.UserMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @description 抽象 计划 -> 思考 + 行动 执行Agent
 * @author changlu
 * @date 2026/9/16
 */
public abstract class BaseAgent implements IAgent {

    // 存储每个会话的取消标志
    protected final Map<Object, AtomicBoolean> cancelFlags =
            new ConcurrentHashMap<Object, AtomicBoolean>();

    protected abstract ChatModel getChatModel();

    // 包含了ChatMemory
    protected abstract ChatMemoryProvider getChatMemoryProvider();

    /** 初始化本次运行的上下文：绑定会话记忆 + 写入本轮用户消息 */
    protected AgentChatContext init(AgentRequest request) {
        AgentChatContext chatContext = initParams(request);

        initMessages(chatContext);

        return chatContext;
    }

    private AgentChatContext initParams(AgentRequest request) {
        ChatMemory chatMemory = getChatMemoryProvider().get(request.getMemoryId());

        return AgentChatContext.builder()
                .request(request)
                .chatModel(getChatModel())
                .chatMemory(chatMemory)
                .build();
    }

    /** 初始化消息 */
    protected void initMessages(AgentChatContext chatContext) {
        addMessage(chatContext, buildUserMessage(chatContext));
    }

    /**
     * 构建用户message
     *
     * @param chatContext AgentChatContext
     * @return UserMessage
     */
    protected UserMessage buildUserMessage(AgentChatContext chatContext) {
        String question = chatContext.getQuestion();
        return UserMessage.from(
                question == null || question.trim().isEmpty() ? "请结合上下文回答。" : question.trim());
    }

    protected void addMessage(AgentChatContext chatContext, ChatMessage newMessages) {
        ChatMemory chatMemory = chatContext.getChatMemory();
        chatMemory.add(newMessages);
    }

    /**
     * 检查指定会话是否已被取消
     *
     * @param memoryId 会话ID
     * @return true 如果已取消
     */
    protected boolean isCancelled(Object memoryId) {
        AtomicBoolean flag = cancelFlags.get(memoryId);
        return flag != null && flag.get();
    }

    /**
     * 清理取消标志
     *
     * @param memoryId 会话ID
     */
    protected void clearCancelFlag(Object memoryId) {
        cancelFlags.remove(memoryId);
    }

    /**
     * Agent 实例会被缓存复用，同一个 memoryId 在上一轮被 cancel 后， cancelFlags 里的取消标记可能残留到下一轮正式启动前。
     * 这里在“新一轮启动入口”做一次清理，前提是上层已保证同一 memoryId 不会并发运行。
     */
    protected void prepareForNewRun(Object memoryId) {
        if (memoryId == null) {
            return;
        }
        clearCancelFlag(memoryId);
    }

    @Override
    public boolean cancel(Object memoryId) {
        // 任务还未开始时同样先落标记，运行中的循环会在下一步think/act前检查到取消信号
        AtomicBoolean flag = cancelFlags.get(memoryId);
        if (flag == null) {
            flag = new AtomicBoolean(false);
            AtomicBoolean previous = cancelFlags.putIfAbsent(memoryId, flag);
            if (previous != null) {
                flag = previous;
            }
        }
        return !flag.getAndSet(true);
    }

    @Override
    public boolean clearContext(Object memoryId) {
        ChatMemoryProvider chatMemoryProvider = getChatMemoryProvider();
        if (chatMemoryProvider == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
        ChatMemory chatMemory = chatMemoryProvider.get(memoryId);
        chatMemory.clear();
        clearCancelFlag(memoryId);
        return true;
    }
}
