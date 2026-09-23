package com.changlu.agentforge.ai.agent.step;

/**
 * @description stop结果状态
 * @author changlu
 * @date 2026/9/16
 */
public enum StopResultState {

    NORMAL(0, "normal stop"),

    CANCEL(1, "cancel stop"),

    MAX_STEPS(2, "greater max steps stop");

    private final Integer code;

    private final String description;

    StopResultState(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 按code取枚举，未命中返回null
     *
     * @param code the mode code
     * @return corresponding StopResultState, or null if not found
     */
    public static StopResultState getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (StopResultState mode : values()) {
            if (mode.getCode().equals(code)) {
                return mode;
            }
        }
        return null;
    }
}
