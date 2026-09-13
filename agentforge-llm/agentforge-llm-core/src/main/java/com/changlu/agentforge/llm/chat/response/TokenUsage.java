package com.changlu.agentforge.llm.chat.response;

public final class TokenUsage {

    private final long inputTokens;
    private final long outputTokens;
    private final long totalTokens;

    public TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens);
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return totalTokens;
    }
}
