package cloud.changlu.agentforge.agent.domain;

/**
 * @description Agent一次运行的结束状态
 * @author changlu
 * @date 2026/9/16
 */
public enum AgentRunState {
    SUCCESS(0, "任务执行成功"),

    MODEL_CALL_ERROR(1001, "模型调用异常"),

    CANCEL(1002, "任务已取消"),

    MAX_STEPS(1003, "执行步长达到上限");

    private final Integer code;
    private final String description;

    AgentRunState(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailure() {
        return !isSuccess();
    }

    public static AgentRunState getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (AgentRunState state : values()) {
            if (state.getCode().equals(code)) {
                return state;
            }
        }
        return null;
    }
}
