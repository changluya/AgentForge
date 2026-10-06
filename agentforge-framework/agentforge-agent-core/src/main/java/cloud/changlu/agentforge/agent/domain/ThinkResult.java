package cloud.changlu.agentforge.agent.domain;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.List;

/**
 * @description think阶段的输出：是否结束、是否需要行动、本轮要执行的工具
 * @author changlu
 * @date 2026/9/16
 */
public class ThinkResult {

    private final AgentRunState state;
    private final Boolean needAct;
    private final Boolean isFinish;
    private final String runRes;
    private final List<ToolExecutionRequest> curActTools;

    private ThinkResult(Builder builder) {
        this.state = builder.state;
        this.needAct = builder.needAct;
        this.isFinish = builder.isFinish;
        this.runRes = builder.runRes;
        this.curActTools = builder.curActTools;
    }

    public static Builder builder() {
        return new Builder();
    }

    public AgentRunState getState() {
        return state;
    }

    public Boolean getNeedAct() {
        return needAct;
    }

    public Boolean getIsFinish() {
        return isFinish;
    }

    public String getRunRes() {
        return runRes;
    }

    public List<ToolExecutionRequest> getCurActTools() {
        return curActTools;
    }

    public Integer getStateCode() {
        return state == null ? null : state.getCode();
    }

    public String getStateDescription() {
        return state == null ? null : state.getDescription();
    }

    public static class Builder {
        private AgentRunState state;
        private Boolean needAct;
        private Boolean isFinish;
        private String runRes;
        private List<ToolExecutionRequest> curActTools;

        private Builder() {}

        public Builder state(AgentRunState state) {
            this.state = state;
            return this;
        }

        public Builder needAct(Boolean needAct) {
            this.needAct = needAct;
            return this;
        }

        public Builder isFinish(Boolean isFinish) {
            this.isFinish = isFinish;
            return this;
        }

        public Builder runRes(String runRes) {
            this.runRes = runRes;
            return this;
        }

        public Builder curActTools(List<ToolExecutionRequest> curActTools) {
            this.curActTools = curActTools;
            return this;
        }

        public ThinkResult build() {
            return new ThinkResult(this);
        }
    }
}
