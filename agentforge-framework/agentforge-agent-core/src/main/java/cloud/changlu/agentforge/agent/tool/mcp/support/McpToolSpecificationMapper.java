package cloud.changlu.agentforge.agent.tool.mcp.support;

import cloud.changlu.agentforge.model.tool.spec.ToolParameters;

import java.util.Map;

/**
 * Maps an MCP {@code inputSchema} to AgentForge's {@link ToolParameters} without loss.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpToolSpecificationMapper {

    private McpToolSpecificationMapper() {}

    /**
     * Passes the MCP JSON Schema through as-is. MCP already speaks JSON Schema, so no field needs
     * to be rewritten.
     *
     * @param inputSchema the MCP tool input schema, may be {@code null} or empty
     * @return the AgentForge tool parameters
     */
    public static ToolParameters toToolParameters(Map<String, Object> inputSchema) {
        if (inputSchema == null || inputSchema.isEmpty()) {
            return ToolParameters.empty();
        }
        return ToolParameters.from(inputSchema);
    }
}
