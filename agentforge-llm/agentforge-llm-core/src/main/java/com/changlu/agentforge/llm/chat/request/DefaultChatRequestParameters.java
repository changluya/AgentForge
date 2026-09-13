package com.changlu.agentforge.llm.chat.request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable default implementation of {@link ChatRequestParameters}.
 */
public final class DefaultChatRequestParameters implements ChatRequestParameters {

    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final Double topP;
    private final List<String> stopSequences;
    private final Map<String, Object> customParameters;

    private DefaultChatRequestParameters(Builder builder) {
        this.modelName = builder.modelName;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.topP = builder.topP;
        this.stopSequences = immutableList(builder.stopSequences);
        this.customParameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.customParameters));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Merge request-level parameters over model-level defaults.
     */
    public static DefaultChatRequestParameters merge(ChatRequestParameters defaults,
                                                     ChatRequestParameters overrides) {
        Builder builder = builder();
        apply(builder, defaults);
        apply(builder, overrides);
        return builder.build();
    }

    private static void apply(Builder builder, ChatRequestParameters parameters) {
        if (parameters == null) {
            return;
        }
        if (parameters.modelName() != null) {
            builder.modelName(parameters.modelName());
        }
        if (parameters.temperature() != null) {
            builder.temperature(parameters.temperature());
        }
        if (parameters.maxTokens() != null) {
            builder.maxTokens(parameters.maxTokens());
        }
        if (parameters.topP() != null) {
            builder.topP(parameters.topP());
        }
        if (parameters.stopSequences() != null) {
            builder.stopSequences(parameters.stopSequences());
        }
        if (parameters.customParameters() != null) {
            builder.customParameters(parameters.customParameters());
        }
    }

    private static List<String> immutableList(List<String> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public Double temperature() {
        return temperature;
    }

    @Override
    public Integer maxTokens() {
        return maxTokens;
    }

    @Override
    public Double topP() {
        return topP;
    }

    @Override
    public List<String> stopSequences() {
        return stopSequences;
    }

    @Override
    public Map<String, Object> customParameters() {
        return customParameters;
    }

    public static final class Builder {
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private List<String> stopSequences;
        private final Map<String, Object> customParameters = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences == null ? null : new ArrayList<String>(stopSequences);
            return this;
        }

        public Builder customParameter(String name, Object value) {
            if (name != null) {
                this.customParameters.put(name, value);
            }
            return this;
        }

        public Builder customParameters(Map<String, Object> customParameters) {
            if (customParameters != null) {
                this.customParameters.putAll(customParameters);
            }
            return this;
        }

        public DefaultChatRequestParameters build() {
            return new DefaultChatRequestParameters(this);
        }
    }
}
