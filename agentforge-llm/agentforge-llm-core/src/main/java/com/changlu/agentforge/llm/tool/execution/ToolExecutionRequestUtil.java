package com.changlu.agentforge.llm.tool.execution;

import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.internal.json.Json;

import java.util.Map;
import com.changlu.agentforge.llm.tool.error.ToolArgumentsException;

/**
 * Utilities for extracting data from a {@link ToolExecutionRequest}'s JSON arguments.
 *
 * @author changlu
 * @since 2026-09-13
 */
final class ToolExecutionRequestUtil {

    private ToolExecutionRequestUtil() {
    }

    /**
     * Parses the {@code arguments} JSON string into a {@code Map}.
     *
     * @param request the tool execution request
     * @return the arguments as a map; empty when the arguments are blank or {@code "{}"}
     * @throws ToolArgumentsException when the arguments cannot be parsed into an object
     */
    static Map<String, Object> argumentsAsMap(ToolExecutionRequest request) {
        String arguments = request == null ? null : request.arguments();
        if (arguments == null || arguments.trim().isEmpty() || "{}".equals(arguments.trim())) {
            return new java.util.LinkedHashMap<String, Object>();
        }
        Object parsed;
        try {
            parsed = Json.parse(arguments);
        } catch (RuntimeException e) {
            throw new ToolArgumentsException(
                    "Failed to parse tool arguments of tool '" + (request == null ? "" : request.name()) + "': "
                            + arguments, e);
        }
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        }
        throw new ToolArgumentsException(
                "Tool arguments must be a JSON object, but got: " + arguments);
    }
}