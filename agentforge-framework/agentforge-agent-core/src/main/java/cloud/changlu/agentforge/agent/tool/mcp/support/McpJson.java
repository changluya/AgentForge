package cloud.changlu.agentforge.agent.tool.mcp.support;

import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.Collections;
import java.util.Map;

/**
 * JSON helpers shared by the MCP client, transports and tool mappers.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpJson {

    private McpJson() {}

    /**
     * Converts a model-produced arguments JSON string into a map.
     *
     * @param arguments the raw JSON arguments, may be {@code null} or blank
     * @return the arguments map, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> argumentsAsMap(String arguments) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = Json.parse(arguments);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            return Collections.emptyMap();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid tool arguments JSON: " + arguments, e);
        }
    }

    public static String toJson(Object value) {
        return Json.stringify(value);
    }
}
