package cloud.changlu.agentforge.agent.tool.mcp.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tool advertised by an MCP server through {@code tools/list}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpTool {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpTool(String name, String description, Map<String, Object> inputSchema) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.inputSchema =
                inputSchema == null
                        ? Collections.<String, Object>emptyMap()
                        : Collections.unmodifiableMap(
                                new LinkedHashMap<String, Object>(inputSchema));
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * @return the raw JSON Schema describing the tool arguments, never {@code null}
     */
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }

    @Override
    public String toString() {
        return "McpTool{" + "name='" + name + '\'' + ", description='" + description + '\'' + '}';
    }
}
