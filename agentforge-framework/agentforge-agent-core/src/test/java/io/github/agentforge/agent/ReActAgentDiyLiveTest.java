package io.github.agentforge.agent;

import io.github.agentforge.agent.domain.AgentRequest;
import io.github.agentforge.agent.domain.AgentRunState;
import io.github.agentforge.agent.domain.AgentSettings;
import io.github.agentforge.agent.memory.ChatMemory;
import io.github.agentforge.agent.memory.ChatMemoryProvider;
import io.github.agentforge.agent.step.ChatResult;
import io.github.agentforge.agent.support.WeatherTools;
import io.github.agentforge.model.chat.ChatModel;
import io.github.agentforge.model.chat.StreamingChatModel;
import io.github.agentforge.model.chat.message.ChatMessage;
import io.github.agentforge.model.chat.message.ChatMessageType;
import io.github.agentforge.model.chat.response.ChatResponse;
import io.github.agentforge.model.registry.LlmFactory;
import io.github.agentforge.model.registry.config.LlmBasicConfig;
import io.github.agentforge.model.registry.constant.LlmConstant;
import io.github.agentforge.model.registry.enums.LlmEnum;
import io.github.agentforge.model.tool.execution.ToolExecution;
import io.github.agentforge.model.tool.execution.ToolService;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * @description 最小化ReAct Agent端到端demo：LlmFactory构建模型 + 天气tools + think/act循环，
 *     非流式与流式各一条链路，默认跳过，需要真实endpoint配置
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgentDiyLiveTest {

    // 快速验证配置：优先读 live-endpoint.properties（已被 .gitignore 忽略），其次读这里的常量。
    // 注意：不要把真实 API Key 写进常量区，本文件会随仓库一起公开。
    private static final int DEFAULT_PROVIDER = LlmEnum.OPENAI.getCode();
    private static final String DEFAULT_BASE_URL = "";
    private static final String DEFAULT_MODEL_NAME = "";
    private static final String DEFAULT_API_KEY = "";

    private static final Object MEMORY_ID = "react-diy-demo";

    /** 非流式：run() 走完 think -> act(工具) -> think 全链路 */
    @Test
    public void shouldRunReActLoopAgainstRealEndpoint() {
        Properties config = loadConfig();
        if (!isConfigured(config)) {
            skip();
            return;
        }

        ReActAgent agent = agent(config);

        ChatResult result =
                agent.run(
                        AgentRequest.builder()
                                .memoryId(MEMORY_ID)
                                .question("杭州现在的天气怎么样？请务必调用工具查询后再回答。")
                                .build());

        System.out.println(
                "runState=" + result.getRunState() + "(" + result.getRunStateDescription() + ")");
        System.out.println("final=" + result.getRes());
        printMemory(agent.getChatMemoryProvider().get(MEMORY_ID));

        assertEquals(AgentRunState.SUCCESS, result.getRunState());
        assertNotNull(result.getRes());
        assertTrue("最终回答不应为空", !result.getRes().trim().isEmpty());
        assertTrue("应当至少完成一次工具调用", hasToolExecutionResult(agent, MEMORY_ID));
    }

    /** 流式：runStream() 增量回调 + 工具轮自动续跑 */
    @Test
    public void shouldStreamReActLoopAgainstRealEndpoint() throws Exception {
        Properties config = loadConfig();
        if (!isConfigured(config)) {
            skip();
            return;
        }

        ReActAgent agent = agent(config);
        final List<String> partials = new ArrayList<String>();
        final AtomicReference<ChatResponse> complete = new AtomicReference<ChatResponse>();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final CountDownLatch latch = new CountDownLatch(1);

        agent.runStream(
                        AgentRequest.builder()
                                .memoryId(MEMORY_ID)
                                .question("北京今天的天气怎么样？请务必调用工具查询后再回答。")
                                .build())
                .onPartialResponse(
                        new Consumer<String>() {
                            @Override
                            public void accept(String partial) {
                                synchronized (partials) {
                                    partials.add(partial);
                                }
                                System.out.print(partial);
                            }
                        })
                .onToolExecuted(
                        new Consumer<ToolExecution>() {
                            @Override
                            public void accept(
                                    io.github.agentforge.model.tool.execution.ToolExecution
                                            execution) {
                                System.out.println(
                                        "\n[tool] "
                                                + execution.request().name()
                                                + "("
                                                + execution.request().arguments()
                                                + ") -> "
                                                + execution.resultText());
                            }
                        })
                .onCompleteResponse(
                        new Consumer<ChatResponse>() {
                            @Override
                            public void accept(ChatResponse response) {
                                complete.set(response);
                                latch.countDown();
                            }
                        })
                .onError(
                        new Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable throwable) {
                                error.set(throwable);
                                latch.countDown();
                            }
                        })
                .start();

        assertTrue("流式执行超时", latch.await(180, TimeUnit.SECONDS));
        System.out.println();
        printMemory(agent.getChatMemoryProvider().get(MEMORY_ID));

        assertNull("流式执行不应报错: " + error.get(), error.get());
        assertNotNull(complete.get());
        assertTrue("应当收到增量文本", !partials.isEmpty());
        assertTrue("最终回答不应为空", !complete.get().aiMessage().text().trim().isEmpty());
        assertTrue("应当至少完成一次工具调用", hasToolExecutionResult(agent, MEMORY_ID));
    }

    private ReActAgent agent(Properties config) {
        LlmBasicConfig llmConfig =
                LlmBasicConfig.builder()
                        .provider(
                                Integer.valueOf(
                                        config.getProperty(
                                                "provider", String.valueOf(DEFAULT_PROVIDER))))
                        .url(firstNonBlank(config.getProperty("baseUrl"), DEFAULT_BASE_URL))
                        .modelName(
                                firstNonBlank(config.getProperty("modelName"), DEFAULT_MODEL_NAME))
                        .apiKey(firstNonBlank(config.getProperty("apiKey"), DEFAULT_API_KEY))
                        .prop(LlmConstant.TEMPERATURE, "0.0")
                        // 该endpoint上限393216，过大直接被LiteLLM拒绝(HTTP 400)
                        .prop(LlmConstant.MAX_TOKENS, "1024")
                        .prop(LlmConstant.TIMEOUT, "120")
                        .build();

        ChatModel chatModel = LlmFactory.buildChatModel(llmConfig);
        StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(llmConfig);

        ToolService toolService = new ToolService();
        toolService.tools(new WeatherTools());

        return ReActAgent.builder()
                .agentName("weather-react-agent")
                .systemPrompt(
                        "You are a weather assistant. Answer in Chinese. "
                                + "Always call a weather tool before answering a weather question.")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
                .toolService(toolService)
                .agentSettings(
                        AgentSettings.builder()
                                .maxSteps(5)
                                .aiCallRetry(1)
                                .aiCallRetryDelay(1000)
                                .build())
                .build();
    }

    private static boolean hasToolExecutionResult(ReActAgent agent, Object memoryId) {
        List<ChatMessage> messages = agent.getChatMemoryProvider().get(memoryId).messages();
        for (ChatMessage message : messages) {
            if (message.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                return true;
            }
        }
        return false;
    }

    private static void printMemory(ChatMemory chatMemory) {
        List<ChatMessage> messages = chatMemory.messages();
        System.out.println("memory messages=" + messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            String text = message.text();
            System.out.println(
                    "  ["
                            + i
                            + "] "
                            + message.type()
                            + " -> "
                            + (text == null ? "(tool calls)" : text));
        }
    }

    private static boolean isConfigured(Properties config) {
        return !firstNonBlank(config.getProperty("apiKey"), DEFAULT_API_KEY).trim().isEmpty();
    }

    private static void skip() {
        System.out.println(
                "Skip ReAct live demo: no apiKey configured. "
                        + "Copy live-endpoint.example.properties to live-endpoint.properties and fill it in.");
    }

    private static Properties loadConfig() {
        Properties config = new Properties();
        try (InputStream in =
                ReActAgentDiyLiveTest.class
                        .getClassLoader()
                        .getResourceAsStream("live-endpoint.properties")) {
            if (in != null) {
                config.load(in);
            }
        } catch (IOException e) {
            // 视为未配置，走常量
        }
        return config;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.trim().isEmpty() ? first.trim() : fallback;
    }
}
