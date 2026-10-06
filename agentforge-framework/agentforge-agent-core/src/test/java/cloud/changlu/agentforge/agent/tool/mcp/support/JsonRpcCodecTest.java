package cloud.changlu.agentforge.agent.tool.mcp.support;

import cloud.changlu.agentforge.agent.tool.mcp.McpProtocolException;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author changlu @date 2026/10/05
 */
public class JsonRpcCodecTest {

    @Test
    public void shouldEncodeRequestInJsonRpcShape() {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "get_weather");

        String message = JsonRpcCodec.encodeRequest(7L, "tools/call", params);

        assertEquals(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"get_weather\"}}",
                message);
    }

    @Test
    public void shouldEncodeNotificationWithoutIdOrParams() {
        String message = JsonRpcCodec.encodeNotification("notifications/initialized", null);

        assertEquals("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", message);
    }

    @Test
    public void shouldReadIdFromMessage() {
        // AgentForge's dependency-free Json decodes every number as a Number (Double), so compare
        // numerically rather than by exact boxed type.
        Object id = JsonRpcCodec.idOf("{\"id\":3,\"method\":\"x\"}");
        assertEquals(3L, ((Number) id).longValue());
    }

    @Test
    public void shouldReturnResultMap() {
        Map<String, Object> result =
                JsonRpcCodec.resultOf(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}", 1L);

        assertTrue(result.containsKey("tools"));
    }

    @Test
    public void shouldThrowProtocolExceptionOnErrorResponse() {
        try {
            JsonRpcCodec.resultOf(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32601,\"message\":\"nope\"}}",
                    1L);
            fail("expected McpProtocolException");
        } catch (McpProtocolException e) {
            assertEquals(-32601, e.code());
            assertEquals("nope", e.getMessage());
        }
    }
}
