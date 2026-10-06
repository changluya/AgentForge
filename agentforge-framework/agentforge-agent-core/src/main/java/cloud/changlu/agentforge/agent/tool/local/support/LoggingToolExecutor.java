package cloud.changlu.agentforge.agent.tool.local.support;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.tool.ToolExecutor;

import java.util.logging.Logger;

/**
 * @description 本地工具执行日志装饰器（AgentForge版）：在工具执行前后打印入参、出参与耗时
 * @author changlu
 * @date 2026/10/04
 */
public class LoggingToolExecutor implements ToolExecutor {

    private static final Logger log = Logger.getLogger(LoggingToolExecutor.class.getName());

    private final ToolExecutor delegate;

    public LoggingToolExecutor(ToolExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public String execute(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
        // 记录入参
        log.fine("=== 工具执行请求 ===");
        log.fine("工具名称: " + toolExecutionRequest.name());
        log.fine("参数: " + toolExecutionRequest.arguments());
        log.fine("记忆ID: " + memoryId);
        log.fine("===================");

        long startTime = System.currentTimeMillis();
        String result = delegate.execute(toolExecutionRequest, memoryId);
        long endTime = System.currentTimeMillis();

        // 记录出参和执行时间
        log.fine("=== 工具执行响应 ===");
        log.fine("结果: " + result);
        log.fine("耗时: " + (endTime - startTime) + " 毫秒");
        log.fine("===================");

        return result;
    }
}
