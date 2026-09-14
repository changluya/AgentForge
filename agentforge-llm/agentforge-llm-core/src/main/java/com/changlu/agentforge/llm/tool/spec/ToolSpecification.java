package com.changlu.agentforge.llm.tool.spec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a tool (function) that the model is allowed to call.
 *
 * 
 * an optional {@link ToolParameters} JSON schema and an optional {@code strict} flag.</p>
 *
 * <p>Provider adapters map this to their own wire format:</p>
 * <pre>
 * OpenAI     : {"type":"function","function":{"name","description","parameters","strict"}}
 * Anthropic  : {"name","description","input_schema"}
 * </pre>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolSpecification {

    private final String name;
    private final String description;
    private final ToolParameters parameters;
    private final Boolean strict;
    private final Map<String, Object> metadata;

    private ToolSpecification(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.description = builder.description;
        this.parameters = builder.parameters;
        this.strict = builder.strict;
        this.metadata = immutableCopy(builder.metadata);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * @return the parameter schema, or {@code null} when the tool takes no arguments
     */
    public ToolParameters parameters() {
        return parameters;
    }

    /**
     * @return true when the provider should enforce strict schema validation, or {@code null} when unset
     */
    public Boolean strict() {
        return strict;
    }

    /**
     * @return provider-neutral metadata, never {@code null}
     */
    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ToolSpecification other = (ToolSpecification) obj;
        return Objects.equals(name, other.name)
                && Objects.equals(description, other.description)
                && Objects.equals(parameters, other.parameters)
                && Objects.equals(strict, other.strict)
                && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, parameters, strict, metadata);
    }

    @Override
    public String toString() {
        return "ToolSpecification{"
                + "name='" + name + '\''
                + ", description='" + description + '\''
                + ", parameters=" + parameters
                + ", strict=" + strict
                + ", metadata=" + metadata
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .strict(strict)
                .metadata(metadata);
    }

    public static ToolSpecification from(String name, String description, ToolParameters parameters) {
        return builder().name(name).description(description).parameters(parameters).build();
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(source));
    }

    public static final class Builder {

        private String name;
        private String description;
        private ToolParameters parameters;
        private Boolean strict;
        private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder parameters(ToolParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder strict(Boolean strict) {
            this.strict = strict;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.clear();
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (key != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public ToolSpecification build() {
            return new ToolSpecification(this);
        }
    }
}