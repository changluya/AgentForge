package com.changlu.agentforge.model.registry.models;

import com.changlu.agentforge.model.chat.ChatModel;
import com.changlu.agentforge.model.chat.StreamingChatModel;
import com.changlu.agentforge.model.registry.config.LlmBasicConfig;

/**
 * @description llm模型构建接口，后续 EmbeddingModel 等能力在这里继续扩展
 * @author changlu
 * @date 2026/9/16
 */
public interface IModel {

    /**
     * 构造同步 ChatModel
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return ChatModel
     */
    ChatModel buildChatModel(LlmBasicConfig llmBasicConfig);

    /**
     * 构造流式 StreamingChatModel
     *
     * @param llmBasicConfig LlmBasicConfig
     * @return StreamingChatModel
     */
    StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig);
}
