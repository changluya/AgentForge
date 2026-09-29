package com.changlu.agentforge.ai.agent.domain;

import com.changlu.agentforge.ai.agent.step.StopResultState;

/**
 * @description Agent停止结果：包含Agent停止时的状态信息和运行结果
 * @author changlu
 * @date 2026/9/16
 */
public class StopResult {

    // 聊天结果状态
    private StopResultState stopResultState;

    // 运行结果
    private String runRes;

    public StopResult() {}

    private StopResult(Builder builder) {
        this.stopResultState = builder.stopResultState;
        this.runRes = builder.runRes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public StopResultState getStopResultState() {
        return stopResultState;
    }

    public void setStopResultState(StopResultState stopResultState) {
        this.stopResultState = stopResultState;
    }

    public String getRunRes() {
        return runRes;
    }

    public void setRunRes(String runRes) {
        this.runRes = runRes;
    }

    @Override
    public String toString() {
        return "StopResult{stopResultState=" + stopResultState + ", runRes='" + runRes + '\'' + '}';
    }

    public static class Builder {
        private StopResultState stopResultState;
        private String runRes;

        public Builder stopResultState(StopResultState stopResultState) {
            this.stopResultState = stopResultState;
            return this;
        }

        public Builder runRes(String runRes) {
            this.runRes = runRes;
            return this;
        }

        public StopResult build() {
            return new StopResult(this);
        }
    }
}
