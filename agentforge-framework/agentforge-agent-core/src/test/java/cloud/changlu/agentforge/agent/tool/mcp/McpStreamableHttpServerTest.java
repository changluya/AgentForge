package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * End-to-end test over a real local HTTP server: real {@code JdkHttpTransport} + real sockets +
 * {@code StreamableHttpMcpTransport}, then wired into {@code ToolService} exactly like the Studio
 * example does.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpStreamableHttpServerTest {

    @Test
    public void shouldDiscoverAndCallToolsOverRealHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", new FakeMcpHttpHandler());
        server.start();
        int port = server.getAddress().getPort();

        DefaultMcpClient client =
                DefaultMcpClient.builder()
                        .serverAlias("remote")
                        .transport(
                                StreamableHttpMcpTransport.builder()
                                        .endpoint("http://127.0.0.1:" + port + "/mcp")
                                        .build())
                        .build();
        try {
            List<McpTool> tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals("get_weather", tools.get(0).name());

            McpCallToolResult callResult =
                    client.callTool(
                            "get_weather",
                            Collections.<String, Object>singletonMap("location", "杭州"));
            assertFalse(callResult.isError());
            assertEquals("杭州 26℃", callResult.content().get(0).text());

            // Studio-like wiring: MCP tools join the same ToolService as local/http tools.
            ToolService toolService = new ToolService();
            toolService.tools(McpToolFactory.buildTools(client, "remote"));
            assertEquals("remote__get_weather", toolService.toolSpecifications().get(0).name());

            ToolExecutor executor = toolService.toolExecutors().get("remote__get_weather");
            String text =
                    executor.execute(
                            ToolExecutionRequest.builder()
                                    .name("remote__get_weather")
                                    .arguments("{\"location\":\"杭州\"}")
                                    .build(),
                            null);
            assertEquals("杭州 26℃", text);
        } finally {
            client.close();
            server.stop(0);
        }
    }

    /**
     * A minimal MCP server: JSON for {@code initialize}/{@code tools/call} and an SSE stream for
     * {@code tools/list}, so both response shapes are exercised.
     */
    private static final class FakeMcpHttpHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = readBody(exchange.getRequestBody());
            Map<String, Object> request = Json.parseObject(body);
            String method = Json.string(request.get("method"));
            Object id = request.get("id");

            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if ("initialize".equals(method)) {
                writeJson(
                        exchange,
                        envelope(
                                id,
                                "\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                                        + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1.0\"}}"));
                return;
            }
            if ("tools/list".equals(method)) {
                writeSse(
                        exchange,
                        envelope(
                                id,
                                "\"result\":{\"tools\":[{\"name\":\"get_weather\",\"description\":\"天气\","
                                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\"}}}}]}"));
                return;
            }
            if ("tools/call".equals(method)) {
                writeJson(
                        exchange,
                        envelope(
                                id,
                                "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"杭州 26℃\"}],\"isError\":false}"));
                return;
            }
            writeJson(
                    exchange,
                    "{\"jsonrpc\":\"2.0\",\"id\":"
                            + Json.stringify(id)
                            + ",\"error\":{\"code\":-32601,\"message\":\"unknown method\"}}");
        }

        private static String envelope(Object id, String body) {
            return "{\"jsonrpc\":\"2.0\",\"id\":" + Json.stringify(id) + "," + body + "}";
        }

        private static void writeJson(HttpExchange exchange, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        }

        private static void writeSse(HttpExchange exchange, String json) throws IOException {
            byte[] bytes =
                    ("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        }

        private static String readBody(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
