package cloud.changlu.agentforge.agent.tool.mcp.transport;

import cloud.changlu.agentforge.agent.tool.mcp.McpTransportException;
import cloud.changlu.agentforge.agent.tool.mcp.support.JsonRpcCodec;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code Streamable HTTP} transport built on AgentForge's dependency-free {@link
 * HttpTransport}.
 *
 * <p>A single endpoint answers {@code POST}s with either {@code application/json} (one JSON-RPC
 * message) or a request-scoped {@code text/event-stream} whose SSE {@code data:} events carry the
 * JSON-RPC messages. Both shapes are handled transparently.
 *
 * <p>Scope note: this P0 implementation targets stateless servers. Session-id echoing ({@code
 * Mcp-Session-Id}) is intentionally not wired yet because {@link HttpResponse} does not expose
 * response headers — that is a P1 item.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class StreamableHttpMcpTransport implements McpTransport {

    private final String endpoint;
    private final Map<String, String> headers;
    private final HttpTransport httpTransport;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private volatile String protocolVersion;
    private volatile boolean closed;

    private StreamableHttpMcpTransport(Builder builder) {
        this.endpoint = builder.endpoint;
        this.headers =
                Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.headers));
        this.httpTransport =
                builder.httpTransport != null ? builder.httpTransport : new JdkHttpTransport();
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
        this.protocolVersion = builder.protocolVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Records the negotiated protocol version so subsequent requests carry the {@code
     * MCP-Protocol-Version} header.
     */
    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    @Override
    public void send(String message) {
        post(message);
    }

    @Override
    public String request(String message) {
        Object id = JsonRpcCodec.idOf(message);
        String body = post(message);
        return selectResponse(body, id);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    private String post(String message) {
        if (closed) {
            throw new McpTransportException("MCP HTTP transport is closed");
        }
        HttpRequest.Builder requestBuilder =
                HttpRequest.builder()
                        .url(endpoint)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .body(message)
                        .connectTimeoutMillis(connectTimeoutMillis)
                        .readTimeoutMillis(readTimeoutMillis);
        if (protocolVersion != null) {
            requestBuilder.header("MCP-Protocol-Version", protocolVersion);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        HttpResponse response;
        try {
            response = httpTransport.execute(requestBuilder.build());
        } catch (Exception e) {
            throw new McpTransportException("MCP HTTP request failed: " + e.getMessage(), e);
        }

        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 400) {
            if (isJsonRpcError(body)) {
                return body;
            }
            throw new McpTransportException(
                    "MCP HTTP server returned " + response.statusCode() + ": " + body);
        }
        return body;
    }

    private String selectResponse(String body, Object id) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new McpTransportException("Empty MCP HTTP response body");
        }
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        List<String> messages = parseSse(trimmed);
        if (messages.isEmpty()) {
            throw new McpTransportException("No JSON-RPC message found in MCP HTTP response");
        }
        for (String message : messages) {
            if (idMatches(message, id)) {
                return message;
            }
        }
        return messages.get(messages.size() - 1);
    }

    /** Extracts the concatenated {@code data:} payload of every SSE event in the body. */
    static List<String> parseSse(String body) {
        List<String> events = new ArrayList<String>();
        StringBuilder data = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (String rawLine : lines) {
            String line =
                    rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    events.add(data.toString());
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                String payload = line.substring("data:".length());
                if (payload.startsWith(" ")) {
                    payload = payload.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(payload);
            }
        }
        if (data.length() > 0) {
            events.add(data.toString());
        }
        return events;
    }

    private static boolean idMatches(String message, Object id) {
        try {
            Map<String, Object> parsed = Json.parseObject(message);
            Object messageId = parsed.get("id");
            if (id == null) {
                return parsed.containsKey("result") || parsed.containsKey("error");
            }
            return messageId != null && String.valueOf(messageId).equals(String.valueOf(id));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isJsonRpcError(String body) {
        if (body == null || !body.trim().startsWith("{")) {
            return false;
        }
        try {
            return Json.parseObject(body).get("error") != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Fluent builder for {@link StreamableHttpMcpTransport}. */
    public static final class Builder {

        private String endpoint;
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private HttpTransport httpTransport;
        private int connectTimeoutMillis = 10_000;
        private int readTimeoutMillis = 60_000;
        private String protocolVersion;

        private Builder() {}

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        public Builder connectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        public Builder readTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public StreamableHttpMcpTransport build() {
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalStateException("MCP endpoint must not be blank");
            }
            return new StreamableHttpMcpTransport(this);
        }
    }
}
