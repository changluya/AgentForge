package cloud.changlu.agentforge.agent.tool.mcp.support;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpContent;
import cloud.changlu.agentforge.model.internal.json.Json;

/**
 * Converts an MCP {@code tools/call} result into the text payload expected by AgentForge's ReAct
 * loop.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpResultConverter {

    private McpResultConverter() {}

    /**
     * Flattens all content blocks into a single text payload. Text blocks are concatenated with
     * newlines; non-text blocks are rendered as their raw JSON so no information is silently lost.
     * When there is no content at all, {@code structuredContent} is used as a fallback.
     *
     * @param result the MCP tool result, may be {@code null}
     * @return the text payload, never {@code null}
     */
    public static String toText(McpCallToolResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (McpContent content : result.content()) {
            String part = content.text();
            if (part == null && content.raw() != null && !content.raw().isEmpty()) {
                part = Json.stringify(content.raw());
            }
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(part);
        }
        if (builder.length() == 0 && result.structuredContent() != null) {
            builder.append(Json.stringify(result.structuredContent()));
        }
        return builder.toString();
    }
}
