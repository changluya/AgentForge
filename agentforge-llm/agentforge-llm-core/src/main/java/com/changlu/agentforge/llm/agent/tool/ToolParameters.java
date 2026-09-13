package com.changlu.agentforge.llm.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes the parameters of a tool using a JSON-schema-like structure.
 *
 * <p>AgentForge intentionally keeps the schema as a plain {@code Map} so the core module
 * does not depend on any JSON library, mirroring the "dependency-free" philosophy of
 * {@code agentforge-llm-core}. Provider adapters serialize this structure into their own
 * wire format:</p>
 * <ul>
 *   <li>OpenAI: {@code tools[].function.parameters}</li>
 *   <li>Anthropic: {@code tools[].input_schema}</li>
 * </ul>
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolParameters {

    private static final String TYPE = "type";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";

    private final Map<String, Object> schema;

    private ToolParameters(Map<String, Object> schema) {
        this.schema = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(schema == null ? Collections.<String, Object>emptyMap() : schema));
    }

    /**
     * Returns the raw JSON-schema map.
     *
     * @return immutable schema map
     */
    public Map<String, Object> toMap() {
        return schema;
    }

    public String type() {
        Object type = schema.get(TYPE);
        return type == null ? null : String.valueOf(type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> properties() {
        Object properties = schema.get(PROPERTIES);
        if (properties instanceof Map) {
            return Collections.unmodifiableMap(new LinkedHashMap<String, Object>((Map<String, Object>) properties));
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public List<String> required() {
        Object required = schema.get(REQUIRED);
        if (required instanceof List) {
            List<String> names = new ArrayList<String>();
            for (Object item : (List<Object>) required) {
                names.add(String.valueOf(item));
            }
            return Collections.unmodifiableList(names);
        }
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        return Objects.equals(schema, ((ToolParameters) obj).schema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema);
    }

    @Override
    public String toString() {
        return "ToolParameters{" + "schema=" + schema + '}';
    }

    /**
     * Creates tool parameters from a raw JSON-schema map.
     *
     * @param schema the schema, may be {@code null}
     * @return immutable tool parameters
     */
    public static ToolParameters from(Map<String, Object> schema) {
        return new ToolParameters(schema);
    }

    public static ToolParameters toolParameters(Map<String, Object> schema) {
        return from(schema);
    }

    /**
     * Creates an empty {@code object} schema, useful for tools without arguments.
     *
     * @return a minimal {@code {"type":"object","properties":{}}} schema
     */
    public static ToolParameters empty() {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put(TYPE, "object");
        schema.put(PROPERTIES, Collections.emptyMap());
        return new ToolParameters(schema);
    }

    /**
     * Creates a builder for a JSON-schema style {@link ToolParameters}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ToolParameters}.
     */
    public static final class Builder {

        private final LinkedHashMap<String, Object> properties = new LinkedHashMap<String, Object>();
        private final List<String> required = new ArrayList<String>();
        private String type = "object";

        private Builder() {
        }

        public Builder type(String type) {
            if (type != null && !type.trim().isEmpty()) {
                this.type = type;
            }
            return this;
        }

        /**
         * Adds a single property definition.
         *
         * @param name       property name
         * @param propertyType JSON-schema type, e.g. {@code string}, {@code number}, {@code boolean}
         * @param description human readable description
         * @param isRequired whether the property is required
         * @return this builder
         */
        public Builder addProperty(String name, String propertyType, String description, boolean isRequired) {
            Objects.requireNonNull(name, "name");
            LinkedHashMap<String, Object> definition = new LinkedHashMap<String, Object>();
            if (propertyType != null) {
                definition.put(TYPE, propertyType);
            }
            if (description != null) {
                definition.put("description", description);
            }
            properties.put(name, definition);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * Adds a property with a raw JSON-schema definition, allowing nested objects and arrays.
         *
         * @param name property name
         * @param definition property schema
         * @param isRequired whether the property is required
         * @return this builder
         */
        public Builder addProperty(String name, Map<String, Object> definition, boolean isRequired) {
            Objects.requireNonNull(name, "name");
            properties.put(name, definition == null
                    ? Collections.<String, Object>emptyMap()
                    : new LinkedHashMap<String, Object>(definition));
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        public ToolParameters build() {
            LinkedHashMap<String, Object> schema = new LinkedHashMap<String, Object>();
            schema.put(TYPE, type);
            schema.put(PROPERTIES, new LinkedHashMap<String, Object>(properties));
            if (!required.isEmpty()) {
                schema.put(REQUIRED, new ArrayList<String>(required));
            }
            return new ToolParameters(schema);
        }
    }
}