package cloud.changlu.agentforge.studio.stream;

import cloud.changlu.agentforge.agent.component.middleware.IStreamingIAgentMiddleware;
import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.domain.StopResult;
import cloud.changlu.agentforge.agent.stream.PartialThinking;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.studio.protocol.StreamEventResponse;
import cloud.changlu.agentforge.studio.protocol.ToolCallResult;
import cloud.changlu.agentforge.studio.protocol.processor.AiErrorProcessor;
import cloud.changlu.agentforge.studio.protocol.processor.AiResponseProcessor;
import cloud.changlu.agentforge.studio.protocol.processor.AiSessionProcessor;
import cloud.changlu.agentforge.studio.protocol.processor.think.AiThinkProcessor;
import cloud.changlu.agentforge.studio.protocol.processor.tool.AiToolCallProcessor;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Studio 统一流式中间件：实现 {@link IStreamingIAgentMiddleware}，由 Agent 在流式执行各阶段回调驱动， 各事件场景委托给对应 processor
 * 构造 {@link StreamEventResponse} 后写出到当前会话的 SSE。
 */
public class StudioStreamingMiddleware implements IStreamingIAgentMiddleware {

    /** memoryId -> 本次运行的会话输出上下文 */
    private final Map<Object, SessionContext> sessions =
            new ConcurrentHashMap<Object, SessionContext>();

    /** 请求进入时绑定当前会话的 SSE 输出 */
    public void bind(Object memoryId, SseEmitter emitter) {
        SessionContext previous = sessions.put(memoryId, new SessionContext(emitter));
        if (previous != null) {
            previous.close();
        }
    }

    /** 请求结束时解绑会话输出 */
    public void unbind(Object memoryId) {
        SessionContext context = sessions.remove(memoryId);
        if (context != null) {
            context.dispose();
        }
    }

    /** 同步启动异常兜底：把错误推给前端并解绑 */
    public void onError(Object memoryId, Throwable error) {
        SessionContext context = sessions.get(memoryId);
        if (context != null) {
            context.send(AiErrorProcessor.respError(safeMessage(error)));
        }
        unbind(memoryId);
    }

    @Override
    public void onInitComplete(AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(
                    AiSessionProcessor.sessionCreated(String.valueOf(chatContext.getMemoryId())));
        }
    }

    @Override
    public void onPartialThinking(
            int currentStep, PartialThinking partialThinking, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context == null || partialThinking == null || partialThinking.text() == null) {
            return;
        }
        context.send(AiThinkProcessor.respThink(partialThinking.text(), false));
    }

    @Override
    public void onPartialResponse(
            int currentStep, String partialResponse, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context == null || partialResponse == null) {
            return;
        }
        context.send(AiResponseProcessor.respText(partialResponse, false));
    }

    @Override
    public void beforeToolExecution(
            ToolExecutionRequest toolRequest, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null && toolRequest != null) {
            context.startTool(toolRequest.id());
        }
    }

    @Override
    public String afterToolExecution(
            ToolExecutionRequest toolRequest, String toolResult, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.sendTool(toolRequest, toolResult, false);
        }
        return toolResult;
    }

    @Override
    public void onToolExecutionError(
            ToolExecutionRequest toolRequest, Throwable error, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.sendTool(toolRequest, safeMessage(error), true);
        }
    }

    @Override
    public void onStop(int currentStep, StopResult stopResult, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(AiResponseProcessor.respEnd(true));
        }
        unbind(chatContext == null ? null : chatContext.getMemoryId());
    }

    @Override
    public void onStopWithError(int currentStep, Throwable error, AgentChatContext chatContext) {
        SessionContext context = state(chatContext);
        if (context != null) {
            context.send(AiErrorProcessor.respError(safeMessage(error)));
        }
        unbind(chatContext == null ? null : chatContext.getMemoryId());
    }

    private SessionContext state(AgentChatContext chatContext) {
        return chatContext == null ? null : sessions.get(chatContext.getMemoryId());
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 单次运行的 SSE 输出上下文，内部维护关闭状态与工具耗时 */
    private static final class SessionContext {

        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Map<String, Long> toolStartTimes = new ConcurrentHashMap<String, Long>();

        private SessionContext(SseEmitter emitter) {
            this.emitter = emitter;
            this.emitter.onCompletion(() -> closed.set(true));
            this.emitter.onTimeout(() -> closed.set(true));
        }

        private void startTool(String toolCallId) {
            if (toolCallId != null) {
                toolStartTimes.put(toolCallId, System.currentTimeMillis());
            }
        }

        private void sendTool(ToolExecutionRequest request, String result, boolean failed) {
            long duration = 0L;
            if (request != null && request.id() != null) {
                Long start = toolStartTimes.remove(request.id());
                if (start != null) {
                    duration = System.currentTimeMillis() - start;
                }
            }
            ToolCallResult toolCallResult = new ToolCallResult();
            if (request != null) {
                toolCallResult.setToolCallId(request.id());
                toolCallResult.setToolName(request.name());
                toolCallResult.setInputParams(request.arguments());
            }
            toolCallResult.setDescription(failed ? "工具执行失败" : "工具执行完成");
            toolCallResult.setOutputResult(result);
            toolCallResult.setDuration(duration);
            send(AiToolCallProcessor.toolCallCard(toolCallResult, true));
        }

        private void send(StreamEventResponse event) {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().data(event));
            } catch (IOException | IllegalStateException ignored) {
                closed.set(true);
            }
        }

        private void close() {
            closed.set(true);
        }

        private void dispose() {
            if (closed.compareAndSet(false, true)) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端可能已断开
                }
            }
        }
    }
}
