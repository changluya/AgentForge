package com.changlu.agentforge.ai.core.component.llm.models;

/**
 * @description 模型构建公共基类，各厂商共用的参数解析逻辑放在这里
 * @author changlu
 * @date 2026/9/16
 */
public abstract class BaseModel implements IModel {

    protected boolean isNotEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
