package cloud.changlu.agentforge.agent.tool.mcp.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single content block of a {@code tools/call} result.
 *
 * <p>MCP defines {@code text}, {@code image}, {@code audio}, {@code resource} and {@code
 * resource_link} blocks. AgentForge keeps the original block in {@link #raw()} so non-text blocks
 * are not lost even though the current core only carries a text payload to the model.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpContent {

    private final String type;
    private final String text;
    private final String mimeType;
    private final Map<String, Object> raw;

    public McpContent(String type, String text, String mimeType, Map<String, Object> raw) {
        this.type = type;
        this.text = text;
        this.mimeType = mimeType;
        this.raw =
                raw == null
                        ? Collections.<String, Object>emptyMap()
                        : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(raw));
    }

    public String type() {
        return type;
    }

    public String text() {
        return text;
    }

    public String mimeType() {
        return mimeType;
    }

    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "McpContent{" + "type='" + type + '\'' + ", text='" + text + '\'' + '}';
    }
}
