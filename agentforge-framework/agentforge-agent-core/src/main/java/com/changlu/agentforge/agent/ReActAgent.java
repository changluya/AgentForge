package com.changlu.agentforge.agent;

import com.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import com.changlu.agentforge.agent.component.middleware.IAgentMiddleware;
import com.changlu.agentforge.agent.domain.AgentChatContext;
import com.changlu.agentforge.agent.domain.AgentRunState;
import com.changlu.agentforge.agent.domain.AgentSettings;
import com.changlu.agentforge.agent.domain.ThinkResult;
import com.changlu.agentforge.agent.memory.ChatMemory;
import com.changlu.agentforge.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.agent.retry.AiCallRetrySupport;
import com.changlu.agentforge.agent.step.StepResult;
import com.changlu.agentforge.agent.tool.AgentToolExecutor;
import com.changlu.agentforge.model.chat.ChatModel;
import com.changlu.agentforge.model.chat.StreamingChatModel;
import com.changlu.agentforge.model.chat.message.AiMessage;
import com.changlu.agentforge.model.chat.message.ChatMessage;
import com.changlu.agentforge.model.chat.message.ChatMessageType;
import com.changlu.agentforge.model.chat.message.SystemMessage;
import com.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.model.chat.message.ToolExecutionResultMessage;
import com.changlu.agentforge.model.chat.request.ChatRequest;
import com.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import com.changlu.agentforge.model.chat.response.ChatResponse;
import com.changlu.agentforge.model.exception.LlmException;
import com.changlu.agentforge.model.tool.ToolExecutor;
import com.changlu.agentforge.model.tool.execution.ToolService;
import com.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @description ReAct Agent：一轮think（模型推理是否要调用工具）+ 一轮act（执行工具并写回记忆），循环到结束或步长上限
 * @author changlu
 * @date 2026/9/16
 */
public class ReActAgent extends BaseReActAgent {

    private final String agentName;
    private final String description;
    private final String systemPrompt;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryProvider chatMemoryProvider;
    private final AgentSettings agentSettings;
    private final ToolService toolService;
    private final AgentToolExecutor toolExecutor;
    private final AgentMiddlewareManager middlewareManager;

    protected ReActAgent(ReActAgentBuilder builder) {
        this.agentName = builder.agentName;
        this.description = builder.description;
        this.systemPrompt = builder.systemPrompt;
        this.chatModel = builder.chatModel;
        this.streamingChatModel = builder.streamingChatModel;
        this.chatMemoryProvider = builder.chatMemoryProvider;
        this.toolService = builder.toolService;
        this.agentSettings =
                builder.agentSettings == null
                        ? AgentSettings.defaultSettings()
                        : builder.agentSettings;
        this.toolExecutor = new AgentToolExecutor(builder.toolService);
        this.middlewareManager = new AgentMiddlewareManager();
        this.middlewareManager.registerAll(builder.middlewares);
    }

    public static ReActAgentBuilder builder() {
        return new ReActAgentBuilder();
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getDescription() {
        return description;
    }

    public String getAgentName() {
        return agentName;
    }

    @Override
    protected void initMessages(AgentChatContext chatContext) {
        ChatMemory chatMemory = chatContext.getChatMemory();
        List<ChatMessage> messages = chatMemory.messages();

        // 过滤掉所有系统消息，保留其他类型的消息
        List<ChatMessage> nonSystemMessages = new ArrayList<ChatMessage>();
        for (ChatMessage message : messages) {
            if (message.type() != ChatMessageType.SYSTEM) {
                nonSystemMessages.add(message);
            }
        }

        // 重新构建消息列表：新系统消息 + 其他非系统消息
        List<ChatMessage> newMessages = new ArrayList<ChatMessage>();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            newMessages.add(SystemMessage.from(systemPrompt));
        }
        newMessages.addAll(nonSystemMessages);

        // 清空原有记忆并重新添加
        chatMemory.clear();
        for (ChatMessage message : newMessages) {
            chatMemory.add(message);
        }

        super.initMessages(chatContext);
    }

