package cloud.changlu.agentforge.agent.tool.mcp.transport;

import java.io.Closeable;
import java.io.IOException;

/**
 * Transport SPI for MCP. Implementations carry single-line JSON-RPC messages of the MCP wire
 * protocol and never interpret the MCP methods themselves.
 *
 * <p>The {@link #request(String)} contract is intentionally one-request/one-response so the same
 * abstraction fits both newline-framed {@code stdio} and HTTP {@code Streamable HTTP} without
 * exposing an async reactor model (kept out of Java 8 / zero-dependency core).
 *
 * @author changlu
 * @date 2026/10/05
 */
public interface McpTransport extends Closeable {

    /**
     * Sends a message without waiting for a response (JSON-RPC notifications).
     *
     * @param message the JSON-RPC notification
     * @throws IOException when the transport is closed or the write fails
     */
    void send(String message) throws IOException;

    /**
     * Sends a JSON-RPC request and returns the matching JSON-RPC response message. Messages that do
     * not belong to this request (notifications, replies to other ids) are ignored.
     *
     * @param message the JSON-RPC request
     * @return the matching raw JSON-RPC response
     * @throws IOException when the transport is closed, times out or the peer disconnects
     */
    String request(String message) throws IOException;

    /**
     * @return {@code true} while the underlying channel/process is usable
     */
    boolean isOpen();

    @Override
    void close();
}
