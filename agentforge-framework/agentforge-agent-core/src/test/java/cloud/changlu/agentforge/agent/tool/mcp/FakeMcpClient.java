package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double for {@link McpClient} that returns canned tools/results without any transport.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class FakeMcpClient implements McpClient {

    private final String serverAlias;
    private final List<McpTool> tools;
    private final Map<String, McpCallToolResult> results;
    private RuntimeException callError;
    private String lastToolName;
    private Map<String, Object> lastArguments;
    private boolean closed;

    public FakeMcpClient(String serverAlias, List<McpTool> tools) {
        this.serverAlias = serverAlias;
        this.tools = tools == null ? Collections.<McpTool>emptyList() : tools;
        this.results = new LinkedHashMap<String, McpCallToolResult>();
    }

    public FakeMcpClient withResult(String toolName, McpCallToolResult result) {
        results.put(toolName, result);
        return this;
    }

    public FakeMcpClient withCallError(RuntimeException error) {
        this.callError = error;
        return this;
    }

    public String lastToolName() {
        return lastToolName;
    }

    public Map<String, Object> lastArguments() {
        return lastArguments;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String serverAlias() {
        return serverAlias;
    }

    @Override
    public List<McpTool> listTools() {
        return new ArrayList<McpTool>(tools);
    }

    @Override
    public McpCallToolResult callTool(String toolName, Map<String, Object> arguments) {
        this.lastToolName = toolName;
        this.lastArguments = arguments;
        if (callError != null) {
            throw callError;
        }
        McpCallToolResult result = results.get(toolName);
        if (result == null) {
            throw new McpProtocolException(-32601, "Unknown tool " + toolName);
        }
        return result;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
