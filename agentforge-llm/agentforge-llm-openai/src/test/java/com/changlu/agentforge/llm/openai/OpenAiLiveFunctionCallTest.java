package com.changlu.agentforge.llm.openai;

import com.changlu.agentforge.llm.tool.Tool;
import com.changlu.agentforge.llm.tool.P;
import com.changlu.agentforge.llm.tool.execution.ToolService;
import com.changlu.agentforge.llm.chat.message.ChatMessage;
import com.changlu.agentforge.llm.chat.message.UserMessage;
import com.changlu.agentforge.llm.chat.request.DefaultChatRequestParameters;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Optional live, end-to-end {@link ToolService} + Function Calling test against a real
 * OpenAI-compatible endpoint.
 *
 * <p>It is skipped by default unless a {@code live-endpoint.properties} file (Git-ignored, see the
 * {@code .gitignore} entry and {@code live-endpoint.example.properties} template) is present with a
 * non-blank {@code baseUrl}/{@code modelName}/{@code apiKey}. Credentials are therefore never hard-coded
 * in the test source.</p>
 *
 * <p>The test registers a {@code getWeather} tool, sends a user message, and lets the model decide
 * to call the tool. {@link ToolService} then executes it and, on a second round, returns the final
 * assistant answer.</p>
 *
 * @author changlu
 * @since 2026-09-13
 */
public class OpenAiLiveFunctionCallTest {

    public static class WeatherTools {

        @Tool(value = "Returns the weather for the given city")
        public String getWeather(@P("The city name") String city) {
            return "The weather in " + city + " is 22 degrees Celsius and sunny.";
        }
    }

    @Test
    public void shouldRunLiveFunctionCallAndResolveWithToolService() {
        Properties config = loadConfig();
        String baseUrl = config.getProperty("baseUrl", "").trim();
        String modelName = config.getProperty("modelName", "").trim();
        String apiKey = config.getProperty("apiKey", "").trim();
        if (baseUrl.isEmpty() || modelName.isEmpty() || apiKey.isEmpty()) {
            System.out.println("Skip live function-call test: no live-endpoint.properties config (see "
                    + "live-endpoint.example.properties template).");
            return;
        }

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .build();

        ToolService toolService = new ToolService();
        toolService.tool(new WeatherTools(), "getWeather");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("What is the weather in Hangzhou? Answer in one short sentence."));

        ToolService.ToolChatResult result = toolService.chat(
                model,
                DefaultChatRequestParameters.builder().build(),
                messages);

        assertNotNull(result.finalResponse());
        assertNotNull(result.finalResponse().aiMessage());
        assertTrue("Expected a final assistant answer", 
                result.finalResponse().aiMessage().text() != null);
        assertFalse("Tool should have been executed", result.toolExecutions().isEmpty());
        assertTrue("getWeather tool result should be non-error",
                !result.toolExecutions().get(0).hasFailed());

        System.out.println("Live function-call succeeded.");
        System.out.println("executions=" + result.toolExecutions().size());
        System.out.println("final=" + result.finalResponse().aiMessage().text());
    }

    private static Properties loadConfig() {
        Properties config = new Properties();
        try (InputStream in = OpenAiLiveFunctionCallTest.class.getClassLoader()
                .getResourceAsStream("live-endpoint.properties")) {
            if (in != null) {
                config.load(in);
            }
        } catch (IOException e) {
            // treat as absent; test will skip
        }
        return config;
    }
}