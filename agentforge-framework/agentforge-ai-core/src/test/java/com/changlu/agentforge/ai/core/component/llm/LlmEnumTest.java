package com.changlu.agentforge.ai.core.component.llm;

import com.changlu.agentforge.ai.core.component.llm.enums.LlmEnum;
import com.changlu.agentforge.ai.core.component.llm.models.AnthropicModel;
import com.changlu.agentforge.ai.core.component.llm.models.BaseModel;
import com.changlu.agentforge.ai.core.component.llm.models.IModel;
import com.changlu.agentforge.ai.core.component.llm.models.OpenAiModel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @description 厂商枚举 code 解析用例
 * @author changlu
 * @date 2026/9/16
 */
public class LlmEnumTest {

    @Test
    public void shouldResolveProviderByCode() {
        assertEquals(LlmEnum.OPENAI, LlmEnum.of(1));
        assertEquals(LlmEnum.ANTHROPIC, LlmEnum.of(2));
        assertEquals(LlmEnum.OPENAI, LlmEnum.of(Integer.valueOf(1)));
    }

    @Test
    public void shouldReturnNullForUnknownOrMissingCode() {
        assertNull(LlmEnum.of(999));
        assertNull(LlmEnum.of(0));
        assertNull(LlmEnum.of(null));
    }

    @Test
    public void shouldKeepStableCodesAndModelClasses() {
        assertEquals(1, LlmEnum.OPENAI.getCode());
        assertEquals(2, LlmEnum.ANTHROPIC.getCode());
        assertEquals("OpenAI", LlmEnum.OPENAI.getDesc());
        assertEquals(OpenAiModel.class, LlmEnum.OPENAI.getModelClazz());
        assertEquals(AnthropicModel.class, LlmEnum.ANTHROPIC.getModelClazz());
    }

    @Test
    public void shouldInstantiateEveryModelClass() throws Exception {
        for (LlmEnum llmEnum : LlmEnum.values()) {
            assertTrue(
                    llmEnum.getModelClazz() + " must implement IModel",
                    IModel.class.isAssignableFrom(llmEnum.getModelClazz()));
            assertTrue(
                    llmEnum.getModelClazz() + " must extend BaseModel",
                    BaseModel.class.isAssignableFrom(llmEnum.getModelClazz()));

            IModel model = llmEnum.getModelClazz().getDeclaredConstructor().newInstance();
            assertNotNull(model);
        }
    }
}
