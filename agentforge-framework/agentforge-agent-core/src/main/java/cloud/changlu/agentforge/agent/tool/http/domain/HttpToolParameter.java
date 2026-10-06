package cloud.changlu.agentforge.agent.tool.http.domain;

import cloud.changlu.agentforge.agent.tool.http.HttpToolExecutor;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * @description HTTP工具参数统一配置类：描述参数名、映射名、使用位置、数据类型、默认值与是否必填
 * @author changlu
 * @date 2026/10/04
 */
public class HttpToolParameter {

    private static final Logger log = Logger.getLogger(HttpToolParameter.class.getName());

    private final String methodParamName; // 参数名
    private final String methodParamDescription; // 参数描述
    private final String mappedName; // 映射后的真实参数名
    private final Integer useTypeValue; // 参数使用类型值（QUERY/BODY/PATH/HEADER）
    private final Integer dataTypeValue; // 参数数据类型值（STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT）
    private final Object defaultValue; // 默认值
    private final Integer required; // 是否必填

    public HttpToolParameter(
            String methodParamName,
            String methodParamDescription,
            String mappedName,
            Integer useTypeValue,
            Integer dataTypeValue,
            Object defaultValue,
            Integer required) {
        this.methodParamName = methodParamName;
        this.methodParamDescription = methodParamDescription;
        this.mappedName = mappedName;
        this.useTypeValue = useTypeValue;
        this.dataTypeValue = dataTypeValue;
        this.defaultValue = defaultValue;
        this.required = required;
    }

    // Getters
    public String getMethodParamName() {
        return methodParamName;
    }

    public String getMappedName() {
        return mappedName;
    }

    public Integer getUseTypeValue() {
        return useTypeValue;
    }

    public Integer getDataTypeValue() {
        return dataTypeValue;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public String getMethodParamDescription() {
        return methodParamDescription;
    }

    public Integer getRequired() {
        return required;
    }

    /** 转换为ToolSpecification所需的JSON Schema属性定义 */
    public Map<String, Object> toJsonSchema() {
        HttpPluginEnums.ParameterType dataType =
                HttpPluginEnums.ParameterType.fromValue(dataTypeValue);

        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        switch (dataType) {
            case INTEGER:
                schema.put("type", "integer");
                break;
            case NUMBER:
                schema.put("type", "number");
                break;
            case BOOLEAN:
                schema.put("type", "boolean");
                break;
            case ARRAY:
                Map<String, Object> items = new LinkedHashMap<String, Object>();
                items.put("type", "string");
                schema.put("type", "array");
                schema.put("items", items);
                break;
            case OBJECT:
                schema.put("type", "object");
                break;
            case STRING:
            default:
                schema.put("type", "string");
                break;
        }
        schema.put("description", methodParamDescription);
        return schema;
    }

    /** 转换为HttpToolExecutor所需的ParameterConfig */
    public HttpToolExecutor.ParameterConfig toParameterConfig() {
        HttpPluginEnums.ParameterType parameterType;
        try {
            parameterType = HttpPluginEnums.ParameterType.fromValue(dataTypeValue);
        } catch (IllegalArgumentException e) {
            // 如果没有匹配到，默认使用 STRING 类型
            parameterType = HttpPluginEnums.ParameterType.STRING;
            log.warning(
                    "参数 '" + methodParamName + "' 的数据类型值 " + dataTypeValue + " 无效，默认使用 STRING 类型");
        }

        return new HttpToolExecutor.ParameterConfig(
                mappedName,
                HttpPluginEnums.ParameterUseType.fromValue(useTypeValue),
                parameterType,
                defaultValue,
                required == null ? 0 : required);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String methodParamName;
        private String methodParamDescription;
        private String mappedName;
        private Integer useTypeValue;
        private Integer dataTypeValue;
        private Object defaultValue;
        private Integer required;

        private Builder() {}

        public Builder methodParamName(String methodParamName) {
            this.methodParamName = methodParamName;
            return this;
        }

        public Builder methodParamDescription(String methodParamDescription) {
            this.methodParamDescription = methodParamDescription;
            return this;
        }

        public Builder mappedName(String mappedName) {
            this.mappedName = mappedName;
            return this;
        }

        public Builder useTypeValue(Integer useTypeValue) {
            this.useTypeValue = useTypeValue;
            return this;
        }

        public Builder dataTypeValue(Integer dataTypeValue) {
            this.dataTypeValue = dataTypeValue;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder required(Integer required) {
            this.required = required;
            return this;
        }

        public HttpToolParameter build() {
            return new HttpToolParameter(
                    methodParamName,
                    methodParamDescription,
                    mappedName,
                    useTypeValue,
                    dataTypeValue,
                    defaultValue,
                    required);
        }
    }
}
