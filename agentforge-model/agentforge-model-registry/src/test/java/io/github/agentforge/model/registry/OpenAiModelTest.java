package io.github.agentforge.model.registry;

import io.github.agentforge.model.chat.ChatModel;
import io.github.agentforge.model.chat.request.DefaultChatRequestParameters;
import io.github.agentforge.model.registry.config.LlmBasicConfig;
import io.github.agentforge.model.registry.constant.LlmConstant;
import io.github.agentforge.model.registry.enums.LlmEnum;
import io.github.agentforge.model.registry.models.OpenAiModel;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description OpenAiModel 参数映射用例：url / 密钥 / 模型名 与 props 中的通用参数
 * @author changlu
 * @date 2026/9/16
 */
public class OpenAiModelTest {

    @Test
    public void shouldMapConnectionAndCommonProps() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("https://tokenrhythm.studio/v1")
                        .modelName("deepseek-v4-flash-0731")
                        .apiKey("sk-test")
                        .prop(LlmConstant.TEMPERATURE, "0.25")
                        .prop(LlmConstant.TOP_P, "0.75")
                        .prop(LlmConstant.MAX_TOKENS, "64")
                        .prop(LlmConstant.TIMEOUT, "30")
                        .build();

        ChatModel chatModel = new OpenAiModel().buildChatModel(config);

        assertEquals("https://tokenrhythm.studio/v1", ModelFields.string(chatModel, "baseUrl"));
        assertEquals("sk-test", ModelFields.string(chatModel, "apiKey"));
        assertEquals(30_000, ModelFields.integer(chatModel, "connectTimeoutMillis"));
        assertEquals(30_000, ModelFields.integer(chatModel, "readTimeoutMillis"));

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(chatModel);
        assertEquals("deepseek-v4-flash-0731", parameters.modelName());
        assertEquals(Double.valueOf(0.25), parameters.temperature());
        assertEquals(Double.valueOf(0.75), parameters.topP());
        assertEquals(Integer.valueOf(64), parameters.maxTokens());
    }

    @Test
    public void shouldFallBackToAdapterDefaultsWithoutProps() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .modelName("gpt-4o-mini")
                        .build();

        ChatModel chatModel = new OpenAiModel().buildChatModel(config);

        assertNull(config.getProps());
        // 未配置 url / timeout 时沿用 adapter 默认值
        assertEquals("https://api.openai.com/v1", ModelFields.string(chatModel, "baseUrl"));
        assertEquals(10_000, ModelFields.integer(chatModel, "connectTimeoutMillis"));
        assertEquals(60_000, ModelFields.integer(chatModel, "readTimeoutMillis"));

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(chatModel);
        assertEquals("gpt-4o-mini", parameters.modelName());
        assertNull(parameters.temperature());
        assertNull(parameters.topP());
        assertNull(parameters.maxTokens());
    }

    @Test
    public void shouldIgnoreBlankCommonProps() {
        Properties props = new Properties();
        props.setProperty(LlmConstant.TEMPERATURE, " ");
        props.setProperty(LlmConstant.TOP_P, "");
        props.setProperty(LlmConstant.MAX_TOKENS, "  ");

        ChatModel chatModel =
                new OpenAiModel()
                        .buildChatModel(
                                LlmBasicConfig.builder()
                                        .provider(LlmEnum.OPENAI.getCode())
                                        .modelName("gpt-4o-mini")
                                        .props(props)
                                        .build());

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(chatModel);
        assertNull(parameters.temperature());
        assertNull(parameters.topP());
        assertNull(parameters.maxTokens());
    }

    @Test
    public void shouldFailFastOnMalformedCommonProp() {
        try {
            new OpenAiModel()
                    .buildChatModel(
                            LlmBasicConfig.builder()
                                    .provider(LlmEnum.OPENAI.getCode())
                                    .modelName("gpt-4o-mini")
                                    .prop(LlmConstant.MAX_TOKENS, "not-a-number")
                                    .build());
            fail("Expected a NumberFormatException for a malformed maxTokens");
        } catch (NumberFormatException e) {
            assertTrue(true);
        }
    }
}
