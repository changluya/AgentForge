package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpContent;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.execution.ToolExecutionResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author changlu @date 2026/10/05
 */
public class McpToolExecutorTest {

    private static McpCallToolResult textResult(String text, boolean isError) {
        McpContent content =
                new McpContent("text", text, null, Collections.<String, Object>emptyMap());
        return new McpCallToolResult(Collections.singletonList(content), null, isError);
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().name(name).arguments(arguments).build();
    }

    @Test
    public void shouldReturnTextFromMcpContent() {
        FakeMcpClient client =
                new FakeMcpClient("weather", Collections.<McpTool>emptyList())
                        .withResult("get_weather", textResult("杭州 26℃", false));
        McpToolExecutor executor = new McpToolExecutor(client, "get_weather");

        String text = executor.execute(request("get_weather", "{\"location\":\"杭州\"}"), null);

        assertEquals("杭州 26℃", text);
        assertEquals("get_weather", client.lastToolName());
        assertEquals("杭州", client.lastArguments().get("location"));
    }

    @Test
    public void shouldMapIsErrorAndStructuredContent() {
        Map<String, Object> structured = new LinkedHashMap<String, Object>();
        structured.put("ok", Boolean.FALSE);
        McpCallToolResult result =
                new McpCallToolResult(
                        Collections.singletonList(
                                new McpContent(
                                        "text",
                                        "boom",
                                        null,
                                        Collections.<String, Object>emptyMap())),
                        structured,
                        true);
        FakeMcpClient client =
                new FakeMcpClient("demo", Collections.<McpTool>emptyList())
                        .withResult("explode", result);
        McpToolExecutor executor = new McpToolExecutor(client, "explode");

        ToolExecutionResult executionResult =
                executor.executeWithResult(request("explode", "{}"), null);

        assertTrue(executionResult.isError());
        assertNotNull(executionResult.result());
        assertEquals("boom", executionResult.text());
    }

    @Test
    public void shouldConvertProtocolErrorToFailureResult() {
        FakeMcpClient client =
                new FakeMcpClient("demo", Collections.<McpTool>emptyList())
                        .withCallError(new McpProtocolException(-32601, "method not found"));
        McpToolExecutor executor = new McpToolExecutor(client, "missing");

        ToolExecutionResult result = executor.executeWithResult(request("missing", "{}"), null);

        assertTrue(result.isError());
        assertTrue(result.text().contains("method not found"));
    }

    @Test
    public void shouldConvertTransportErrorToFailureResult() {
        FakeMcpClient client =
                new FakeMcpClient("demo", Collections.<McpTool>emptyList())
                        .withCallError(new McpTransportException("connection reset"));
        McpToolExecutor executor = new McpToolExecutor(client, "flaky");

        ToolExecutionResult result = executor.executeWithResult(request("flaky", "{}"), null);

        assertTrue(result.isError());
        assertTrue(result.text().contains("connection reset"));
    }

    @Test
    public void shouldFallBackToStructuredContentWhenNoTextContent() {
        Map<String, Object> structured = new LinkedHashMap<String, Object>();
        structured.put("count", Long.valueOf(2));
        McpCallToolResult result =
                new McpCallToolResult(Arrays.<McpContent>asList(), structured, false);
        FakeMcpClient client =
                new FakeMcpClient("demo", Collections.<McpTool>emptyList())
                        .withResult("count", result);
        McpToolExecutor executor = new McpToolExecutor(client, "count");

        ToolExecutionResult executionResult =
                executor.executeWithResult(request("count", "{}"), null);

        assertFalse(executionResult.isError());
        assertTrue(executionResult.text().contains("count"));
    }
}
