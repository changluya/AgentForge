package com.changlu.agentforge.ai.core.component.llm.models;

import com.changlu.agentforge.ai.core.component.llm.config.LlmBasicConfig;
import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.StreamingChatModel;

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
