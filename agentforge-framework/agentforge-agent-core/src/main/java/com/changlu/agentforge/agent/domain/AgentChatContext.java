package com.changlu.agentforge.agent.domain;

import com.changlu.agentforge.agent.memory.ChatMemory;
import com.changlu.agentforge.model.chat.ChatModel;

import java.util.HashMap;
import java.util.Map;

/**
 * @description 单次对话上下文：一次运行的请求 + 会话记忆 + 模型，extensions由本对象自行维护
 * @author changlu
 * @date 2026/9/16
 */
public class AgentChatContext {

    // 本次运行的请求参数
    private AgentRequest request;
    private ChatMemory chatMemory;
    // 模型
    private ChatModel chatModel;
    // 扩展业务字段，运行期由Agent/Tool读写
    private Map<String, Object> extensions;

    public AgentChatContext() {}

    private AgentChatContext(Builder builder) {
        this.request = builder.request;
        this.chatMemory = builder.chatMemory;
        this.chatModel = builder.chatModel;
        this.extensions =
                builder.extensions == null ? new HashMap<String, Object>() : builder.extensions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentRequest getRequest() {
        return request;
    }

    public void setRequest(AgentRequest request) {
        this.request = request;
    }

    /** 会话ID，直接取自本次请求 */
    public Object getMemoryId() {
        return request == null ? null : request.getMemoryId();
    }

    /** 用户问题，直接取自本次请求 */
    public String getQuestion() {
        return request == null ? null : request.getQuestion();
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    public void setChatMemory(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public void setChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public Map<String, Object> getExtensions() {
        if (extensions == null) {
            extensions = new HashMap<String, Object>();
        }
        return extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    /** 写入一个运行期业务字段 */
    public AgentChatContext putExtension(String key, Object value) {
        if (key != null) {
            getExtensions().put(key, value);
        }
        return this;
    }

    /** 读取一个运行期业务字段 */
    public Object getExtension(String key) {
        return key == null ? null : getExtensions().get(key);
    }

    public static class Builder {
        private AgentRequest request;
        private ChatMemory chatMemory;
        private ChatModel chatModel;
        private Map<String, Object> extensions;

        private Builder() {}

        public Builder request(AgentRequest request) {
            this.request = request;
            return this;
        }

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder extensions(Map<String, Object> extensions) {
            this.extensions = extensions;
            return this;
        }

        public AgentChatContext build() {
            return new AgentChatContext(this);
        }
    }
}
