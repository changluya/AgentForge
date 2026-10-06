package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;

import java.io.Closeable;
import java.util.List;
import java.util.Map;

/**
 * Protocol-level MCP client used by {@code McpToolFactory} / {@code McpToolExecutor}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public interface McpClient extends Closeable {

    /**
     * @return the alias used to qualify tool names, or {@code null} for none
     */
    String serverAlias();

    /**
     * Lists the tools advertised by the server ({@code tools/list}).
     *
     * @return the advertised tools, never {@code null}
     */
    List<McpTool> listTools();

    /**
     * Invokes a tool on the server ({@code tools/call}).
     *
     * @param toolName the server-side tool name (without any AgentForge alias prefix)
     * @param arguments the tool arguments, may be {@code null}
     * @return the MCP tool result
     */
    McpCallToolResult callTool(String toolName, Map<String, Object> arguments);

    @Override
    void close();
}
