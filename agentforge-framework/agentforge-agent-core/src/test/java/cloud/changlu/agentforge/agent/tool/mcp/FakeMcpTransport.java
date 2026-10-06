package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.transport.McpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link McpTransport} that answers requests from a {@link Responder} and records
 * traffic.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class FakeMcpTransport implements McpTransport {

    /** Produces a raw JSON-RPC response for a raw request. */
    public interface Responder {
        String respond(String request);
    }

    private final Responder responder;
    private final List<String> requests = new ArrayList<String>();
    private final List<String> notifications = new ArrayList<String>();
    private boolean closed;

    public FakeMcpTransport(Responder responder) {
        this.responder = responder;
    }

    public List<String> requests() {
        return Collections.unmodifiableList(requests);
    }

    public List<String> notifications() {
        return Collections.unmodifiableList(notifications);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void send(String message) {
        notifications.add(message);
    }

    @Override
    public String request(String message) throws IOException {
        requests.add(message);
        return responder.respond(message);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
