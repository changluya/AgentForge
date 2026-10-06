package cloud.changlu.agentforge.model.registry;

import cloud.changlu.agentforge.model.anthropic.AnthropicChatModel;
import cloud.changlu.agentforge.model.anthropic.AnthropicStreamingChatModel;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @description LlmFactory 构建 ChatModel 的用例，不访问真实模型服务
 * @author changlu
 * @date 2026/9/16
 */
public class LlmFactoryTest {

    @Test
    public void shouldBuildOpenAiChatModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("https://api.openai.com/v1")
                        .modelName("gpt-4o-mini")
                        .apiKey("sk-test")
                        .prop(LlmConstant.TEMPERATURE, "0.2")
                        .prop(LlmConstant.MAX_TOKENS, "1024")
                        .prop(LlmConstant.TOP_P, "0.9")
                        .prop(LlmConstant.TIMEOUT, "30")
                        .build();

        ChatModel chatModel = LlmFactory.buildChatModel(config);

        assertTrue(chatModel instanceof OpenAiChatModel);
        assertEquals("gpt-4o-mini", ModelFields.defaultParameters(chatModel).modelName());
    }

    @Test
    public void shouldBuildAnthropicChatModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.ANTHROPIC.getCode())
                        .modelName("claude-sonnet-4-20250514")
                        .apiKey("sk-test")
                        .build();

        ChatModel chatModel = LlmFactory.buildChatModel(config);

        assertTrue(chatModel instanceof AnthropicChatModel);
        assertEquals(Integer.valueOf(1024), ModelFields.defaultParameters(chatModel).maxTokens());
    }

    @Test
    public void shouldBuildOpenAiCompatibleEndpointFromUrl() {
        // DashScope / Ollama / Xinference 等 OpenAI-compatible 服务复用 OPENAI provider，只需配置 url
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("http://127.0.0.1:11434/v1")
                        .modelName("qwen2.5")
                        .build();

        ChatModel chatModel = LlmFactory.buildChatModel(config);

        assertTrue(chatModel instanceof OpenAiChatModel);
        assertEquals("http://127.0.0.1:11434/v1", ModelFields.string(chatModel, "baseUrl"));
    }

    @Test
    public void shouldBuildOpenAiStreamingChatModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("https://api.openai.com/v1")
                        .modelName("gpt-4o-mini")
                        .apiKey("sk-test")
                        .prop(LlmConstant.TEMPERATURE, "0.2")
                        .prop(LlmConstant.MAX_TOKENS, "1024")
                        .prop(LlmConstant.TOP_P, "0.9")
                        .prop(LlmConstant.TIMEOUT, "30")
                        .build();

        StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);

        assertTrue(streamingChatModel instanceof OpenAiStreamingChatModel);
        assertEquals(
                "https://api.openai.com/v1", ModelFields.string(streamingChatModel, "baseUrl"));
        assertEquals("sk-test", ModelFields.string(streamingChatModel, "apiKey"));
        assertEquals(30_000, ModelFields.integer(streamingChatModel, "connectTimeoutMillis"));
        assertEquals(30_000, ModelFields.integer(streamingChatModel, "readTimeoutMillis"));

        DefaultChatRequestParameters parameters = ModelFields.defaultParameters(streamingChatModel);
        assertEquals("gpt-4o-mini", parameters.modelName());
        assertEquals(Double.valueOf(0.2), parameters.temperature());
        assertEquals(Double.valueOf(0.9), parameters.topP());
        assertEquals(Integer.valueOf(1024), parameters.maxTokens());
    }

    @Test
    public void shouldBuildAnthropicStreamingChatModel() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.ANTHROPIC.getCode())
                        .modelName("claude-sonnet-4-20250514")
                        .apiKey("sk-test")
                        .build();

        StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);

        assertTrue(streamingChatModel instanceof AnthropicStreamingChatModel);
        assertEquals(
                "https://api.anthropic.com", ModelFields.string(streamingChatModel, "baseUrl"));
    }

    @Test
    public void shouldRejectUnsupportedProvider() {
        LlmBasicConfig config =
                LlmBasicConfig.builder().provider(999).modelName("any-model").build();

        try {
            LlmFactory.buildChatModel(config);
            fail("Expected an unsupported provider error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported provider code: 999"));
        }
    }

    @Test
    public void shouldRejectMissingProvider() {
        LlmBasicConfig config = LlmBasicConfig.builder().modelName("any-model").build();

        try {
            LlmFactory.buildChatModel(config);
            fail("Expected an unsupported provider error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unsupported provider code: null"));
        }
    }

    @Test
    public void shouldBuildAFreshModelEveryCall() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .modelName("gpt-4o-mini")
                        .build();

        ChatModel first = LlmFactory.buildChatModel(config);
        ChatModel second = LlmFactory.buildChatModel(config);

        assertNotSame(first, second);
        assertEquals(
                ModelFields.defaultParameters(first).modelName(),
                ((DefaultChatRequestParameters) ModelFields.defaultParameters(second)).modelName());
    }
}
