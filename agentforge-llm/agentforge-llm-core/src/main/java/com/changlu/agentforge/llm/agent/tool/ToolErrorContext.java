package com.changlu.agentforge.llm.agent.tool;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import java.util.Objects;

/**
 * Context passed to a {@link ToolArgumentsErrorHandler} or {@link ToolExecutionErrorHandler}.
 *
 * <p>Carries the tool execution request, the {@link ToolExecution} (when available) and the raw
 * (cause-unwrapped) error.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolErrorContext {

    private final ToolExecutionRequest toolExecutionRequest;
    private final ToolExecution toolExecution;
    private final Throwable rawError;

    private ToolErrorContext(Builder builder) {
        this.toolExecutionRequest = builder.toolExecutionRequest;
        this.toolExecution = builder.toolExecution;
        this.rawError = builder.rawError;
    }

    public ToolExecutionRequest toolExecutionRequest() {
        return toolExecutionRequest;
    }

    /**
     * @return the tool execution, or {@code null} when the failure happened before execution
     */
    public ToolExecution toolExecution() {
        return toolExecution;
    }

    /**
     * @return the raw (cause-unwrapped) error
     */
    public Throwable rawError() {
        return rawError;
    }

    @Override
    public String toString() {
        return "ToolErrorContext{"
                + "toolExecutionRequest=" + Objects.toString(toolExecutionRequest)
                + ", toolExecution=" + Objects.toString(toolExecution)
                + ", rawError=" + rawError
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ToolExecutionRequest toolExecutionRequest;
        private ToolExecution toolExecution;
        private Throwable rawError;

        private Builder() {
        }

        public Builder toolExecutionRequest(ToolExecutionRequest toolExecutionRequest) {
            this.toolExecutionRequest = toolExecutionRequest;
            return this;
        }

        public Builder toolExecution(ToolExecution toolExecution) {
            this.toolExecution = toolExecution;
            return this;
        }

        public Builder rawError(Throwable rawError) {
            this.rawError = rawError;
            return this;
        }

        public ToolErrorContext build() {
            return new ToolErrorContext(this);
        }
    }
}