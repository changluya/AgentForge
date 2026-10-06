package cloud.changlu.agentforge.agent.tool.mcp.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of an MCP {@code tools/call} request.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpCallToolResult {

    private final List<McpContent> content;
    private final Object structuredContent;
    private final boolean isError;

    public McpCallToolResult(List<McpContent> content, Object structuredContent, boolean isError) {
        this.content =
                content == null
                        ? Collections.<McpContent>emptyList()
                        : Collections.unmodifiableList(new ArrayList<McpContent>(content));
        this.structuredContent = structuredContent;
        this.isError = isError;
    }

    public List<McpContent> content() {
        return content;
    }

    public Object structuredContent() {
        return structuredContent;
    }

    public boolean isError() {
        return isError;
    }

    @Override
    public String toString() {
        return "McpCallToolResult{"
                + "content="
                + content
                + ", structuredContent="
                + structuredContent
                + ", isError="
                + isError
                + '}';
    }
}
