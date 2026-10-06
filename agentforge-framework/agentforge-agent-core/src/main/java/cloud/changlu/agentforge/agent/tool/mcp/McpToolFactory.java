package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.support.McpToolSpecificationMapper;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns the tools discovered through {@code tools/list} into AgentForge's {@code
 * Map<ToolSpecification, ToolExecutor>}, mirroring {@code HttpToolFactory} / {@code
 * LocalToolFactory}.
 *
 * <p>Tool names are namespaced as {@code serverAlias__toolName} so tools from different MCP servers
 * can share one {@code ToolService} without collisions. The alias defaults to {@link
 * McpClient#serverAlias()} and can be overridden per call.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpToolFactory {

    static final String ALIAS_SEPARATOR = "__";

    private McpToolFactory() {}

    /**
     * Builds the tool map using the client's own {@link McpClient#serverAlias()} as the prefix.
     *
     * @param client the MCP client to discover and call tools on
     * @return the discovered tools keyed by specification
     */
    public static Map<ToolSpecification, ToolExecutor> buildTools(McpClient client) {
        return buildTools(client, null, null, null);
    }

    /**
     * Builds the tool map, preferring the explicit alias over {@link McpClient#serverAlias()}.
     *
     * @param client the MCP client to discover and call tools on
     * @param serverAlias the alias used to prefix tool names; when blank, {@link
     *     McpClient#serverAlias()} is used
     * @return the discovered tools keyed by specification
     */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client, String serverAlias) {
        return buildTools(client, serverAlias, null, null);
    }

    /**
     * Builds the tool map with optional name filtering.
     *
     * @param client the MCP client to discover and call tools on
     * @param serverAlias the alias used to prefix tool names; when blank, {@link
     *     McpClient#serverAlias()} is used
     * @param enabledTools when non-empty, only these remote tool names are exposed
     * @param disabledTools when non-null, these remote tool names are hidden
     * @return the discovered tools keyed by specification
     */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client,
            String serverAlias,
            Set<String> enabledTools,
            Set<String> disabledTools) {
        String alias = resolveAlias(client, serverAlias);
        Map<ToolSpecification, ToolExecutor> result =
                new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (McpTool tool : client.listTools()) {
            if (enabledTools != null
                    && !enabledTools.isEmpty()
                    && !enabledTools.contains(tool.name())) {
                continue;
            }
            if (disabledTools != null && disabledTools.contains(tool.name())) {
                continue;
            }

            ToolSpecification specification =
                    ToolSpecification.builder()
                            .name(qualify(alias, tool.name()))
                            .description(tool.description())
                            .parameters(
                                    McpToolSpecificationMapper.toToolParameters(tool.inputSchema()))
                            .addMetadata("mcp.server", alias == null ? "" : alias)
                            .addMetadata("mcp.tool", tool.name())
                            .build();
            result.put(specification, new McpToolExecutor(client, tool.name()));
        }
        return result;
    }

    private static String resolveAlias(McpClient client, String serverAlias) {
        if (serverAlias != null && !serverAlias.trim().isEmpty()) {
            return serverAlias;
        }
        return client == null ? null : client.serverAlias();
    }

    static String qualify(String alias, String toolName) {
        if (alias == null || alias.trim().isEmpty()) {
            return toolName;
        }
        return alias + ALIAS_SEPARATOR + toolName;
    }
}
