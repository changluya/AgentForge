package cloud.changlu.agentforge.agent.step;

import cloud.changlu.agentforge.agent.domain.AgentRunState;

/**
 * @description 一步think+act的执行结果
 * @author changlu
 * @date 2026/9/16
 */
public class StepResult {

    private StepState state;
    private String res;
    private AgentRunState runState;

    public StepResult() {}

    private StepResult(Builder builder) {
        this.state = builder.state;
        this.res = builder.res;
        this.runState = builder.runState;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 本轮行动完成，继续下一步think */
    public static StepResult toFinished(String message) {
        return builder().res(message).state(StepState.FINISHED).build();
    }

    public static StepResult toStop(String res) {
        return toStop(res, AgentRunState.SUCCESS);
    }

    /** 整个Agent运行结束，直接拿到最终结果 */
    public static StepResult toStop(String res, AgentRunState runState) {
        return builder().res(res).runState(runState).state(StepState.STOP).build();
    }

    public static StepResult toRunning() {
        return builder().state(StepState.RUNNING).build();
    }

    public StepState getState() {
        return state;
    }

    public void setState(StepState state) {
        this.state = state;
    }

    public String getRes() {
        return res;
    }

    public void setRes(String res) {
        this.res = res;
    }

    public AgentRunState getRunState() {
        return runState;
    }

    public void setRunState(AgentRunState runState) {
        this.runState = runState;
    }

    public Integer getRunStateCode() {
        return runState == null ? null : runState.getCode();
    }

    public String getRunStateDescription() {
        return runState == null ? null : runState.getDescription();
    }

    @Override
    public String toString() {
        return "StepResult{state=" + state + ", res='" + res + "', runState=" + runState + '}';
    }

    public static class Builder {
        private StepState state;
        private String res;
        private AgentRunState runState;

        public Builder state(StepState state) {
            this.state = state;
            return this;
        }

        public Builder res(String res) {
            this.res = res;
            return this;
        }

        public Builder runState(AgentRunState runState) {
            this.runState = runState;
            return this;
        }

        public StepResult build() {
            return new StepResult(this);
        }
    }
}
