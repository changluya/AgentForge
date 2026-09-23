package com.changlu.agentforge.ai.agent.domain;

/**
 * @description Agent运行请求参数
 * @author changlu
 * @date 2026/9/16
 */
public class AgentRequest {

    private Object memoryId;

    private String question;

    public AgentRequest() {
    }

    private AgentRequest(AgentRequestBuilder builder) {
        this.memoryId = builder.memoryId;
        this.question = builder.question;
    }

    public static AgentRequestBuilder builder() {
        return new AgentRequestBuilder();
    }

    public Object getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(Object memoryId) {
        this.memoryId = memoryId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public static class AgentRequestBuilder {
        private Object memoryId;
        private String question;

        public AgentRequestBuilder memoryId(Object memoryId) {
            this.memoryId = memoryId;
            return this;
        }

        public AgentRequestBuilder question(String question) {
            this.question = question;
            return this;
        }

        public AgentRequest build() {
            if (memoryId == null) {
                throw new IllegalArgumentException("memoryId must not be null");
            }
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            return new AgentRequest(this);
        }
    }
}
