package com.changlu.agentforge.ai.agent.support;

import com.changlu.agentforge.ai.agent.component.middleware.IStreamingIAgentMiddleware;
import com.changlu.agentforge.ai.agent.domain.AgentChatContext;
import com.changlu.agentforge.ai.agent.domain.StopResult;
import com.changlu.agentforge.ai.agent.step.StepResult;
import com.changlu.agentforge.ai.agent.stream.PartialThinking;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * @description 记录所有中间件回调顺序的测试中间件，可选注入中断/改写行为
 * @author changlu
 * @date 2026/9/16
 */
public class RecordingMiddleware implements IStreamingIAgentMiddleware {

    private final List<String> events = new ArrayList<String>();

    private String toolResultSuffix = "";

    private boolean abortModelCall;

    private boolean streamingEvents = true;

    private boolean afterModelSawRequest;

    private int initCount;

    public List<String> events() {
        return events;
    }

    public RecordingMiddleware toolResultSuffix(String toolResultSuffix) {
        this.toolResultSuffix = toolResultSuffix;
        return this;
    }

    public RecordingMiddleware abortModelCall() {
        this.abortModelCall = true;
        return this;
    }

    public RecordingMiddleware streamingEvents(boolean streamingEvents) {
        this.streamingEvents = streamingEvents;
        return this;
    }

    public boolean afterModelSawRequest() {
        return afterModelSawRequest;
    }

    public int initCount() {
        return initCount;
    }

    public void clear() {
        events.clear();
    }

    private void record(String event) {
        events.add(event);
    }

    @Override
    public void onInitComplete(AgentChatContext chatContext) {
        initCount++;
        record("init");
    }

    @Override
    public void beforeLoop(int currentStep, AgentChatContext chatContext) {
        record("beforeLoop:" + currentStep);
    }

    @Override
    public void afterLoop(int currentStep, StepResult stepResult, AgentChatContext chatContext) {
        record("afterLoop:" + currentStep + ":"
                + (stepResult == null ? "null" : stepResult.getState()));
    }

    @Override
    public void onLoopError(int currentStep, Throwable error, AgentChatContext chatContext) {
        record("loopError:" + currentStep);
    }

    @Override
    public void onAiCallRetry(int currentStep, ChatRequest chatRequest, AgentChatContext chatContext,
                              int retryCount, int maxRetries, long delayMs, Exception lastException) {
        record("retry:" + currentStep + ":" + retryCount + "/" + maxRetries);
    }

    @Override
    public void beforeToolExecution(ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        record("beforeTool:" + toolRequest.name());
    }

    @Override
    public String afterToolExecution(ToolExecutionRequest toolRequest, String toolResult,
                                     AgentChatContext chatContext) {
        record("afterTool:" + toolRequest.name());
        return toolResultSuffix.isEmpty() ? toolResult : toolResult + toolResultSuffix;
    }

    @Override
    public void onToolExecutionError(ToolExecutionRequest toolRequest, Throwable error,
                                     AgentChatContext chatContext) {
        record("toolError:" + toolRequest.name());
    }

    @Override
    public ChatRequest beforeModelCall(int currentStep, ChatRequest chatRequest,
                                       AgentChatContext chatContext) {
        record("beforeModel:" + currentStep);
        return abortModelCall ? null : chatRequest;
    }

    @Override
    public ChatResponse afterModelCall(int currentStep, ChatRequest chatRequest,
                                       ChatResponse chatResponse, AgentChatContext chatContext) {
        afterModelSawRequest = chatRequest != null;
        record("afterModel:" + currentStep + ":"
                + (chatResponse.aiMessage().toolExecutionRequests() == null ? 0
                        : chatResponse.aiMessage().toolExecutionRequests().size()));
        return chatResponse;
    }

    @Override
    public void onModelCallError(int currentStep, ChatRequest chatRequest, Throwable error,
                                 AgentChatContext chatContext) {
        record("modelError:" + currentStep);
    }

    @Override
    public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        record("stop:" + currentStep + ":"
                + (stopResult == null ? "null" : stopResult.getStopResultState()));
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
        record("stopError:" + currentStep);
    }

    @Override
    public void onPartialResponse(int currentStep, String partialResponse, AgentChatContext chatContext) {
        record("partial:" + currentStep + ":" + partialResponse);
    }

    @Override
    public void onPartialThinking(int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        record("think:" + currentStep + ":" + partialThinking.text());
    }

    @Override
    public void onIntermediateResponse(int currentStep, ChatResponse intermediateResponse,
                                       AgentChatContext chatContext) {
        record("intermediate:" + currentStep);
    }

    @Override
    public boolean handlesStreamingEvents() {
        return streamingEvents;
    }
}
