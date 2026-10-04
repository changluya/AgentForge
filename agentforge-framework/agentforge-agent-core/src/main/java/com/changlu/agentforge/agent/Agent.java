package com.changlu.agentforge.agent;

import com.changlu.agentforge.agent.component.middleware.AgentMiddlewareManager;
import com.changlu.agentforge.agent.domain.AgentChatContext;
import com.changlu.agentforge.agent.domain.AgentRequest;
import com.changlu.agentforge.agent.domain.AgentRunState;
import com.changlu.agentforge.agent.domain.AgentSettings;
import com.changlu.agentforge.agent.domain.StopResult;
import com.changlu.agentforge.agent.exception.AgentException;
import com.changlu.agentforge.agent.exception.CancelException;
import com.changlu.agentforge.agent.memory.ChatMemoryProvider;
import com.changlu.agentforge.agent.step.ChatResult;
import com.changlu.agentforge.agent.step.StepResult;
import com.changlu.agentforge.agent.step.StepState;
import com.changlu.agentforge.agent.step.StopResultState;
import com.changlu.agentforge.agent.stream.TokenStream;
import com.changlu.agentforge.model.tool.execution.ToolService;

/**
 * @description Agent抽象类，定义了Agent的核心运行逻辑和步骤
 * @author changlu
 * @date 2026/9/16
 */
public abstract class Agent extends BaseAgent implements IAgent {

    protected abstract ToolService getToolService();

    protected abstract AgentSettings getAgentSettings();

    protected abstract StepResult step(int currentStep, AgentChatContext chatContext)
            throws CancelException;

    protected abstract TokenStream stepStream(AgentChatContext chatContext);

    protected abstract AgentMiddlewareManager getMiddlewareManager();

    /**
     * 针对一个目标结果来进行执行任务
     *
     * @param request AgentRequest
     * @return ChatResult
     */
    @Override
    public ChatResult run(AgentRequest request) {
        Object memoryId = request.getMemoryId();
        String question = request.getQuestion();
        prepareForNewRun(memoryId);
        AgentChatContext chatContext = init(request);

        validateRunParams();

        AgentMiddlewareManager middlewareManager = getMiddlewareManager();

        // 触发中间件初始化完成回调（只执行一次）
        if (middlewareManager != null) {
            middlewareManager.triggerOnInitComplete(chatContext);
        }

        int currentStep = 1;
        // 获取到最大步数限制
        int maxSteps = getAgentSettings().getMaxSteps();

        // 本次运行的最终结果
        String runRes = "";
        String cancelReason = null;
        boolean isFinished = false;
        AgentRunState runState = null;

        try {
            StepResult curStep = StepResult.toRunning();
            while (currentStep <= maxSteps) {
                if (middlewareManager != null) {
                    middlewareManager.triggerBeforeLoop(currentStep, chatContext);
                }
                curStep = step(currentStep, chatContext);
                if (middlewareManager != null) {
                    middlewareManager.triggerAfterLoop(currentStep, curStep, chatContext);
                }

                if (curStep.getState().equals(StepState.STOP)) {
                    isFinished = true;
                    runRes = curStep.getRes();
                    runState =
                            curStep.getRunState() == null
                                    ? AgentRunState.SUCCESS
                                    : curStep.getRunState();
                    break;
                }
                currentStep++;
            }
        } catch (CancelException e) {
            cancelReason = "任务已被用户取消";
            runState = AgentRunState.CANCEL;
            if (middlewareManager != null) {
                middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
            }
        } catch (Exception e) {
            if (isInterruptionRelated(e)) {
                cancelReason = "任务已被中断";
                runState = AgentRunState.CANCEL;
                // 中断相关异常按取消处理，停止回调统一走下面的 cancelReason 分支，避免重复触发
                if (middlewareManager != null) {
                    middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
                }
            } else {
                // 非中断相关的异常，先通知中间件再向外抛出
                if (middlewareManager != null) {
                    middlewareManager.triggerOnLoopError(currentStep, e, chatContext);
                    middlewareManager.triggerOnStopWithError(currentStep, e, chatContext);
                }
                throw new AgentException("Agent执行失败: " + e.getMessage(), e);
            }
        } finally {
            // 取消标志只在本次运行内生效，结束后统一清理
            clearCancelFlag(memoryId);
        }

        // 判断是取消中断还是步长中断情况
        if (cancelReason != null) {
            runRes = cancelReason;
            runState = AgentRunState.CANCEL;
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.CANCEL, runRes), chatContext);
            }
        } else if (!isFinished && currentStep > maxSteps) {
            runRes = "执行步长达到限制，请重新询问你的问题！";
            runState = AgentRunState.MAX_STEPS;
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.MAX_STEPS, runRes), chatContext);
            }
        } else if (isFinished) {
            // 正常结束（模型不再要求调用工具）
            if (middlewareManager != null) {
                middlewareManager.triggerOnStop(
                        currentStep, stopResult(StopResultState.NORMAL, runRes), chatContext);
            }
        }

        return ChatResult.toFinished(
                question, runRes, runState == null ? AgentRunState.SUCCESS : runState);
    }

    /**
     * 流式运行
     *
     * @param request AgentRequest
     * @return TokenStream
     */
    @Override
    public TokenStream runStream(AgentRequest request) {
        prepareForNewRun(request.getMemoryId());
        AgentChatContext chatContext = init(request);

        validateRunStreamParams();
        return stepStream(chatContext);
    }

    private void validateRunParams() {
        if (getChatModel() == null) {
            throw new AgentException("chatModel is null, need set");
        }
        if (getChatMemoryProvider() == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
        if (getToolService() == null) {
            throw new AgentException("toolService is null, need set");
        }
    }

    private void validateRunStreamParams() {
        ChatMemoryProvider chatMemoryProvider = getChatMemoryProvider();
        if (chatMemoryProvider == null) {
            throw new AgentException("ChatMemoryProvider is null, need set");
        }
    }

    // 判断异常是否与线程中断相关，中断统一按取消处理而不向外抛出
    private boolean isInterruptionRelated(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static StopResult stopResult(StopResultState state, String runRes) {
        return StopResult.builder().stopResultState(state).runRes(runRes).build();
    }
}
