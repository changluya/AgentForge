package cloud.changlu.agentforge.agent.tool.mcp;

import cloud.changlu.agentforge.agent.tool.mcp.domain.McpCallToolResult;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpContent;
import cloud.changlu.agentforge.agent.tool.mcp.domain.McpTool;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StdioMcpTransport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Live test against a real, out-of-the-box public MCP server: the official {@code
 * @modelcontextprotocol/server-filesystem} started through {@code npx}.
 *
 * <p>Disabled by default (it needs Node/npx and network to fetch the package). Run with:
 *
 * <pre>mvn test -Dagentforge.mcp.live=true -Dtest=McpStdioLiveTest</pre>
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpStdioLiveTest {

    private static final String ENABLED_PROPERTY = "agentforge.mcp.live";

    @Test
    public void shouldTalkToRealFilesystemMcpServer() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"))) {
            System.out.println(
                    "Skip McpStdioLiveTest: set -D"
                            + ENABLED_PROPERTY
                            + "=true (requires npx + network).");
            return;
        }

        Path directory = Files.createTempDirectory("agentforge-mcp-live");
        Path file = directory.resolve("hello.txt");

        StdioMcpTransport transport =
                StdioMcpTransport.builder()
                        .command(
                                "npx",
                                "-y",
                                "@modelcontextprotocol/server-filesystem",
                                directory.toAbsolutePath().toString())
                        .build();
        DefaultMcpClient client =
                DefaultMcpClient.builder().serverAlias("fs").transport(transport).build();
        try {
            List<McpTool> tools = client.listTools();
            Set<String> names = new HashSet<String>();
            for (McpTool tool : tools) {
                names.add(tool.name());
            }
            assertTrue("filesystem server should expose read_file", names.contains("read_file"));
            assertTrue("filesystem server should expose write_file", names.contains("write_file"));

            client.callTool(
                    "write_file", arguments("path", file.toString(), "content", "hello mcp"));
            McpCallToolResult read =
                    client.callTool("read_file", arguments("path", file.toString()));
            assertFalse(read.isError());
            assertTrue(textOf(read).contains("hello mcp"));

            // Studio scenario: register the discovered tools into the shared ToolService.
            ToolService toolService = new ToolService();
            toolService.tools(McpToolFactory.buildTools(client, "fs"));
            assertNotNull(toolService.toolExecutors().get("fs__read_file"));

            String text =
                    toolService
                            .toolExecutors()
                            .get("fs__read_file")
                            .execute(
                                    ToolExecutionRequest.builder()
                                            .name("fs__read_file")
                                            .arguments(
                                                    "{\"path\":"
                                                            + Json.stringify(file.toString())
                                                            + "}")
                                            .build(),
                                    null);
            assertTrue(text.contains("hello mcp"));
        } finally {
            client.close();
        }
    }

    private static Map<String, Object> arguments(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static Map<String, Object> arguments(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private static String textOf(McpCallToolResult result) {
        StringBuilder builder = new StringBuilder();
        for (McpContent content : result.content()) {
            if (content.text() != null) {
                builder.append(content.text());
            }
        }
        return builder.toString();
    }
}
