package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;

/**
 * Live test against a real, public Streamable HTTP MCP server (defaults to DeepWiki, no auth
 * required).
 *
 * <p>Disabled by default. Run with:
 *
 * <pre>mvn test -Dagentforge.mcp.live=true -Dtest=McpHttpLiveTest</pre>
 *
 * Override the endpoint with {@code -Dagentforge.mcp.http.endpoint=...}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpHttpLiveTest {

    private static final String ENABLED_PROPERTY = "agentforge.mcp.live";
    private static final String ENDPOINT_PROPERTY = "agentforge.mcp.http.endpoint";
    private static final String DEFAULT_ENDPOINT = "https://mcp.deepwiki.com/mcp";

    @Test
    public void shouldListToolsFromPublicHttpServer() {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
            System.out.println(
                    "Skip McpHttpLiveTest: set -D"
                            + ENABLED_PROPERTY
                            + "=true (requires network).");
            return;
        }

        String endpoint = System.getProperty(ENDPOINT_PROPERTY, DEFAULT_ENDPOINT);
        DefaultMcpClient client =
                DefaultMcpClient.builder()
                        .serverAlias("remote")
                        .transport(StreamableHttpMcpTransport.builder().endpoint(endpoint).build())
                        .build();
        try {
            List<McpTool> tools = client.listTools();
            System.out.println(
                    "Public MCP server " + endpoint + " exposed " + tools.size() + " tools");
            assertFalse("public MCP server should expose at least one tool", tools.isEmpty());
        } finally {
            client.close();
        }
    }
}
