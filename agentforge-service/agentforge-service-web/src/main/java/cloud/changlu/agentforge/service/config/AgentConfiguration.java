package cloud.changlu.agentforge.service.config;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.agent.tool.local.LocalToolFactory;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachClient;
import cloud.changlu.agentforge.service.plugins.openreach.OpenReachTools;
import cloud.changlu.agentforge.service.stream.ServiceStreamingMiddleware;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Map;

@Configuration
public class AgentConfiguration {

    @Bean
    public ServiceStreamingMiddleware serviceStreamingMiddleware() {
        return new ServiceStreamingMiddleware();
    }

    @Bean
    public ReActAgent serviceAgent(
            @Value("${agentforge.model.base-url}") String baseUrl,
            @Value("${agentforge.model.api-key:}") String apiKey,
            @Value("${agentforge.model.name}") String modelName,
            @Value("${agentforge.model.temperature:0.3}") Double temperature,
            @Value("${agentforge.openreach.base-url:}") String openReachBaseUrl,
            ServiceStreamingMiddleware serviceStreamingMiddleware) {
        OpenAiChatModel chatModel =
                OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(temperature)
                        .build();
        OpenAiStreamingChatModel streamingChatModel =
                OpenAiStreamingChatModel.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(temperature)
                        .build();

        return ReActAgent.builder()
                .agentName("agentforge-service-agent")
                .description("AgentForge Service default ReAct agent")
                .systemPrompt(
                        "You are AgentForge Service assistant. Be accurate, concise and helpful. "
                                + "When you need up-to-date or external information, use the web tools "
                                + "(webSearch, webRead, webCurl, webImageSearch) and cite the sources you "
                                + "actually read. Reply in the user's language.")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
                .toolService(buildToolService(openReachBaseUrl))
                .agentSettings(
                        AgentSettings.builder()
                                .maxSteps(10)
                                .aiCallRetry(1)
                                .aiCallRetryDelay(500)
                                .build())
                .middleware(serviceStreamingMiddleware)
                .build();
    }

    private ToolService buildToolService(String openReachBaseUrl) {
        ToolService toolService = new ToolService();
        if (openReachBaseUrl == null || openReachBaseUrl.trim().isEmpty()) {
            return toolService;
        }
        OpenReachTools openReachTools = new OpenReachTools(new OpenReachClient(openReachBaseUrl));
        Map<ToolSpecification, ToolExecutor> tools =
                LocalToolFactory.buildLocalTools(Collections.<Object>singletonList(openReachTools));
        toolService.tools(tools);
        return toolService;
    }
}
