package cloud.changlu.agentforge.model.registry;

import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import cloud.changlu.agentforge.model.registry.models.IModel;

/**
 * @description 可快速构建LLM模型的工厂类
 * @author changlu
 * @date 2026/9/16
 */
public class LlmFactory {

    private LlmFactory() {
        // 工具类，禁止实例化
    }

    /** 构造同步 ChatModel */
    public static ChatModel buildChatModel(LlmBasicConfig llmBasicConfig) {
        return loadModel(llmBasicConfig).buildChatModel(llmBasicConfig);
    }

    /** 构造流式 StreamingChatModel */
    public static StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig) {
        return loadModel(llmBasicConfig).buildStreamChatModel(llmBasicConfig);
    }

    /** 根据 providerCode 加载 IModel 实例（内部复用） */
    private static IModel loadModel(LlmBasicConfig llmBasicConfig) {
        LlmEnum llmEnum = LlmEnum.of(llmBasicConfig.getProvider());
        if (llmEnum == null) {
            throw new IllegalArgumentException(
                    "Unsupported provider code: " + llmBasicConfig.getProvider());
        }

        try {
            // 反射无参构造实例
            return llmEnum.getModelClazz().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to instantiate IModel: " + llmEnum.getModelClazz(), e);
        }
    }
}
