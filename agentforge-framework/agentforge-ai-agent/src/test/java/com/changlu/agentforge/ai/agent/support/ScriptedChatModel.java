package com.changlu.agentforge.ai.agent.support;

import com.changlu.agentforge.llm.chat.ChatModel;
import com.changlu.agentforge.llm.chat.message.AiMessage;
import com.changlu.agentforge.llm.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.llm.chat.request.ChatRequest;
import com.changlu.agentforge.llm.chat.response.ChatResponse;
import com.changlu.agentforge.llm.chat.response.FinishReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @description 离线脚本化同步模型：按预置顺序返回回复，用于验证think/act循环
 * @author changlu
 * @date 2026/9/16
 */
public class ScriptedChatModel implements ChatModel {

    private final List<ChatResponse> scripted = new ArrayList<ChatResponse>();
    private final List<ChatRequest> requests = new ArrayList<ChatRequest>();
    private int failuresBeforeSuccess;
    private String failureMessage = "mock model failure";

    public ScriptedChatModel enqueueText(String text) {
        scripted.add(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from(text))
                        .finishReason(FinishReason.STOP)
                        .build());
        return this;
    }

    public ScriptedChatModel enqueueToolCall(String id, String name, String arguments) {
        scripted.add(
                ChatResponse.builder()
                        .aiMessage(
                                AiMessage.from(
                                        Collections.singletonList(
                                                ToolExecutionRequest.from(id, name, arguments))))
                        .finishReason(FinishReason.TOOL_EXECUTION)
                        .build());
        return this;
    }

    /** 前若干次调用直接抛异常，用于验证重试 */
    public ScriptedChatModel failFirst(int times) {
        this.failuresBeforeSuccess = times;
        return this;
    }

    public ScriptedChatModel alwaysFail(String message) {
        this.failureMessage = message;
        return failFirst(Integer.MAX_VALUE);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        requests.add(chatRequest);
        if (failuresBeforeSuccess-- > 0) {
            throw new IllegalStateException(failureMessage);
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException("no scripted response left, calls=" + requests.size());
        }
        return scripted.remove(0);
    }

    public int callCount() {
        return requests.size();
    }

    public List<ChatRequest> requests() {
        return requests;
    }
}
