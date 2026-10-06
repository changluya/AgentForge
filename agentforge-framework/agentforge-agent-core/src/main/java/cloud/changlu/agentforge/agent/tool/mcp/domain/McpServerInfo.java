package cloud.changlu.agentforge.agent.tool.mcp.domain;

/**
 * Server identity returned by the MCP {@code initialize} handshake.
 *
 * @author changlu
 * @date 2026/10/05
 */
public final class McpServerInfo {

    private final String name;
    private final String version;

    public McpServerInfo(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    @Override
    public String toString() {
        return "McpServerInfo{" + "name='" + name + '\'' + ", version='" + version + '\'' + '}';
    }
}
