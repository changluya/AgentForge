package cloud.changlu.agentforge.agent.tool.local.support;

import cloud.changlu.agentforge.model.internal.json.Json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * @description Local Tool复杂参数转换器（AgentForge版）。
 *     <p>兼容模型把Array/Object参数按标准JSON值传入，也兼容把整个JSON值再次编码为String的情况。
 *     由于AgentForge的JSON编解码基于Map/List，本转换器最终返回原始JSON结构（Map/List/标量）， 与{@code
 *     DefaultToolExecutor}的处理策略保持一致。
 * @author changlu
 * @date 2026/10/04
 */
public final class LocalToolArgumentConverter {

    private static final Object NO_MATCH = new Object();

    private LocalToolArgumentConverter() {}

    @SuppressWarnings("unchecked")
    public static <T> T convert(Object argument, Type targetType) {
        if (!(argument instanceof String)) {
            return (T) argument;
        }

        Class<?> rawType = rawClass(targetType);
        String json = (String) argument;

        Object parsed = parseIfMatches(json, rawType);
        if (parsed == NO_MATCH) {
            parsed = parseIfMatches(cleanJsonString(json), rawType);
        }
        if (parsed == NO_MATCH) {
            throw new IllegalArgumentException(
                    "Cannot convert argument to " + targetType + ": " + json);
        }
        return (T) parsed;
    }

    private static Object parseIfMatches(String json, Class<?> rawType) {
        if (json == null || json.isEmpty()) {
            return NO_MATCH;
        }
        try {
            Object parsed = Json.parse(json);
            if (rawType == null || rawType.isInstance(parsed)) {
                return parsed;
            }
            return NO_MATCH;
        } catch (Exception e) {
            return NO_MATCH;
        }
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    private static String cleanJsonString(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        String cleaned = json.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).replace("\\\"", "\"");
        }
        return cleaned;
    }
}
