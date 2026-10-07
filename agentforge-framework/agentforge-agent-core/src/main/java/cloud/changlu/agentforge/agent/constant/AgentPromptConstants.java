package cloud.changlu.agentforge.agent.constant;

/**
 * @description Agent 提示词常量
 * @author changlu
 * @date 2026/10/07
 */
public final class AgentPromptConstants {

    private AgentPromptConstants() {}

    /**
     * 并发执行工具提示语。
     *
     * <p>当「工具数量 &gt; 1」且「enableConcurrentToolExecution 开关开启（默认开启）」时，追加到系统提示词，
     * 引导模型在多个工具无依赖关系时于同一轮并发调用它们。
     */
    public static final String CONCURRENT_TOOL_EXECUTION_PROMPT =
            "\n\n# Concurrent Tool Execution\n"
                    + "You can call multiple tools in a single turn to improve efficiency. "
                    + "When several tools have no dependency on each other, call them concurrently "
                    + "in the same response. For example, if you need both the weather and the news, "
                    + "call both tools in one response.";
}
