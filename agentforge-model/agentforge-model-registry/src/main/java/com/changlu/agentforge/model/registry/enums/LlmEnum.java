package com.changlu.agentforge.model.registry.enums;

import com.changlu.agentforge.model.registry.models.AnthropicModel;
import com.changlu.agentforge.model.registry.models.IModel;
import com.changlu.agentforge.model.registry.models.OpenAiModel;

/**
 * 大模型厂商枚举
 *
 * @author changlu
 * @since 2026-09-16
 */
public enum LlmEnum {

    // OpenAI Chat Completions 协议，同时兼容 OpenAI-compatible 服务（DashScope / Ollama / Xinference 等，配置对应
    // url 即可）
    OPENAI(1, "OpenAI", OpenAiModel.class),
    ANTHROPIC(2, "Anthropic", AnthropicModel.class);

    private final int code;
    private final String desc;
    private final Class<? extends IModel> modelClazz;

    LlmEnum(int code, String desc, Class<? extends IModel> modelClazz) {
        this.code = code;
        this.desc = desc;
        this.modelClazz = modelClazz;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public Class<? extends IModel> getModelClazz() {
        return modelClazz;
    }

    /** 根据 code 获取枚举 */
    public static LlmEnum of(Integer code) {
        for (LlmEnum e : values()) {
            if (code != null && e.code == code) {
                return e;
            }
        }
        return null;
    }
}
