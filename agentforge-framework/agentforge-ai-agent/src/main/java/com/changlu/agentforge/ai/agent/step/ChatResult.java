package com.changlu.agentforge.ai.agent.step;

import com.changlu.agentforge.ai.agent.domain.AgentRunState;

/**
 * @description Agent run() 的最终返回
 * @author changlu
 * @date 2026/9/16
 */
public class ChatResult {

    private String question;
    private String res;
    private ChatResultState chatResultState;
    private AgentRunState runState;

    public ChatResult() {}

    private ChatResult(Builder builder) {
        this.question = builder.question;
        this.res = builder.res;
        this.chatResultState = builder.chatResultState;
        this.runState = builder.runState;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ChatResult toFinished(String question, String res) {
        return toFinished(question, res, AgentRunState.SUCCESS);
    }

    public static ChatResult toFinished(String question, String res, AgentRunState runState) {
        return builder()
                .question(question)
                .res(res)
                .runState(runState)
                .chatResultState(ChatResultState.FINISHED)
                .build();
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getRes() {
        return res;
    }

    public void setRes(String res) {
        this.res = res;
    }

    public ChatResultState getChatResultState() {
        return chatResultState;
    }

    public void setChatResultState(ChatResultState chatResultState) {
        this.chatResultState = chatResultState;
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
        return "ChatResult{question='"
                + question
                + "', res='"
                + res
                + "', chatResultState="
                + chatResultState
                + ", runState="
                + runState
                + '}';
    }

    public static class Builder {
        private String question;
        private String res;
        private ChatResultState chatResultState;
        private AgentRunState runState;

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public Builder res(String res) {
            this.res = res;
            return this;
        }

        public Builder chatResultState(ChatResultState chatResultState) {
            this.chatResultState = chatResultState;
            return this;
        }

        public Builder runState(AgentRunState runState) {
            this.runState = runState;
            return this;
        }

        public ChatResult build() {
            return new ChatResult(this);
        }
    }
}
