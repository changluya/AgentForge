package cloud.changlu.agentforge.agent.tool.mcp;

/**
 * Raised when an MCP transport cannot complete a request (process died, HTTP error, malformed
 * stream, ...).
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
