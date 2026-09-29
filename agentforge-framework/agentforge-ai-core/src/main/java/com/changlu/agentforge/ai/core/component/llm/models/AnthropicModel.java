package com.changlu.agentforge.ai.core.component.llm.models;

import com.changlu.agentforge.ai.core.component.llm.config.LlmBasicConfig;
import com.changlu.agentforge.ai.core.component.llm.constant.LlmConstant;
import com.changlu.agentforge.llm.anthropic.AnthropicChatModel;
import com.changlu.agentforge.llm.anthropic.AnthropicStreamingChatModel;
import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.StreamingChatModel;

import java.util.Properties;

/**
 * @description Anthropic Messages 协议模型构建
 * @author changlu
 * @date 2026/9/16
 */
public class AnthropicModel extends BaseModel {

    @Override
    public ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
        String url = llmBasicConfig.getUrl();
        String modelName = llmBasicConfig.getModelName();
        String apiKey = llmBasicConfig.getApiKey();
        Properties props = llmBasicConfig.getProps();

        AnthropicChatModel.Builder builder =
                AnthropicChatModel.builder().baseUrl(url).modelName(modelName).apiKey(apiKey);

        // 通用参数配置
        configureCommonParams(builder, props);

        return builder.build();
    }

    @Override
    public StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
        String url = llmBasicConfig.getUrl();
        String modelName = llmBasicConfig.getModelName();
        String apiKey = llmBasicConfig.getApiKey();
        Properties props = llmBasicConfig.getProps();

        AnthropicStreamingChatModel.Builder builder =
                AnthropicStreamingChatModel.builder()
                        .baseUrl(url)
                        .modelName(modelName)
                        .apiKey(apiKey);

        // 通用参数配置
        configureCommonParams(builder, props);

        return builder.build();
    }

    // 通用参数配置方法
    private void configureCommonParams(AnthropicChatModel.Builder builder, Properties props) {
        if (props == null) {
            return;
        }

        String timeout = props.getProperty(LlmConstant.TIMEOUT);
        if (isNotEmpty(timeout)) {
            int timeoutMillis = (int) (Long.parseLong(timeout.trim()) * 1000L);
            builder.connectTimeoutMillis(timeoutMillis);
            builder.readTimeoutMillis(timeoutMillis);
        }

        String temperature = props.getProperty(LlmConstant.TEMPERATURE);
        if (isNotEmpty(temperature)) {
            builder.temperature(Double.parseDouble(temperature));
        }

        String topP = props.getProperty(LlmConstant.TOP_P);
        if (isNotEmpty(topP)) {
            builder.topP(Double.parseDouble(topP));
        }

        String maxTokens = props.getProperty(LlmConstant.MAX_TOKENS);
        if (isNotEmpty(maxTokens)) {
            builder.maxTokens(Integer.parseInt(maxTokens));
        }
    }

    // 重载方法用于 StreamingChatModel
    private void configureCommonParams(
            AnthropicStreamingChatModel.Builder builder, Properties props) {
        if (props == null) {
            return;
        }

        String timeout = props.getProperty(LlmConstant.TIMEOUT);
        if (isNotEmpty(timeout)) {
            int timeoutMillis = (int) (Long.parseLong(timeout.trim()) * 1000L);
            builder.connectTimeoutMillis(timeoutMillis);
            builder.readTimeoutMillis(timeoutMillis);
        }

        String temperature = props.getProperty(LlmConstant.TEMPERATURE);
        if (isNotEmpty(temperature)) {
            builder.temperature(Double.parseDouble(temperature));
        }

        String topP = props.getProperty(LlmConstant.TOP_P);
        if (isNotEmpty(topP)) {
            builder.topP(Double.parseDouble(topP));
        }

        String maxTokens = props.getProperty(LlmConstant.MAX_TOKENS);
        if (isNotEmpty(maxTokens)) {
            builder.maxTokens(Integer.parseInt(maxTokens));
        }
    }
}
