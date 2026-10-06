package cloud.changlu.agentforge.agent.tool.http.enums;

/**
 * @description HTTP插件相关枚举：请求方法、参数数据类型、参数使用类型、是否必填
 * @author changlu
 * @date 2026/10/04
 */
public class HttpPluginEnums {

    /** HTTP 方法枚举（支持值和名称） */
    public enum HttpMethod {
        GET(1, "GET"),
        POST(2, "POST"),
        PUT(3, "PUT"),
        DELETE(4, "DELETE"),
        PATCH(5, "PATCH");

        private final Integer value;
        private final String methodName;

        HttpMethod(Integer value, String methodName) {
            this.value = value;
            this.methodName = methodName;
        }

        public Integer getValue() {
            return value;
        }

        public String getMethodName() {
            return methodName;
        }

        /** 根据值获取枚举 */
        public static HttpMethod fromValue(Integer value) {
            for (HttpMethod method : values()) {
                if (method.value.equals(value)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("无效的HttpMethod值: " + value);
        }

        /** 根据名称获取枚举（不区分大小写） */
        public static HttpMethod fromName(String name) {
            for (HttpMethod method : values()) {
                if (method.methodName.equalsIgnoreCase(name)) {
                    return method;
                }
            }
            throw new IllegalArgumentException("无效的HttpMethod名称: " + name);
        }
    }

    /** 参数数据类型枚举 */
    public enum ParameterType {
        STRING(1, "string"),
        INTEGER(2, "integer"),
        NUMBER(3, "number"),
        BOOLEAN(4, "boolean"),
        ARRAY(5, "array"),
        OBJECT(6, "object");

        private final Integer value;
        private final String name;

        ParameterType(Integer value, String name) {
            this.value = value;
            this.name = name;
        }

        public Integer getValue() {
            return value;
        }

        public String getName() {
            return name;
        }

        public static ParameterType fromValue(Integer value) {
            for (ParameterType type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid ParameterType value: " + value);
        }

        public static ParameterType fromName(String name) {
            for (ParameterType type : values()) {
                if (type.name.equalsIgnoreCase(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid ParameterType name: " + name);
        }
    }

    /** 参数使用类型 */
    public enum ParameterUseType {
        QUERY(1, "query"), // URL查询参数
        BODY(2, "body"), // 请求体参数
        PATH(3, "path"), // URL路径参数
        HEADER(4, "header"); // 请求头参数

        private final Integer value;
        private final String name;

        ParameterUseType(Integer value, String name) {
            this.value = value;
            this.name = name;
        }

        public Integer getValue() {
            return value;
        }

        public String getName() {
            return name;
        }

        // 根据值获取枚举
        public static ParameterUseType fromValue(Integer value) {
            for (ParameterUseType type : values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid ParameterUseType value: " + value);
        }

        // 根据名称获取枚举
        public static ParameterUseType fromName(String name) {
            for (ParameterUseType type : values()) {
                if (type.name.equalsIgnoreCase(name)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Invalid ParameterUseType name: " + name);
        }
    }

    /** 是否必填枚举 */
    public enum RequiredStatus {
        NOT_REQUIRED(0, "否"),
        REQUIRED(1, "是");

        private final Integer code;
        private final String desc;

        RequiredStatus(Integer code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public Integer getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }

        public static RequiredStatus getByCode(Integer code) {
            for (RequiredStatus status : RequiredStatus.values()) {
                if (status.getCode().equals(code)) {
                    return status;
                }
            }
            return null;
        }
    }
}
