package com.changlu.agentforge.ai.agent.domain;

/**
 * @description Agent配置类
 * @author changlu
 * @date 2026/9/16
 */
public class AgentSettings {

    // 最大执行步骤数量
    private final int maxSteps;
    // ai call执行失败 重试次数
    private final int aiCallRetry;
    // ai call 首次重试等待时间，后续按指数退避，默认 2 秒
    private final long aiCallRetryDelay;

    private AgentSettings(Builder builder) {
        this.maxSteps = builder.maxSteps == null ? 10 : builder.maxSteps;
        this.aiCallRetry = builder.aiCallRetry == null ? 3 : builder.aiCallRetry;
        this.aiCallRetryDelay =
                builder.aiCallRetryDelay == null ? 2 * 1000L : builder.aiCallRetryDelay;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getAiCallRetry() {
        return aiCallRetry;
    }

    public long getAiCallRetryDelay() {
        return aiCallRetryDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AgentSettings defaultSettings() {
        return builder().build();
    }

    public static class Builder {
        private Integer maxSteps;
        private Integer aiCallRetry;
        private Long aiCallRetryDelay;

        private Builder() {}

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder aiCallRetry(int aiCallRetry) {
            this.aiCallRetry = aiCallRetry;
            return this;
        }

        public Builder aiCallRetryDelay(long aiCallRetryDelay) {
            this.aiCallRetryDelay = aiCallRetryDelay;
            return this;
        }

        public AgentSettings build() {
            return new AgentSettings(this);
        }
    }
}
