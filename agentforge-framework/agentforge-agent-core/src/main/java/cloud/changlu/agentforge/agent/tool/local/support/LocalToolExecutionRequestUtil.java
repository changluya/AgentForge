package cloud.changlu.agentforge.agent.tool.local.support;

import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description 本地工具执行请求参数工具类（AgentForge版）：把arguments JSON转换为Map并做容错
 * @author changlu
 * @date 2026/10/04
 */
public class LocalToolExecutionRequestUtil {

    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",(\\s*[}\\]])");
    private static final Pattern LEADING_TRAILING_QUOTE_PATTERN = Pattern.compile("^\"|\"$");
    private static final Pattern ESCAPED_QUOTE_PATTERN = Pattern.compile("\\\\\"");

    private LocalToolExecutionRequestUtil() {}

    /**
     * Convert arguments to map.
     *
     * @param arguments json string
     * @return map
     */
    public static Map<String, Object> argumentsAsMap(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return asMap(Json.parse(arguments));
        } catch (Exception ignored) {
            String normalizedArguments = removeTrailingComma(normalizeJsonString(arguments));
            return asMap(Json.parse(normalizedArguments));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("Expected JSON object");
    }

    /**
     * Removes trailing commas before closing braces or brackets in JSON strings.
     *
     * @param json the JSON string
     * @return the corrected JSON string
     */
    static String removeTrailingComma(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        Matcher matcher = TRAILING_COMMA_PATTERN.matcher(json);
        return matcher.replaceAll("$1");
    }

    /**
     * Normalizes a JSON string by removing leading and trailing quotes and unescaping internal
     * double quotes.
     *
     * @param arguments the raw JSON string
     * @return the normalized JSON string
     */
    static String normalizeJsonString(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return arguments;
        }

        Matcher leadingTrailingMatcher = LEADING_TRAILING_QUOTE_PATTERN.matcher(arguments);
        String normalizedJson = leadingTrailingMatcher.replaceAll("");

        Matcher escapedQuoteMatcher = ESCAPED_QUOTE_PATTERN.matcher(normalizedJson);
        return escapedQuoteMatcher.replaceAll("\"");
    }
}
