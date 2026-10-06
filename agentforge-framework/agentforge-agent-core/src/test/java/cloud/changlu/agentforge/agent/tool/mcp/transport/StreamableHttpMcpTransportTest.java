package cloud.changlu.agentforge.agent.tool.mcp.transport;

import cloud.changlu.agentforge.agent.tool.mcp.McpProtocolException;
import cloud.changlu.agentforge.agent.tool.mcp.McpTransportException;
import cloud.changlu.agentforge.agent.tool.mcp.support.JsonRpcCodec;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author changlu @date 2026/10/05
 */
public class StreamableHttpMcpTransportTest {

    private static final String REQUEST = JsonRpcCodec.encodeRequest(1L, "tools/list", null);

    private static final class RecordingHttpTransport implements HttpTransport {

        private HttpRequest lastRequest;
        private HttpResponse response;

        RecordingHttpTransport(HttpResponse response) {
            this.response = response;
        }

        @Override
        public HttpResponse execute(HttpRequest request) throws IOException {
            this.lastRequest = request;
            return response;
        }
    }

    @Test
    public void shouldReturnPlainJsonResponse() {
        RecordingHttpTransport http =
                new RecordingHttpTransport(
                        new HttpResponse(
                                200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}"));
        StreamableHttpMcpTransport transport =
                StreamableHttpMcpTransport.builder()
                        .endpoint("http://localhost/mcp")
                        .httpTransport(http)
                        .build();

        Map<String, Object> result = JsonRpcCodec.resultOf(transport.request(REQUEST), 1L);

        assertTrue(result.containsKey("tools"));
    }

    @Test
    public void shouldSelectMatchingMessageFromSseStream() {
        String sse =
                "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"x\"}]}}\n"
                        + "\n";
        RecordingHttpTransport http = new RecordingHttpTransport(new HttpResponse(200, sse));
        StreamableHttpMcpTransport transport =
                StreamableHttpMcpTransport.builder()
                        .endpoint("http://localhost/mcp")
                        .httpTransport(http)
                        .build();

        Map<String, Object> result = JsonRpcCodec.resultOf(transport.request(REQUEST), 1L);

        assertTrue(result.containsKey("tools"));
    }

    @Test
    public void shouldSendMcpHeadersAndVersion() {
        RecordingHttpTransport http =
                new RecordingHttpTransport(new HttpResponse(200, "{\"id\":1,\"result\":{}}"));
        StreamableHttpMcpTransport transport =
                StreamableHttpMcpTransport.builder()
                        .endpoint("http://localhost/mcp")
                        .header("Authorization", "Bearer token")
                        .httpTransport(http)
                        .build();
        transport.setProtocolVersion("2025-06-18");

        transport.request(REQUEST);

        Map<String, String> headers = http.lastRequest.headers();
        assertEquals("application/json", headers.get("Content-Type"));
        assertTrue(headers.get("Accept").contains("text/event-stream"));
        assertEquals("2025-06-18", headers.get("MCP-Protocol-Version"));
        assertEquals("Bearer token", headers.get("Authorization"));
    }

    @Test
    public void shouldThrowWhenHttpErrorHasNoJsonRpcError() {
        RecordingHttpTransport http = new RecordingHttpTransport(new HttpResponse(500, "boom"));
        StreamableHttpMcpTransport transport =
                StreamableHttpMcpTransport.builder()
                        .endpoint("http://localhost/mcp")
                        .httpTransport(http)
                        .build();

        try {
            transport.request(REQUEST);
            fail("expected McpTransportException");
        } catch (McpTransportException e) {
            assertTrue(e.getMessage().contains("500"));
        }
    }

    @Test
    public void shouldSurfaceJsonRpcErrorBodyForHttp400() {
        RecordingHttpTransport http =
                new RecordingHttpTransport(
                        new HttpResponse(
                                400,
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32022,\"message\":\"bad version\"}}"));
        StreamableHttpMcpTransport transport =
                StreamableHttpMcpTransport.builder()
                        .endpoint("http://localhost/mcp")
                        .httpTransport(http)
                        .build();

        try {
            JsonRpcCodec.resultOf(transport.request(REQUEST), 1L);
            fail("expected McpProtocolException");
        } catch (McpProtocolException e) {
            assertEquals(-32022, e.code());
        }
    }

    @Test
    public void shouldParseSseDataPayloads() {
        List<String> events =
                StreamableHttpMcpTransport.parseSse(
                        ": ping\nevent: message\ndata: {\"a\":1}\ndata: {\"b\":2}\n\n");

        assertEquals(1, events.size());
        assertEquals("{\"a\":1}\n{\"b\":2}", events.get(0));
    }
}
