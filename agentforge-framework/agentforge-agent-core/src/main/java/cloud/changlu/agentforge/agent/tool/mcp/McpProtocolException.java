package cloud.changlu.agentforge.agent.tool.mcp;

/**
 * Raised when an MCP server replies with a JSON-RPC {@code error} object.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long code;

    public McpProtocolException(long code, String message) {
        super(message);
        this.code = code;
    }

    public McpProtocolException(long code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * @return the JSON-RPC error code, e.g. {@code -32601} (method not found)
     */
    public long code() {
        return code;
    }
}