    @Override
    protected ThinkResult think(int currentStep, AgentChatContext chatContext) {
        ChatMemory chatMemory = chatContext.getChatMemory();
        List<ChatMessage> curCallMessages = chatMemory.messages();

        int retryCount = getAgentSettings().getAiCallRetry();
        long aiCallRetryDelay = getAgentSettings().getAiCallRetryDelay();

        // 构建请求request对象：当前记忆消息 + 全部工具声明
        ChatRequest chatRequest =
                ChatRequest.builder()
                        .messages(curCallMessages)
                        .parameters(
                                DefaultChatRequestParameters.builder()
                                        .tools(
                                                new ArrayList<ToolSpecification>(
                                                        getToolSpecifications()))
                                        .build())
                        .build();

        // 触发模型调用前的中间件
        ChatRequest processedRequest =
                middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
        if (processedRequest == null) {
            // 中间件中断本轮模型调用，直接以中断原因结束任务，不再继续消耗步长
            return ThinkResult.builder()
                    .isFinish(true)
                    .needAct(false)
                    .runRes("模型调用被中间件中断")
                    .curActTools(Collections.<ToolExecutionRequest>emptyList())
                    .build();
        }

        // real model call
        ChatResponse chatResponse;
        try {
            chatResponse =
                    AiCallRetrySupport.execute(
                            () -> getChatModel().chat(processedRequest),
                            retryCount,
                            aiCallRetryDelay,
                            (attempt, maxRetries, lastException, delayMs) ->
                                    middlewareManager.triggerOnAiCallRetry(
                                            currentStep,
                                            processedRequest,
                                            chatContext,
                                            attempt,
                                            maxRetries,
                                            delayMs,
                                            lastException));
        } catch (Exception e) {
            // 触发模型调用错误的中间件
            middlewareManager.triggerOnModelCallError(
                    currentStep, processedRequest, e, chatContext);
            // 模型调用失败，直接结束任务，避免继续进行工具调用
            return ThinkResult.builder()
                    .state(AgentRunState.MODEL_CALL_ERROR)
                    .isFinish(true)
                    .needAct(false)
                    .runRes("模型调用失败，请解决请求异常: " + describeError(e))
                    .curActTools(Collections.<ToolExecutionRequest>emptyList())
                    .build();
        }

        // 触发模型调用后的中间件
        chatResponse =
                middlewareManager.triggerAfterModelCall(
                        currentStep, processedRequest, chatResponse, chatContext);

        AiMessage aiMessage = chatResponse.aiMessage();

        // 添加message消息
        addMessage(chatContext, aiMessage);

        List<ToolExecutionRequest> curActTools = new ArrayList<ToolExecutionRequest>();
        // 未开启stop tool，直接根据是否还有工具调用来判断任务是否结束
        boolean isFinish = aiMessage.toolExecutionRequests().isEmpty();
        String runRes = "";
        if (isFinish) {
            runRes = aiMessage.text();
        } else {
            curActTools = aiMessage.toolExecutionRequests();
        }

        return ThinkResult.builder()
                .needAct(!curActTools.isEmpty())
                .isFinish(isFinish)
                .runRes(runRes)
                .curActTools(curActTools)
                .build();
    }

    /** 模型调用异常时尽量带上服务端响应体，便于快速定位400/401这类协议级错误 */
    private static String describeError(Exception e) {
        if (e instanceof LlmException) {
            String body = ((LlmException) e).responseBody();
            if (body != null && !body.trim().isEmpty()) {
                return e.getMessage() + " | response: " + body.trim();
            }
        }
        return e.getMessage();
    }

    @Override
    protected StepResult act(
            int currentStep, List<ToolExecutionRequest> curActTools, AgentChatContext chatContext) {
        List<ToolExecutionResultMessage> toolMessages =
                getToolExecutor()
                        .execute(
                                currentStep,
                                curActTools,
                                chatContext,
                                null,
                                getMiddlewareManager());
        return StepResult.toFinished(toolMessages.toString());
    }

    @Override
    protected List<ToolSpecification> getToolSpecifications() {
        return toolService.toolSpecifications();
    }

    protected Map<String, ToolExecutor> getExecutorMap() {
        return toolService.toolExecutors();
    }

    protected List<ChatMessage> getAllMessages(Object memoryId) {
        return chatMemoryProvider.get(memoryId).messages();
    }

    @Override
    public ChatModel getChatModel() {
        return chatModel;
    }

    @Override
    protected StreamingChatModel getStreamChatModel() {
        return streamingChatModel;
    }

    @Override
    public ChatMemoryProvider getChatMemoryProvider() {
        return chatMemoryProvider;
    }

    @Override
    protected AgentSettings getAgentSettings() {
        return agentSettings;
    }

    @Override
    protected ToolService getToolService() {
        return toolService;
    }

    @Override
    protected AgentToolExecutor getToolExecutor() {
        return toolExecutor;
    }

    @Override
    protected AgentMiddlewareManager getMiddlewareManager() {
        return middlewareManager;
    }

    public static class ReActAgentBuilder {
        private String systemPrompt;
        private String description;
        private String agentName;

        private ChatModel chatModel;
        private StreamingChatModel streamingChatModel;
        private ChatMemoryProvider chatMemoryProvider;
        private ToolService toolService;
        private AgentSettings agentSettings;
        private final List<IAgentMiddleware> middlewares = new ArrayList<IAgentMiddleware>();

        public ReActAgentBuilder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public ReActAgentBuilder streamingChatModel(StreamingChatModel streamingChatModel) {
            this.streamingChatModel = streamingChatModel;
            return this;
        }

        public ReActAgentBuilder chatMemoryProvider(ChatMemoryProvider chatMemoryProvider) {
            this.chatMemoryProvider = chatMemoryProvider;
            return this;
        }

        public ReActAgentBuilder toolService(ToolService toolService) {
            this.toolService = toolService;
            return this;
        }

        public ReActAgentBuilder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public ReActAgentBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ReActAgentBuilder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public ReActAgentBuilder agentSettings(AgentSettings agentSettings) {
            this.agentSettings = agentSettings;
            return this;
        }

        /** 注册单个中间件，按注册顺序执行 */
        public ReActAgentBuilder middleware(IAgentMiddleware middleware) {
            if (middleware != null) {
                this.middlewares.add(middleware);
            }
            return this;
        }

        /** 批量注册中间件，按加入顺序执行 */
        public ReActAgentBuilder middlewares(List<IAgentMiddleware> middlewares) {
            if (middlewares != null) {
                for (IAgentMiddleware middleware : middlewares) {
                    middleware(middleware);
                }
            }
            return this;
        }

        public ReActAgent build() {
            return new ReActAgent(this);
        }
    }
}
