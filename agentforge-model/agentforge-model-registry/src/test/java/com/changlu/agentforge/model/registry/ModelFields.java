package com.changlu.agentforge.model.registry;

import com.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;

import java.lang.reflect.Field;

/**
 * @description 测试辅助：AgentForge 的 ChatModel adapter 不暴露 getter，这里反射读取构建结果， 用于断言 LlmBasicConfig
 *     的参数确实被映射到了具体模型对象上
 * @author changlu
 * @date 2026/9/16
 */
final class ModelFields {

    private ModelFields() {}

    static String string(Object model, String fieldName) {
        return (String) read(model, fieldName);
    }

    static int integer(Object model, String fieldName) {
        return (Integer) read(model, fieldName);
    }

    static DefaultChatRequestParameters defaultParameters(Object model) {
        return (DefaultChatRequestParameters) read(model, "defaultParameters");
    }

    private static Object read(Object model, String fieldName) {
        try {
            Field field = model.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(model);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot read field '" + fieldName + "' of " + model.getClass(), e);
        }
    }
}
