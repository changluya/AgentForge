package cloud.changlu.agentforge.agent.tool.mcp.real;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentRunState;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.memory.ChatMemory;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.step.ChatResult;
import cloud.changlu.agentforge.agent.support.ScriptedChatModel;
import cloud.changlu.agentforge.agent.support.WeatherTools;
import cloud.changlu.agentforge.agent.tool.mcp.DefaultMcpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpClient;
import cloud.changlu.agentforge.agent.tool.mcp.McpToolFactory;
import cloud.changlu.agentforge.agent.tool.mcp.transport.StdioMcpTransport;
import cloud.changlu.agentforge.model.chat.message.ChatMessage;
import cloud.changlu.agentforge.model.chat.message.ChatMessageType;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real-application test for the doc's <b>Studio</b> scenario (section 5.1.1): a ReAct agent whose
 * {@code ToolService} mixes a real public stdio MCP server with a local {@code @Tool} bean.
 *
 * <p>The model is scripted offline, so only the MCP server is real. Runs by default when {@code
 * npx} is available; disable with {@code -Dagentforge.mcp.live=false}.
 *
 * @author changlu
 * @date 2026/10/05
 */
public class McpRealStudioTest {

    private static final Object MEMORY_ID = "studio-real";

    /**
     * 文档 5.1.1 的 Studio 场景真实应用验证。
     *
     * <p>用真实公开的 filesystem MCP Server 提供工具，与本地 {@code @Tool}（WeatherTools）注册进 同一个 {@code
     * ToolService}；再由 {@code ScriptedChatModel} 脚本驱动 {@code ReActAgent} 依次执行 {@code fs__write_file}
     * → {@code fs__read_file}，断言：
     *
     * <ol>
     *   <li>循环以 {@code SUCCESS} 结束并返回最终答复；
     *   <li>首轮请求的工具清单里同时含 MCP 工具（fs__read_file）与本地工具（getWeather）；
     *   <li>真实 MCP 工具的结果已回灌进 {@code ChatMemory}。
     * </ol>
     *
     * <p>依赖 {@code npx} 与网络（首次会拉取 npm 包）；用 {@code -Dagentforge.mcp.live=false} 可跳过。
     */
    @Test
    public void shouldRunStudioReActLoopWithRealMcpTools() throws Exception {
        assumeLive();
        assumeNpxAvailable();

        Path directory = Files.createTempDirectory("mcp-studio");
        Path file = directory.resolve("note.txt");

        McpClient mcp =
                DefaultMcpClient.builder()
                        .serverAlias("fs")
                        .transport(
                                StdioMcpTransport.of(
                                        "npx",
                                        "-y",
                                        "@modelcontextprotocol/server-filesystem",
                                        directory.toAbsolutePath().toString()))
                        .build();

        // Studio wiring: real MCP tools + local @Tool bean share one ToolService.
        ToolService toolService = new ToolService();
        toolService.tools(McpToolFactory.buildTools(mcp, "fs"));
        toolService.tools(new WeatherTools());

        ScriptedChatModel chatModel =
                new ScriptedChatModel()
                        .enqueueToolCall(
                                "c1",
                                "fs__write_file",
                                "{\"path\":"
                                        + Json.stringify(file.toAbsolutePath().toString())
                                        + ",\"content\":\"hello studio\"}")
                        .enqueueToolCall(
                                "c2",
                                "fs__read_file",
                                "{\"path\":"
                                        + Json.stringify(file.toAbsolutePath().toString())
                                        + "}")
                        .enqueueText("已完成：写入并读回 note.txt = hello studio");

        ReActAgent agent =
                ReActAgent.builder()
                        .agentName("studio-real")
                        .description("Studio + real MCP")
                        .systemPrompt("You are a studio assistant. Use tools to finish the task.")
                        .chatModel(chatModel)
                        .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
                        .toolService(toolService)
                        .agentSettings(
                                AgentSettings.builder()
                                        .maxSteps(6)
                                        .aiCallRetry(0)
                                        .aiCallRetryDelay(0)
                                        .build())
                        .build();

        try {
            ChatResult result =
                    agent.run(
                            AgentRequest.builder()
                                    .memoryId(MEMORY_ID)
                                    .question("把 note.txt 写入 hello studio，再读出来")
                                    .build());

            assertEquals(AgentRunState.SUCCESS, result.getRunState());
            assertTrue(result.getRes().contains("hello studio"));

            // The first model request already advertises both the MCP tools and the local tool.
            List<String> toolNames = toolNames(chatModel.requests().get(0).parameters().tools());
            assertTrue(
                    "expected fs__read_file in " + toolNames, toolNames.contains("fs__read_file"));
            assertTrue("expected getWeather in " + toolNames, toolNames.contains("getWeather"));

            // The real MCP tool actually executed and its output was fed back into memory.
            ChatMemory memory = agent.getChatMemoryProvider().get(MEMORY_ID);
            assertTrue(hasToolResultContaining(memory.messages(), "hello studio"));
        } finally {
            mcp.close();
        }
    }

    private static List<String> toolNames(List<ToolSpecification> tools) {
        List<String> names = new ArrayList<String>();
        for (ToolSpecification tool : tools) {
            names.add(tool.name());
        }
        return names;
    }

    private static boolean hasToolResultContaining(List<ChatMessage> messages, String needle) {
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT
                    && message.text() != null
                    && message.text().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static void assumeLive() {
        Assume.assumeTrue(
                "set -Dagentforge.mcp.live=false to skip",
                Boolean.parseBoolean(System.getProperty("agentforge.mcp.live", "true")));
    }

    private static void assumeNpxAvailable() {
        try {
            Process process =
                    new ProcessBuilder("npx", "--version").redirectErrorStream(true).start();
            process.getInputStream().close();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            Assume.assumeTrue("npx not runnable", finished && process.exitValue() == 0);
        } catch (Exception e) {
            Assume.assumeNoException("npx is not available", e);
        }
    }
}
