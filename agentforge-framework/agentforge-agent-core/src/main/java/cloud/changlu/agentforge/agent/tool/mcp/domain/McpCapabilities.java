package cloud.changlu.agentforge.agent.tool.mcp.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Capabilities advertised by an MCP server during {@code initialize}.
 *
 * <p>The current core only needs to know which top-level capability buckets exist ({@code tools},
 * {@code resources}, {@code prompts}, ...), so the raw map is kept as-is.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpCapabilities {

    private final Map<String, Object> raw;

    public McpCapabilities(Map<String, Object> raw) {
        this.raw =
                raw == null
                        ? Collections.<String, Object>emptyMap()
                        : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(raw));
    }

    public boolean has(String capability) {
        return capability != null && raw.containsKey(capability);
    }

    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "McpCapabilities{" + raw + '}';
    }
}
