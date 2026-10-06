package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.model.internal.json.Json;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author changlu @date 2026/10/05
 */
public class DefaultMcpClientTest {

    /** Emulates a spec-compliant legacy MCP server over an in-memory transport. */
    private static FakeMcpTransport server(final boolean failInitialize) {
        return new FakeMcpTransport(
                new FakeMcpTransport.Responder() {
                    @Override
                    public String respond(String request) {
                        Map<String, Object> message = Json.parseObject(request);
                        String method = Json.string(message.get("method"));
                        Object id = message.get("id");
                        if ("initialize".equals(method)) {
                            if (failInitialize) {
                                return error(id, -32601, "initialize unsupported");
                            }
                            return result(
                                    id,
                                    "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                                            + "\"serverInfo\":{\"name\":\"fs\",\"version\":\"1.0\"}}");
                        }
                        if ("tools/list".equals(method)) {
                            return result(
                                    id,
                                    "{\"tools\":[{\"name\":\"read_file\",\"description\":\"读文件\","
                                            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}]}");
                        }
                        if ("tools/call".equals(method)) {
                            return result(
                                    id,
                                    "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":false}");
                        }
                        return error(id, -32601, "unknown method " + method);
                    }
                });
    }

    private static String result(Object id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + Json.stringify(id)
                + ",\"result\":"
                + resultJson
                + "}";
    }

    private static String error(Object id, long code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":"
                + Json.stringify(id)
                + ",\"error\":{\"code\":"
                + code
                + ",\"message\":"
                + Json.stringify(message)
                + "}}";
    }

    @Test
    public void shouldHandshakeThenListToolsAndCallTool() {
        FakeMcpTransport transport = server(false);
        DefaultMcpClient client =
                DefaultMcpClient.builder().serverAlias("fs").transport(transport).build();

        List<McpTool> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("read_file", tools.get(0).name());
        assertTrue(tools.get(0).inputSchema().containsKey("properties"));

        McpCallToolResult callResult =
                client.callTool("read_file", Collections.singletonMap("path", "/tmp/a.txt"));
        assertFalse(callResult.isError());
        assertEquals("hello", callResult.content().get(0).text());

        // Handshake happened once, before the first business call.
        assertEquals(3, transport.requests().size());
        assertEquals(
                "initialize",
                Json.string(Json.parseObject(transport.requests().get(0)).get("method")));
        assertEquals(
                "tools/list",
                Json.string(Json.parseObject(transport.requests().get(1)).get("method")));
        assertEquals(
                "tools/call",
                Json.string(Json.parseObject(transport.requests().get(2)).get("method")));

        // The initialized notification is sent after the handshake.
        assertEquals(1, transport.notifications().size());
        assertEquals(
                "notifications/initialized",
                Json.string(Json.parseObject(transport.notifications().get(0)).get("method")));

        Map<String, Object> initializeParams =
                Json.object(Json.parseObject(transport.requests().get(0)).get("params"));
        assertEquals("2025-06-18", initializeParams.get("protocolVersion"));
    }

    @Test
    public void shouldSendClientInfoAndProtocolVersion() {
        FakeMcpTransport transport = server(false);
        DefaultMcpClient client =
                DefaultMcpClient.builder()
                        .serverAlias("fs")
                        .transport(transport)
                        .clientInfo("studio", "9.9.9")
                        .protocolVersion("2024-11-05")
                        .build();

        client.listTools();

        Map<String, Object> params =
                Json.object(Json.parseObject(transport.requests().get(0)).get("params"));
        assertEquals("2024-11-05", params.get("protocolVersion"));
        Map<String, Object> clientInfo = Json.object(params.get("clientInfo"));
        assertEquals("studio", clientInfo.get("name"));
        assertEquals("9.9.9", clientInfo.get("version"));
    }

    @Test
    public void shouldPropagateInitializeError() {
        DefaultMcpClient client =
                DefaultMcpClient.builder().serverAlias("fs").transport(server(true)).build();

        try {
            client.listTools();
            fail("expected McpProtocolException");
        } catch (McpProtocolException e) {
            assertEquals(-32601, e.code());
        }
    }

    @Test
    public void shouldCloseTransport() {
        FakeMcpTransport transport = server(false);
        DefaultMcpClient client = DefaultMcpClient.builder().transport(transport).build();

        client.close();

        assertTrue(transport.isClosed());
    }
}
