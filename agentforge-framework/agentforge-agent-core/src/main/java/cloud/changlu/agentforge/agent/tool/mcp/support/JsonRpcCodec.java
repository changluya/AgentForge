package cloud.changlu.agentforge.agent.tool.mcp.support;

import cloud.changlu.agentforge.agent.tool.mcp.McpProtocolException;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal JSON-RPC 2.0 codec for the MCP wire format, built on AgentForge's dependency-free {@link
 * Json}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class JsonRpcCodec {

    private static final String JSONRPC = "jsonrpc";
    private static final String VERSION = "2.0";
    private static final String ID = "id";
    private static final String METHOD = "method";
    private static final String PARAMS = "params";
    private static final String RESULT = "result";
    private static final String ERROR = "error";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";

    private JsonRpcCodec() {}

    public static String encodeRequest(long id, String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put(JSONRPC, VERSION);
        message.put(ID, Long.valueOf(id));
        message.put(METHOD, method);
        if (params != null && !params.isEmpty()) {
            message.put(PARAMS, params);
        }
        return Json.stringify(message);
    }

    public static String encodeNotification(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put(JSONRPC, VERSION);
        message.put(METHOD, method);
        if (params != null && !params.isEmpty()) {
            message.put(PARAMS, params);
        }
        return Json.stringify(message);
    }

    /**
     * Extracts the {@code id} of a JSON-RPC request/notification, or {@code null} for
     * notifications.
     */
    public static Object idOf(String message) {
        Map<String, Object> parsed = Json.parseObject(message);
        return parsed.get(ID);
    }

    /**
     * Parses a JSON-RPC response, throwing {@link McpProtocolException} when it carries an {@code
     * error} object.
     *
     * @param response the raw response message
     * @param expectedId the request id, used only for diagnostics
     * @return the {@code result} object, or an empty map when the response has no result
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resultOf(String response, Object expectedId) {
        Map<String, Object> message;
        try {
            message = Json.parseObject(response);
        } catch (RuntimeException e) {
            throw new McpProtocolException(
                    -32700, "Malformed JSON-RPC response for id " + expectedId, e);
        }

        Object error = message.get(ERROR);
        if (error != null) {
            Map<String, Object> errorObject = Json.object(error);
            long code = errorObject == null ? 0 : Json.longValue(errorObject.get(CODE), 0);
            String text =
                    errorObject == null
                            ? String.valueOf(error)
                            : Json.string(errorObject.get(MESSAGE));
            throw new McpProtocolException(code, text == null ? "MCP error " + code : text);
        }

        Object result = message.get(RESULT);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        return Collections.emptyMap();
    }

    public static boolean hasError(String response) {
        Map<String, Object> message = Json.parseObject(response);
        return message.get(ERROR) != null;
    }
}
