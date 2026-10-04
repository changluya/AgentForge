package io.github.agentforge.agent.stream;

import io.github.agentforge.agent.component.middleware.AgentMiddlewareManager;
import io.github.agentforge.agent.domain.AgentChatContext;
import io.github.agentforge.agent.domain.AgentSettings;
import io.github.agentforge.agent.domain.StopResult;
import io.github.agentforge.agent.exception.AgentException;
import io.github.agentforge.agent.exception.CancelException;
import io.github.agentforge.agent.step.StepResult;
import io.github.agentforge.agent.step.StopResultState;
import io.github.agentforge.agent.tool.AgentToolExecutor;
import io.github.agentforge.model.chat.StreamingChatModel;
import io.github.agentforge.model.chat.message.AiMessage;
import io.github.agentforge.model.chat.request.ChatRequest;
import io.github.agentforge.model.chat.request.DefaultChatRequestParameters;
import io.github.agentforge.model.chat.response.ChatResponse;
import io.github.agentforge.model.chat.response.StreamingChatResponseHandler;
import io.github.agentforge.model.tool.execution.ToolExecution;
import io.github.agentforge.model.tool.spec.ToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * @description ReAct流式执行：每一轮用StreamingChatModel发起请求，遇到工具调用则执行act并自动进入下一轮，
 *     直到模型给出最终回答或达到步长上限；执行过程同步触发中间件回调
 * @author changlu
 * @date 2026/9/16
 */
public class ReActTokenStream implements TokenStream {

    private static final String MODEL_CALL_INTERRUPTED = "模型调用被中间件中断";

    private static final String MAX_STEPS_PREFIX = "执行步长达到限制";

    private final AgentChatContext chatContext;
    private final StreamingChatModel streamingChatModel;
    private final AgentToolExecutor toolExecutor;
    private final List<ToolSpecification> toolSpecifications;
    private final AgentSettings agentSettings;
    private final Map<Object, AtomicBoolean> cancelFlags;
    private final AgentMiddlewareManager middlewareManager;

    private Consumer<String> partialResponseHandler;
    private Consumer<PartialThinking> partialThinkingHandler;
    private Consumer<ChatResponse> intermediateResponseHandler;
    private Consumer<ToolExecution> toolExecutedHandler;
    private Consumer<ChatResponse> completeResponseHandler;
    private Consumer<Throwable> errorHandler;
    private boolean ignoreErrors;

    // 本轮实际发起模型调用所用的请求，供afterModelCall等中间件回调读取
    private ChatRequest currentChatRequest;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public ReActTokenStream(
            AgentChatContext chatContext,
            StreamingChatModel streamingChatModel,
            AgentToolExecutor toolExecutor,
            List<ToolSpecification> toolSpecifications,
            AgentSettings agentSettings,
            Map<Object, AtomicBoolean> cancelFlags) {
        this(
                chatContext,
                streamingChatModel,
                toolExecutor,
                toolSpecifications,
                agentSettings,
                cancelFlags,
                null);
    }

    public ReActTokenStream(
            AgentChatContext chatContext,
            StreamingChatModel streamingChatModel,
            AgentToolExecutor toolExecutor,
            List<ToolSpecification> toolSpecifications,
            AgentSettings agentSettings,
            Map<Object, AtomicBoolean> cancelFlags,
            AgentMiddlewareManager middlewareManager) {
        if (streamingChatModel == null) {
            throw new AgentException("streamingChatModel is null, need set");
        }
        this.chatContext = chatContext;
        this.streamingChatModel = streamingChatModel;
        this.toolExecutor = toolExecutor;
        this.toolSpecifications =
                toolSpecifications == null
                        ? new ArrayList<ToolSpecification>()
                        : toolSpecifications;
        this.agentSettings = agentSettings;
        this.cancelFlags = cancelFlags;
        this.middlewareManager = middlewareManager;
    }

