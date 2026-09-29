package com.changlu.agentforge.ai.core.component.llm;

import com.changlu.agentforge.ai.core.component.llm.config.LlmBasicConfig;
import com.changlu.agentforge.ai.core.component.llm.models.BaseModel;
import com.changlu.agentforge.ai.core.component.llm.models.IModel;
import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.StreamingChatModel;
import com.changlu.agentforge.llm.chat.response.StreamingChatResponseHandler;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * @description BaseModel 共享判空逻辑与 IModel 契约用例
 * @author changlu
 * @date 2026/9/16
 */
public class BaseModelTest {

    private static final ChatModel NOOP_CHAT_MODEL = chatRequest -> null;

    private static final StreamingChatModel NOOP_STREAMING_CHAT_MODEL =
            new StreamingChatModel() {
                @Override
                public void chat(
                        com.changlu.agentforge.llm.chat.request.ChatRequest chatRequest,
                        StreamingChatResponseHandler handler) {
                    handler.onCompleteResponse(null);
                }
            };

    /** 通过子类桥接 protected 的 isNotEmpty，便于直接断言基类行为。 */
    private static class RecordingModel extends BaseModel {
        private LlmBasicConfig received;

        @Override
        public ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
            this.received = llmBasicConfig;
            return NOOP_CHAT_MODEL;
        }

        @Override
        public StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
            return NOOP_STREAMING_CHAT_MODEL;
        }

        boolean temperatureConfigured() {
            String value =
                    received.getProps() == null
                            ? null
                            : received.getProps().getProperty("temperature");
            return isNotEmpty(value);
        }
    }

    @Test
    public void shouldReceiveTheRawConfigAndReturnAChatModel() {
        RecordingModel recordingModel = new RecordingModel();
        assertTrue(recordingModel instanceof IModel);

        LlmBasicConfig config = LlmBasicConfig.builder().modelName("m").build();

        assertSame(NOOP_CHAT_MODEL, recordingModel.buildChatModel(config));
        assertSame(config, recordingModel.received);
    }

    @Test
    public void shouldTreatMissingNullAndBlankValuesAsEmpty() {
        assertFalse(temperatureOf(LlmBasicConfig.builder().modelName("m").build()));
        assertFalse(
                temperatureOf(
                        LlmBasicConfig.builder().modelName("m").prop("temperature", "").build()));
        assertFalse(
                temperatureOf(
                        LlmBasicConfig.builder()
                                .modelName("m")
                                .prop("temperature", "   ")
                                .build()));
    }

    @Test
    public void shouldRecognizeAConfiguredValue() {
        assertTrue(
                temperatureOf(
                        LlmBasicConfig.builder()
                                .modelName("m")
                                .prop("temperature", "0.2")
                                .build()));
    }

    private static boolean temperatureOf(LlmBasicConfig config) {
        RecordingModel recordingModel = new RecordingModel();
        recordingModel.buildChatModel(config);
        return recordingModel.temperatureConfigured();
    }
}
