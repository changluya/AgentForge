package cloud.changlu.agentforge.agent.tool.mcp.real;

import cloud.changlu.agentforge.agent.tool.mcp.DefaultMcpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpToolFactory;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StreamableHttpMcpTransport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolExecutionResult;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Real-application test for the doc's <b>HTTP</b> scenario (section 5.1.2): a real public
 * Streamable HTTP MCP server discovered and invoked through a {@code ToolService}, exactly like
 * Studio.
 *
 * <p>Runs by default when the public endpoint is reachable; disable with {@code
 * -Dagentforge.mcp.live=false}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpRealHttpTest {

    private static final String ENDPOINT = "https://mcp.deepwiki.com/mcp";
    private static final String HOST = "mcp.deepwiki.com";
    private static final int PORT = 443;

    /**
     * 文档 5.1.2 的 HTTP 场景真实应用验证。
     *
     * <p>连接公开的 DeepWiki Streamable HTTP MCP Server，通过 {@code McpToolFactory} 把其工具注册进 {@code
     * ToolService}，断言：
     *
     * <ol>
     *   <li>发现到 {@code wiki__read_wiki_structure} / {@code wiki__read_wiki_contents} / {@code
     *       wiki__ask_wiki_question} 三个工具；
     *   <li>真实调用 {@code wiki__read_wiki_structure(facebook/react)} 成功且返回非空内容。
     * </ol>
     *
     * <p>依赖到 {@code mcp.deepwiki.com:443} 的网络；用 {@code -Dagentforge.mcp.live=false} 可跳过。
     */
    @Test
    public void shouldWireRealHttpMcpIntoToolServiceAndCallTool() {
        assumeLive();
        assumeTcpReachable(HOST, PORT);

        McpClient client =
                DefaultMcpClient.builder()
                        .serverAlias("wiki")
                        .transport(StreamableHttpMcpTransport.builder().endpoint(ENDPOINT).build())
                        .build();
        try {
            ToolService toolService = new ToolService();
            toolService.tools(McpToolFactory.buildTools(client, "wiki"));

            // Discovery: deepwiki exposes these three tools behind the wiki__ prefix.
            assertTrue(toolService.toolExecutors().containsKey("wiki__read_wiki_structure"));
            assertTrue(toolService.toolExecutors().containsKey("wiki__read_wiki_contents"));
            assertTrue(toolService.toolExecutors().containsKey("wiki__ask_wiki_question"));

            // Invocation: really call a public tool through the unified ToolService.
            ToolExecutor readStructure =
                    toolService.toolExecutors().get("wiki__read_wiki_structure");
            ToolExecutionResult result =
                    readStructure.executeWithResult(
                            ToolExecutionRequest.builder()
                                    .name("wiki__read_wiki_structure")
                                    .arguments("{\"repoName\":\"facebook/react\"}")
                                    .build(),
                            null);

            assertFalse("real call should not be an error", result.isError());
            assertNotNull(result.text());
            assertFalse("real call should return content", result.text().trim().isEmpty());
            System.out.println(
                    "[MCP-REAL-HTTP] wiki__read_wiki_structure(facebook/react) -> "
                            + shorten(result.text()));
        } finally {
            client.close();
        }
    }

    /** HTTP 场景轻量验证：MCP 工具能被规范化为模型可见的 {@code ToolSpecification}（不做真实调用）。 */
    @Test
    public void shouldExposeToolSpecificationsToTheModel() {
        assumeLive();
        assumeTcpReachable(HOST, PORT);

        McpClient client =
                DefaultMcpClient.builder()
                        .serverAlias("wiki")
                        .transport(StreamableHttpMcpTransport.builder().endpoint(ENDPOINT).build())
                        .build();
        try {
            Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "wiki");
            assertTrue(tools.size() >= 3);
        } finally {
            client.close();
        }
    }

    private static String shorten(String text) {
        String singleLine = text.replace('\n', ' ');
        return singleLine.length() > 120 ? singleLine.substring(0, 120) + "..." : singleLine;
    }

    private static void assumeLive() {
        Assume.assumeTrue(
                "set -Dagentforge.mcp.live=false to skip",
                Boolean.parseBoolean(System.getProperty("agentforge.mcp.live", "true")));
    }

    private static void assumeTcpReachable(String host, int port) {
        try {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 5000);
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            Assume.assumeNoException("no network to " + host + ":" + port, e);
        }
    }
}
