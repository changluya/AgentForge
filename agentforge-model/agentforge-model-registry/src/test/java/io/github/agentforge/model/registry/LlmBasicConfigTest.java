package io.github.agentforge.model.registry;

import io.github.agentforge.model.registry.config.LlmBasicConfig;
import io.github.agentforge.model.registry.constant.LlmConstant;
import io.github.agentforge.model.registry.enums.LlmEnum;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * @description LlmBasicConfig 字段与 props 读写用例
 * @author changlu
 * @date 2026/9/16
 */
public class LlmBasicConfigTest {

    @Test
    public void shouldReadValuesFromBuilder() {
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url("https://api.openai.com/v1")
                        .modelName("gpt-4o-mini")
                        .apiKey("sk-test")
                        .prop(LlmConstant.TEMPERATURE, "0.2")
                        .prop(LlmConstant.MAX_TOKENS, "1024")
                        .build();

        assertEquals(Integer.valueOf(1), config.getProvider());
        assertEquals("https://api.openai.com/v1", config.getUrl());
        assertEquals("gpt-4o-mini", config.getModelName());
        assertEquals("sk-test", config.getApiKey());
        assertEquals("0.2", config.getProps().getProperty(LlmConstant.TEMPERATURE));
        assertEquals("1024", config.getProps().getProperty(LlmConstant.MAX_TOKENS));
    }

    @Test
    public void shouldSupportBeanStyleSetters() {
        Properties props = new Properties();
        props.setProperty(LlmConstant.TOP_P, "0.9");

        LlmBasicConfig config = new LlmBasicConfig();
        config.setProvider(LlmEnum.ANTHROPIC.getCode());
        config.setUrl("https://api.anthropic.com");
        config.setModelName("claude-sonnet-4-20250514");
        config.setApiKey("sk-test");
        config.setProps(props);

        assertEquals(Integer.valueOf(2), config.getProvider());
        assertEquals("https://api.anthropic.com", config.getUrl());
        assertEquals("claude-sonnet-4-20250514", config.getModelName());
        assertEquals("sk-test", config.getApiKey());
        assertSame(props, config.getProps());
    }

    @Test
    public void shouldLeavePropsNullUntilAPropertyIsSet() {
        assertNull(LlmBasicConfig.builder().modelName("m").build().getProps());

        LlmBasicConfig config =
                LlmBasicConfig.builder().modelName("m").prop(LlmConstant.TIMEOUT, "30").build();
        assertEquals(1, config.getProps().size());
        assertEquals("30", config.getProps().getProperty(LlmConstant.TIMEOUT));
    }

    @Test
    public void shouldReplacePropsWhenPassedExplicitly() {
        Properties first = new Properties();
        first.setProperty(LlmConstant.TEMPERATURE, "0.1");
        Properties second = new Properties();
        second.setProperty(LlmConstant.TEMPERATURE, "0.8");

        LlmBasicConfig config = LlmBasicConfig.builder().props(first).props(second).build();

        assertEquals("0.8", config.getProps().getProperty(LlmConstant.TEMPERATURE));
        assertEquals(1, config.getProps().size());
    }
}
