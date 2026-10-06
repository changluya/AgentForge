package cloud.changlu.agentforge.agent.tool.local;

import cloud.changlu.agentforge.agent.tool.local.support.LoggingToolExecutor;
import cloud.changlu.agentforge.model.tool.Tool;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecifications;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @description 本地方法工具工厂类（AgentForge版）：扫描对象上的{@link Tool}方法并构建工具映射
 * @author changlu
 * @date 2026/10/04
 */
public class LocalToolFactory {

    private LocalToolFactory() {}

    /**
     * 构建本地工具映射
     *
     * @param objectsWithTools 包含@Tool注解方法的对象集合
     * @return Map&lt;ToolSpecification, ToolExecutor&gt; 工具映射
     */
    public static Map<ToolSpecification, ToolExecutor> buildLocalTools(
            Collection<Object> objectsWithTools) {
        return buildLocalToolsWithLogging(objectsWithTools, false);
    }

    /**
     * 构建带日志记录的本地工具映射
     *
     * @param objectsWithTools 包含@Tool注解方法的对象集合
     * @param hasLogging 是否启用执行日志装饰
     * @return Map&lt;ToolSpecification, ToolExecutor&gt; 工具映射
     */
    public static Map<ToolSpecification, ToolExecutor> buildLocalToolsWithLogging(
            Collection<Object> objectsWithTools, boolean hasLogging) {
        Map<ToolSpecification, ToolExecutor> result =
                new HashMap<ToolSpecification, ToolExecutor>();

        if (objectsWithTools == null || objectsWithTools.isEmpty()) {
            return result;
        }

        for (Object objectWithTool : objectsWithTools) {
            if (objectWithTool instanceof Class) {
                throw new IllegalArgumentException(
                        "Tool '" + objectWithTool + "' must be an object, not a class");
            }

            Method[] methods = objectWithTool.getClass().getDeclaredMethods();
            for (Method method : methods) {
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolSpecification toolSpecification =
                            ToolSpecifications.toolSpecificationFrom(method);
                    if (result.containsKey(toolSpecification)) {
                        throw new IllegalArgumentException(
                                "Duplicated definition for tool: " + toolSpecification.name());
                    }

                    // 创建（可选带日志的）ToolExecutor
                    ToolExecutor toolExecutor;
                    if (hasLogging) {
                        toolExecutor =
                                new LoggingToolExecutor(
                                        new LocalToolExecutor(objectWithTool, method));
                    } else {
                        toolExecutor = new LocalToolExecutor(objectWithTool, method);
                    }
                    result.put(toolSpecification, toolExecutor);
                }
            }
        }

        return result;
    }
}
