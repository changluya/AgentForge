package cloud.changlu.agentforge.studio.config;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.memory.ChatMemoryProvider;
import cloud.changlu.agentforge.model.openai.OpenAiChatModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingChatModel;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.studio.stream.StudioStreamingMiddleware;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {

    @Bean
    public StudioStreamingMiddleware studioStreamingMiddleware() {
        return new StudioStreamingMiddleware();
    }

    @Bean
    public ReActAgent studioAgent(
            @Value("${agentforge.model.base-url}") String baseUrl,
            @Value("${agentforge.model.api-key:}") String apiKey,
            @Value("${agentforge.model.name}") String modelName,
            @Value("${agentforge.model.temperature:0.3}") Double temperature,
            StudioStreamingMiddleware studioStreamingMiddleware) {
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
                .agentName("agentforge-studio-agent")
                .description("AgentForge Studio default ReAct agent")
                .systemPrompt(
                        "You are AgentForge Studio assistant. Be accurate, concise and helpful. Reply in the user's language.")
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
                .toolService(new ToolService())
                .agentSettings(
                        AgentSettings.builder()
                                .maxSteps(10)
                                .aiCallRetry(1)
                                .aiCallRetryDelay(500)
                                .build())
                .middleware(studioStreamingMiddleware)
                .build();
    }
}
