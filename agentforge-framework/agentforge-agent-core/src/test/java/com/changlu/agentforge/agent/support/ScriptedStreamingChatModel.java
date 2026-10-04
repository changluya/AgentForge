package com.changlu.agentforge.agent.support;

import com.changlu.agentforge.model.chat.StreamingChatModel;
import com.changlu.agentforge.model.chat.message.AiMessage;
import com.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import com.changlu.agentforge.model.chat.request.ChatRequest;
import com.changlu.agentforge.model.chat.response.ChatResponse;
import com.changlu.agentforge.model.chat.response.FinishReason;
import com.changlu.agentforge.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @description 离线脚本化流式模型：在当前线程按顺序回调增量文本与整轮结果
 * @author changlu
 * @date 2026/9/16
 */
public class ScriptedStreamingChatModel implements StreamingChatModel {

    private static final class Round {
        private final List<String> thinking;
        private final List<String> partials;
        private final ChatResponse response;
        private final Throwable error;

        private Round(
                List<String> thinking,
                List<String> partials,
                ChatResponse response,
                Throwable error) {
            this.thinking = thinking;
            this.partials = partials;
            this.response = response;
            this.error = error;
        }
    }

    private final List<Round> rounds = new ArrayList<Round>();
    private final List<ChatRequest> requests = new ArrayList<ChatRequest>();

    public ScriptedStreamingChatModel enqueueText(String... partials) {
        return enqueueReasonedText(new String[0], partials);
    }

    /** 先回调思考增量片段、再回调文本增量的整轮结果，模拟带推理阶段的模型 */
    public ScriptedStreamingChatModel enqueueReasonedText(String[] thinking, String... partials) {
        List<String> fragments = Arrays.asList(partials);
        String text = String.join("", fragments.toArray(new String[0]));
        rounds.add(
                new Round(
                        Arrays.asList(thinking),
                        fragments,
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from(text))
                                .finishReason(FinishReason.STOP)
                                .build(),
                        null));
        return this;
    }

    public ScriptedStreamingChatModel enqueueToolCall(String id, String name, String arguments) {
        rounds.add(
                new Round(
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        ChatResponse.builder()
                                .aiMessage(
                                        AiMessage.from(
                                                Collections.singletonList(
                                                        ToolExecutionRequest.from(
                                                                id, name, arguments))))
                                .finishReason(FinishReason.TOOL_EXECUTION)
                                .build(),
                        null));
        return this;
    }

    public ScriptedStreamingChatModel enqueueError(Throwable error) {
        rounds.add(
                new Round(
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        null,
                        error));
        return this;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        requests.add(chatRequest);
        if (rounds.isEmpty()) {
            handler.onError(new IllegalStateException("no scripted streaming round left"));
            return;
        }
        Round round = rounds.remove(0);
        if (round.error != null) {
            handler.onError(round.error);
            return;
        }
        for (String thinkingFragment : round.thinking) {
            handler.onPartialThinking(thinkingFragment);
        }
        for (String partial : round.partials) {
            handler.onPartialResponse(partial);
        }
        handler.onCompleteResponse(round.response);
    }

    public int callCount() {
        return requests.size();
    }

    public List<ChatRequest> requests() {
        return requests;
    }
}
