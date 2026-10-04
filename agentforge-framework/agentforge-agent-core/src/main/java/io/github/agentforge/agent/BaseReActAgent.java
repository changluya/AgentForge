package io.github.agentforge.agent;

import io.github.agentforge.agent.domain.AgentChatContext;
import io.github.agentforge.agent.domain.AgentRunState;
import io.github.agentforge.agent.domain.ThinkResult;
import io.github.agentforge.agent.exception.CancelException;
import io.github.agentforge.agent.step.StepResult;
import io.github.agentforge.agent.step.StepState;
import io.github.agentforge.agent.stream.ReActTokenStream;
import io.github.agentforge.agent.stream.TokenStream;
import io.github.agentforge.agent.tool.AgentToolExecutor;
import io.github.agentforge.model.chat.StreamingChatModel;
import io.github.agentforge.model.chat.message.ToolExecutionRequest;
import io.github.agentforge.model.tool.spec.ToolSpecification;

import java.util.List;

/**
 * BaseReActAgent implements the ReAct (Reasoning and Acting) framework, enabling agents to
 * interleave reasoning and actions. It extends the base Agent class and provides abstract methods
 * for thinking and acting.
 *
 * @author changlu
 * @date 2026/9/16
 */
public abstract class BaseReActAgent extends Agent {

    protected abstract StreamingChatModel getStreamChatModel();

    protected abstract AgentToolExecutor getToolExecutor();

    protected abstract List<ToolSpecification> getToolSpecifications();

    protected abstract ThinkResult think(int currentStep, AgentChatContext chatContext);

    protected abstract StepResult act(
            int currentStep, List<ToolExecutionRequest> curActTools, AgentChatContext chatContext);

    @Override
    protected StepResult step(int currentStep, AgentChatContext chatContext) {
        // 初始检查
        checkIsCancelled(chatContext);
        // 思考！
        ThinkResult think = think(currentStep, chatContext);
        // 思考后立即检查
        checkIsCancelled(chatContext);

        Boolean isFinish = think.getIsFinish();
        if (isFinish != null && isFinish) {
            // 若是在初步思考中结束，那就说明不需要进行下一步执行了，直接拿到返回结果
            String res = think.getRunRes();
            AgentRunState runState =
                    think.getState() == null ? AgentRunState.SUCCESS : think.getState();
            return StepResult.toStop(res, runState);
        }

        Boolean shouldAct = think.getNeedAct() != null && think.getNeedAct();
        if (shouldAct) {
            // 行动！
            return act(currentStep, think.getCurActTools(), chatContext);
        }
        return StepResult.builder().state(StepState.RUNNING).res("任务未结束，继续执行下一步，暂无行动指南").build();
    }

    @Override
    protected TokenStream stepStream(AgentChatContext chatContext) {
        return new ReActTokenStream(
                chatContext,
                getStreamChatModel(),
                getToolExecutor(),
                getToolSpecifications(),
                getAgentSettings(),
                cancelFlags,
                getMiddlewareManager());
    }

    // check任务是否被取消
    private void checkIsCancelled(AgentChatContext chatContext) {
        if (isCancelled(chatContext.getMemoryId())) {
            throw new CancelException(
                    String.format(
                            "会话：%s 进行问答：%s 在此步think前中断",
                            chatContext.getMemoryId(), chatContext.getQuestion()));
        }
    }
}
