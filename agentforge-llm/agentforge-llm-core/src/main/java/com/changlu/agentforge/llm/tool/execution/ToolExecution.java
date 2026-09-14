package com.changlu.agentforge.llm.tool.execution;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a single tool execution: the {@link ToolExecutionRequest} and the resulting
 * {@link ToolExecutionResult}, plus optional timing and the memory-id the execution ran under.
 *
 * 
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolExecution {

    private final ToolExecutionRequest request;
    private final ToolExecutionResult result;
    private final LocalDateTime startTime;
    private final LocalDateTime finishTime;
    private final Object memoryId;

    private ToolExecution(Builder builder) {
        this.request = Objects.requireNonNull(builder.request, "request");
        this.result = Objects.requireNonNull(builder.result, "result");
        this.startTime = builder.startTime;
        this.finishTime = builder.finishTime;
        this.memoryId = builder.memoryId;
    }

    public ToolExecutionRequest request() {
        return request;
    }

    public ToolExecutionResult result() {
        return result;
    }

    public Object memoryId() {
        return memoryId;
    }

    public String resultText() {
        return result.text();
    }

    public boolean hasFailed() {
        return result.isError();
    }

    public LocalDateTime startTime() {
        return startTime;
    }

    public LocalDateTime finishTime() {
        return finishTime;
    }

    /**
     * @return execution duration, or {@code null} when timing was not recorded
     */
    public Duration duration() {
        if (startTime == null || finishTime == null) {
            return null;
        }
        return Duration.between(startTime, finishTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ToolExecution other = (ToolExecution) obj;
        return Objects.equals(request, other.request)
                && Objects.equals(result, other.result)
                && Objects.equals(startTime, other.startTime)
                && Objects.equals(finishTime, other.finishTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(request, result, startTime, finishTime);
    }

    @Override
    public String toString() {
        return "ToolExecution{"
                + "request=" + request
                + ", result=" + result
                + ", startTime=" + startTime
                + ", finishTime=" + finishTime
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ToolExecutionRequest request;
        private ToolExecutionResult result;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private Object memoryId;

        private Builder() {
        }

        public Builder request(ToolExecutionRequest request) {
            this.request = request;
            return this;
        }

        public Builder result(ToolExecutionResult result) {
            this.result = result;
            return this;
        }

        public Builder startTime(LocalDateTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder finishTime(LocalDateTime finishTime) {
            this.finishTime = finishTime;
            return this;
        }

        public Builder memoryId(Object memoryId) {
            this.memoryId = memoryId;
            return this;
        }

        public ToolExecution build() {
            return new ToolExecution(this);
        }
    }
}