    @Override
    public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
        this.partialResponseHandler = partialResponseHandler;
        return this;
    }

    @Override
    public TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler) {
        this.partialThinkingHandler = partialThinkingHandler;
        return this;
    }

    @Override
    public TokenStream onIntermediateResponse(Consumer<ChatResponse> intermediateResponseHandler) {
        this.intermediateResponseHandler = intermediateResponseHandler;
        return this;
    }

    @Override
    public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
        this.toolExecutedHandler = toolExecuteHandler;
        return this;
    }

    @Override
    public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
        this.completeResponseHandler = completeResponseHandler;
        return this;
    }

    @Override
    public TokenStream onError(Consumer<Throwable> errorHandler) {
        this.errorHandler = errorHandler;
        return this;
    }

    @Override
    public TokenStream ignoreErrors() {
        this.ignoreErrors = true;
        return this;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new AgentException("TokenStream已经启动，不能重复start");
        }
        if (middlewareManager != null) {
            // 触发中间件初始化完成回调（只执行一次）
            middlewareManager.triggerOnInitComplete(chatContext);
        }
        try {
            chat(1);
        } catch (RuntimeException e) {
            // 模型调用是异步的，这里的同步异常统一走错误回调，避免直接抛到调用线程
            handleError(1, e);
        }
    }

    private void chat(final int currentStep) {
        if (isCancelled()) {
            handleCancel(
                    currentStep,
                    String.format("会话：%s 在第%d步被取消", chatContext.getMemoryId(), currentStep));
            return;
        }
        int maxSteps = agentSettings.getMaxSteps();
        if (currentStep > maxSteps) {
            handleError(currentStep, new AgentException(MAX_STEPS_PREFIX + "，请重新询问你的问题！"));
            return;
        }

        DefaultChatRequestParameters.Builder parameters = DefaultChatRequestParameters.builder();
        if (!toolSpecifications.isEmpty()) {
            parameters.tools(new ArrayList<ToolSpecification>(toolSpecifications));
        }
        ChatRequest chatRequest =
                ChatRequest.builder()
                        .messages(chatContext.getChatMemory().messages())
                        .parameters(parameters.build())
                        .build();

        ChatRequest processedRequest = chatRequest;
        if (middlewareManager != null) {
            middlewareManager.triggerBeforeLoop(currentStep, chatContext);
            processedRequest =
                    middlewareManager.triggerBeforeModelCall(currentStep, chatRequest, chatContext);
            if (processedRequest == null) {
                // 中间件中断本轮模型调用，按取消态收敛后结束
                notifyStop(
                        currentStep,
                        StopResultState.CANCEL,
                        MODEL_CALL_INTERRUPTED,
                        new AgentException(MODEL_CALL_INTERRUPTED));
                notifyError(new AgentException(MODEL_CALL_INTERRUPTED));
                return;
            }
        }
        currentChatRequest = processedRequest;

        final ChatRequest callRequest = processedRequest;
        streamingChatModel.chat(
                callRequest,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (middlewareManager != null) {
                            middlewareManager.triggerOnPartialResponse(
                                    currentStep, partialResponse, chatContext);
                        }
                        if (partialResponseHandler != null) {
                            partialResponseHandler.accept(partialResponse);
                        }
                    }

                    @Override
                    public void onPartialThinking(String partialThinking) {
                        PartialThinking thinking = new PartialThinking(partialThinking);
                        if (middlewareManager != null) {
                            middlewareManager.triggerOnPartialThinking(
                                    currentStep, thinking, chatContext);
                        }
                        if (partialThinkingHandler != null) {
                            partialThinkingHandler.accept(thinking);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        onRoundComplete(currentStep, completeResponse);
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleError(currentStep, error);
                    }
                });
    }

    private void onRoundComplete(int currentStep, ChatResponse response) {
        if (middlewareManager != null) {
            // 触发模型调用后的中间件
            response =
                    middlewareManager.triggerAfterModelCall(
                            currentStep, currentChatRequest, response, chatContext);
        }

        AiMessage aiMessage = response.aiMessage();
        chatContext.getChatMemory().add(aiMessage);

        if (isCancelled()) {
            handleCancel(currentStep, String.format("会话：%s 流式执行被取消", chatContext.getMemoryId()));
            return;
        }

        // check是否还需要tools调用情况
        if (!aiMessage.hasToolExecutionRequests()) {
            if (middlewareManager != null) {
                middlewareManager.triggerAfterLoop(
                        currentStep, StepResult.toStop(aiMessage.text()), chatContext);
                middlewareManager.triggerOnStop(
                        currentStep,
                        stopResult(StopResultState.NORMAL, aiMessage.text()),
                        chatContext);
            }
            handleComplete(response);
            return;
        }

        // act：执行本轮工具并把结果写回记忆，随后自动进入下一轮think
        toolExecutor.execute(
                currentStep,
                aiMessage.toolExecutionRequests(),
                chatContext,
                toolExecutedHandler,
                middlewareManager);
        if (middlewareManager != null) {
            // 中间响应：本轮的模型响应携带工具调用请求，工具执行完成后在下一轮思考前触发
            middlewareManager.triggerOnIntermediateResponse(currentStep, response, chatContext);
        }
        if (intermediateResponseHandler != null) {
            intermediateResponseHandler.accept(response);
        }
        if (middlewareManager != null) {
            // 与非流式循环保持一致：afterLoop 在本轮工具执行完成后触发，状态为 FINISHED
            middlewareManager.triggerAfterLoop(currentStep, StepResult.toFinished(""), chatContext);
        }
        chat(currentStep + 1);
    }

    private void handleComplete(ChatResponse response) {
        clearCancelFlag();
        if (completeResponseHandler != null) {
            completeResponseHandler.accept(response);
        }
    }

    // 取消场景：停止回调在这里统一触发，避免与handleError重复
    private void handleCancel(int currentStep, String message) {
        notifyStop(currentStep, StopResultState.CANCEL, message, new CancelException(message));
        notifyError(new CancelException(message));
    }

    // 触发中间件的错误/停止回调，再交给上层错误处理器
    private void handleError(int currentStep, Throwable error) {
        if (middlewareManager != null) {
            middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
            if (isMaxStepsError(error)) {
                middlewareManager.triggerOnStop(
                        currentStep,
                        stopResult(StopResultState.MAX_STEPS, error.getMessage()),
                        chatContext);
            } else if (error instanceof CancelException) {
                middlewareManager.triggerOnStop(
                        currentStep,
                        stopResult(StopResultState.CANCEL, error.getMessage()),
                        chatContext);
            } else {
                middlewareManager.triggerOnStopWithError(currentStep, error, chatContext);
            }
        }
        notifyError(error);
    }

    // onLoopError + onStop 成对触发
    private void notifyStop(
            int currentStep, StopResultState state, String runRes, Throwable error) {
        if (middlewareManager == null) {
            return;
        }
        middlewareManager.triggerOnLoopError(currentStep, error, chatContext);
        middlewareManager.triggerOnStop(currentStep, stopResult(state, runRes), chatContext);
    }

    private void notifyError(Throwable error) {
        clearCancelFlag();
        if (errorHandler != null) {
            errorHandler.accept(error);
            return;
        }
        if (ignoreErrors) {
            return;
        }
        throw new AgentException("Agent流式执行失败: " + error.getMessage(), error);
    }

    private boolean isMaxStepsError(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().startsWith(MAX_STEPS_PREFIX)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static StopResult stopResult(StopResultState state, String runRes) {
        return StopResult.builder().stopResultState(state).runRes(runRes).build();
    }

    private boolean isCancelled() {
        AtomicBoolean flag = cancelFlags.get(chatContext.getMemoryId());
        return flag != null && flag.get();
    }

    private void clearCancelFlag() {
        cancelFlags.remove(chatContext.getMemoryId());
    }
}
