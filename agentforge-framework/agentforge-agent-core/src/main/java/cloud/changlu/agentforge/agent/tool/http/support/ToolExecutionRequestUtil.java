package cloud.changlu.agentforge.agent.tool.http.support;

import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description HTTP工具执行请求参数工具类：把模型产生的arguments JSON转换为Map，并做容错处理
 * @author changlu
 * @date 2026/10/04
 */
public class ToolExecutionRequestUtil {

    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",(\\s*[}\\]])");
    private static final Pattern LEADING_TRAILING_QUOTE_PATTERN = Pattern.compile("^\"|\"$");
    private static final Pattern ESCAPED_QUOTE_PATTERN = Pattern.compile("\\\\\"");

    private ToolExecutionRequestUtil() {}

    /**
     * Convert arguments to map.
     *
     * @param arguments json string
     * @return map
     */
    public static Map<String, Object> argumentsAsMap(String arguments) {
        if (isNullOrBlank(arguments)) {
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
        if (isNullOrEmpty(json)) {
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
        if (isNullOrEmpty(arguments)) {
            return arguments;
        }

        Matcher leadingTrailingMatcher = LEADING_TRAILING_QUOTE_PATTERN.matcher(arguments);
        String normalizedJson = leadingTrailingMatcher.replaceAll("");

        Matcher escapedQuoteMatcher = ESCAPED_QUOTE_PATTERN.matcher(normalizedJson);
        return escapedQuoteMatcher.replaceAll("\"");
    }

    /**
     * Is the given string {@code null} or empty ("")?
     *
     * @param string The string to check.
     * @return true if the string is {@code null} or empty.
     */
    public static boolean isNullOrEmpty(String string) {
        return string == null || string.isEmpty();
    }

    /**
     * Is the given string {@code null} or blank?
     *
     * @param string The string to check.
     * @return true if the string is {@code null} or blank.
     */
    public static boolean isNullOrBlank(String string) {
        return string == null || string.trim().isEmpty();
    }

    /**
     * Convert map to JSON string.
     *
     * @param map the map to convert
     * @return JSON string
     */
    public static String toJson(Map<String, Object> map) {
        return Json.stringify(map);
    }
}
