package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCapabilities;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpContent;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpServerInfo;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.support.JsonRpcCodec;
import cloud.changlu.agentforge.agent.tool.mcp.transport.McpTransport;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link McpClient}: performs the {@code initialize} handshake lazily and then speaks
 * {@code tools/list} / {@code tools/call} over a pluggable {@link McpTransport}.
 *
 * <p>P0 scope: legacy {@code initialize} handshake only. The modern {@code server/discover} era
 * probe is a P1 item; the code is structured so it can be added without touching callers.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class DefaultMcpClient implements McpClient {

    /** Latest spec revision understood by the legacy handshake implemented here. */
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private static final String DEFAULT_CLIENT_NAME = "agentforge";
    private static final String DEFAULT_CLIENT_VERSION = "1.0.0";

    private final String serverAlias;
    private final McpTransport transport;
    private final String clientName;
    private final String clientVersion;

    private final AtomicLong idSequence = new AtomicLong();
    private final Object initLock = new Object();

    private volatile boolean initialized;
    private volatile String protocolVersion;
    private volatile McpServerInfo serverInfo;
    private volatile McpCapabilities capabilities;

    private DefaultMcpClient(Builder builder) {
        this.serverAlias = builder.serverAlias;
        this.transport = builder.transport;
        this.clientName = builder.clientName;
        this.clientVersion = builder.clientVersion;
        this.protocolVersion = builder.protocolVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String serverAlias() {
        return serverAlias;
    }

    public McpServerInfo serverInfo() {
        ensureInitialized();
        return serverInfo;
    }

    public McpCapabilities capabilities() {
        ensureInitialized();
        return capabilities;
    }

    public String protocolVersion() {
        ensureInitialized();
        return protocolVersion;
    }

    @Override
    public List<McpTool> listTools() {
        ensureInitialized();
        Map<String, Object> result = request("tools/list", null);
        List<Object> rawTools = Json.array(result.get("tools"));
        List<McpTool> tools = new ArrayList<McpTool>();
        if (rawTools != null) {
            for (Object rawTool : rawTools) {
                Map<String, Object> tool = Json.object(rawTool);
                if (tool == null) {
                    continue;
                }
                String name = Json.string(tool.get("name"));
                if (name == null || name.isEmpty()) {
                    continue;
                }
                tools.add(
                        new McpTool(
                                name,
                                Json.string(tool.get("description")),
                                Json.object(tool.get("inputSchema"))));
            }
        }
        return tools;
    }

    @Override
    public McpCallToolResult callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put(
                "arguments",
                arguments == null ? Collections.<String, Object>emptyMap() : arguments);
        Map<String, Object> result = request("tools/call", params);
        return parseCallToolResult(result);
    }

    @Override
    public void close() {
        transport.close();
        initialized = false;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (initialized) {
                return;
            }
            initialize();
            initialized = true;
        }
    }

    private void initialize() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", protocolVersion);
        params.put("capabilities", new LinkedHashMap<String, Object>());
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", clientName);
        clientInfo.put("version", clientVersion);
        params.put("clientInfo", clientInfo);

        Map<String, Object> result = request("initialize", params);

        String negotiated = Json.string(result.get("protocolVersion"));
        if (negotiated != null && !negotiated.isEmpty()) {
            this.protocolVersion = negotiated;
        }
        Map<String, Object> info = Json.object(result.get("serverInfo"));
        if (info != null) {
            this.serverInfo =
                    new McpServerInfo(
                            Json.string(info.get("name")), Json.string(info.get("version")));
        }
        this.capabilities = new McpCapabilities(Json.object(result.get("capabilities")));

        if (transport instanceof StreamableHttpMcpTransport) {
            ((StreamableHttpMcpTransport) transport).setProtocolVersion(this.protocolVersion);
        }

        sendNotification("notifications/initialized");
    }

    private Map<String, Object> request(String method, Map<String, Object> params) {
        long id = idSequence.incrementAndGet();
        String message = JsonRpcCodec.encodeRequest(id, method, params);
        String response;
        try {
            response = transport.request(message);
        } catch (IOException e) {
            throw new McpTransportException(
                    "MCP request '" + method + "' failed: " + e.getMessage(), e);
        }
        return JsonRpcCodec.resultOf(response, Long.valueOf(id));
    }

    private void sendNotification(String method) {
        try {
            transport.send(JsonRpcCodec.encodeNotification(method, null));
        } catch (IOException e) {
            throw new McpTransportException("MCP notification '" + method + "' failed", e);
        }
    }

    private static McpCallToolResult parseCallToolResult(Map<String, Object> result) {
        List<McpContent> contents = new ArrayList<McpContent>();
        List<Object> rawContent = Json.array(result.get("content"));
        if (rawContent != null) {
            for (Object item : rawContent) {
                Map<String, Object> block = Json.object(item);
                if (block == null) {
                    continue;
                }
                contents.add(
                        new McpContent(
                                Json.string(block.get("type")),
                                Json.string(block.get("text")),
                                Json.string(block.get("mimeType")),
                                block));
            }
        }
        boolean isError = Boolean.TRUE.equals(result.get("isError"));
        return new McpCallToolResult(contents, result.get("structuredContent"), isError);
    }

    /** Fluent builder for {@link DefaultMcpClient}. */
    public static final class Builder {

        private String serverAlias;
        private McpTransport transport;
        private String protocolVersion = DEFAULT_PROTOCOL_VERSION;
        private String clientName = DEFAULT_CLIENT_NAME;
        private String clientVersion = DEFAULT_CLIENT_VERSION;

        private Builder() {}

        public Builder serverAlias(String serverAlias) {
            this.serverAlias = serverAlias;
            return this;
        }

        public Builder transport(McpTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder protocolVersion(String protocolVersion) {
            if (protocolVersion != null && !protocolVersion.trim().isEmpty()) {
                this.protocolVersion = protocolVersion;
            }
            return this;
        }

        public Builder clientInfo(String name, String version) {
            if (name != null && !name.trim().isEmpty()) {
                this.clientName = name;
            }
            if (version != null && !version.trim().isEmpty()) {
                this.clientVersion = version;
            }
            return this;
        }

        public DefaultMcpClient build() {
            if (transport == null) {
                throw new IllegalStateException("transport must not be null");
            }
            return new DefaultMcpClient(this);
        }
    }
}
