package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.transport.McpTransport;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StdioMcpTransport;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Live smoke test across several real, public MCP servers (stdio via npx, and HTTP endpoints). Each
 * server is best-effort: failures are printed but do not fail the run unless every server of a
 * transport kind is unreachable.
 *
 * <p>Disabled by default. Run with:
 *
 * <pre>mvn test -Dagentforge.mcp.live=true -Dtest=McpPublicServersLiveTest</pre>
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpPublicServersLiveTest {

    private static final String ENABLED_PROPERTY = "agentforge.mcp.live";

    @Test
    public void shouldConnectToPublicStdioServers() throws Exception {
        if (!enabled()) {
            return;
        }
        Path directory = Files.createTempDirectory("agentforge-mcp-public");
        List<String> ok = new ArrayList<String>();
        ok.addAll(
                probeStdio(
                        "filesystem",
                        StdioMcpTransport.of(
                                "npx",
                                "-y",
                                "@modelcontextprotocol/server-filesystem",
                                directory.toAbsolutePath().toString())));
        ok.addAll(
                probeStdio(
                        "memory",
                        StdioMcpTransport.of("npx", "-y", "@modelcontextprotocol/server-memory")));
        ok.addAll(
                probeStdio(
                        "sequential-thinking",
                        StdioMcpTransport.of(
                                "npx", "-y", "@modelcontextprotocol/server-sequential-thinking")));
        ok.addAll(
                probeStdio(
                        "everything",
                        StdioMcpTransport.of(
                                "npx", "-y", "@modelcontextprotocol/server-everything")));
        assertTrue("at least one public stdio MCP server should be reachable", !ok.isEmpty());
    }

    @Test
    public void shouldConnectToPublicHttpServers() {
        if (!enabled()) {
            return;
        }
        List<String> ok = new ArrayList<String>();
        ok.addAll(probeHttp("deepwiki", "https://mcp.deepwiki.com/mcp"));
        ok.addAll(probeHttp("context7", "https://mcp.context7.com/mcp"));
        ok.addAll(probeHttp("gitmcp", "https://gitmcp.io/modelcontextprotocol/servers"));
        assertTrue("at least one public HTTP MCP server should be reachable", !ok.isEmpty());
    }

    private static List<String> probeStdio(String name, McpTransport transport) {
        return probe(name, transport);
    }

    private static List<String> probeHttp(String name, String endpoint) {
        return probe(name, StreamableHttpMcpTransport.builder().endpoint(endpoint).build());
    }

    private static List<String> probe(String name, McpTransport transport) {
        DefaultMcpClient client =
                DefaultMcpClient.builder().serverAlias(name).transport(transport).build();
        try {
            List<McpTool> tools = client.listTools();
            List<String> names = new ArrayList<String>();
            for (McpTool tool : tools) {
                names.add(tool.name());
            }
            System.out.println("[MCP-LIVE] " + name + " OK, tools=" + names.size() + " " + names);
            return names;
        } catch (Throwable error) {
            System.out.println("[MCP-LIVE] " + name + " FAILED: " + error);
            return new ArrayList<String>();
        } finally {
            client.close();
        }
    }

    private static boolean enabled() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
            System.out.println(
                    "Skip McpPublicServersLiveTest: set -D" + ENABLED_PROPERTY + "=true.");
            return false;
        }
        return true;
    }
}
