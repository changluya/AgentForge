package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.support.McpJson;
import cloud.changlu.agentforge.agent.tool.mcp.support.McpResultConverter;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolExecutionResult;

import java.util.Map;

/**
 * Executes an MCP tool as if it were a local AgentForge tool. One instance is bound to a single
 * remote tool name on a single {@link McpClient}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpToolExecutor implements ToolExecutor {

    private final McpClient client;
    private final String remoteToolName;

    public McpToolExecutor(McpClient client, String remoteToolName) {
        this.client = client;
        this.remoteToolName = remoteToolName;
    }

    public McpClient client() {
        return client;
    }

    public String remoteToolName() {
        return remoteToolName;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        Map<String, Object> arguments = McpJson.argumentsAsMap(request.arguments());
        return McpResultConverter.toText(client.callTool(remoteToolName, arguments));
    }

    @Override
    public ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        try {
            McpCallToolResult result =
                    client.callTool(remoteToolName, McpJson.argumentsAsMap(request.arguments()));
            return ToolExecutionResult.builder()
                    .isError(result.isError())
                    .result(result.structuredContent())
                    .text(McpResultConverter.toText(result))
                    .build();
        } catch (McpProtocolException e) {
            return ToolExecutionResult.failure("MCP protocol error: " + e.getMessage(), e);
        } catch (McpTransportException e) {
            return ToolExecutionResult.failure("MCP transport error: " + e.getMessage(), e);
        }
    }
}
