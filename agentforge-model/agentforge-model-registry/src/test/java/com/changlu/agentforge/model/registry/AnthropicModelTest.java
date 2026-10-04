package com.changlu.agentforge.model.registry;

import com.changlu.agentforge.model.chat.ChatModel;
import com.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.model.registry.config.LlmBasicConfig;
import com.changlu.agentforge.model.registry.constant.LlmConstant;
import com.changlu.agentforge.model.registry.enums.LlmEnum;
import com.changlu.agentforge.model.registry.models.AnthropicModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * @description AnthropicModel 参数映射用例
 * @author changlu
 * @date 2026/9/16
 */
public class AnthropicModelTest {

    @Test
    public void shouldMapConnectionAndCommonProps() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.ANTHROPIC.getCode())
                        .url("https://my-gateway.example.com/anthropic")
                        .modelName("claude-sonnet-4-20250514")
                        .apiKey("sk-ant-test")
                        .prop(LlmConstant.TEMPERATURE, "0.1")
                        .prop(LlmConstant.TOP_P, "0.5")
                        .prop(LlmConstant.MAX_TOKENS, "2048")
                        .prop(LlmConstant.TIMEOUT, "45")
                        .build();

        ChatModel chatModel = new AnthropicModel().buildChatModel(config);

        assertEquals(
                "https://my-gateway.example.com/anthropic",
                ModelFields.string(chatModel, "baseUrl"));
        assertEquals("sk-ant-test", ModelFields.string(chatModel, "apiKey"));
        assertEquals(45_000, ModelFields.integer(chatModel, "connectTimeoutMillis"));
        assertEquals(45_000, ModelFields.integer(chatModel, "readTimeoutMillis"));

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(chatModel);
        assertEquals("claude-sonnet-4-20250514", parameters.modelName());
        assertEquals(Double.valueOf(0.1), parameters.temperature());
        assertEquals(Double.valueOf(0.5), parameters.topP());
        assertEquals(Integer.valueOf(2048), parameters.maxTokens());
    }

    @Test
    public void shouldKeepAdapterDefaultsWhenOnlyModelNameIsConfigured() {
        ChatModel chatModel =
                new AnthropicModel()
                        .buildChatModel(
                                LlmBasicConfig.builder()
                                        .provider(LlmEnum.ANTHROPIC.getCode())
                                        .modelName("claude-sonnet-4-20250514")
                                        .build());

        assertEquals("https://api.anthropic.com", ModelFields.string(chatModel, "baseUrl"));
        assertEquals(10_000, ModelFields.integer(chatModel, "connectTimeoutMillis"));

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(chatModel);
        assertEquals("claude-sonnet-4-20250514", parameters.modelName());
        assertNull(parameters.temperature());
    }
}
