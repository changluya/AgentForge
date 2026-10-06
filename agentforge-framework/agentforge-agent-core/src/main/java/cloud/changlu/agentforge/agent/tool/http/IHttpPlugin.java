package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.Map;
import java.util.Properties;

/**
 * @description 提供接口后续可自定义扩展HTTP插件（AgentForge版）
 * @author changlu
 * @date 2026/10/04
 */
public interface IHttpPlugin {

    /**
     * 初始化必备参数配置
     *
     * @param props Properties
     */
    void init(Properties props);

    /**
     * 获取HttpPlugin实体类
     *
     * @return HttpPlugin
     */
    HttpPlugin getHttpPlugin();

    /**
     * 构建plugin name
     *
     * @return String
     */
    String getPluginName();

    /**
     * 快速构建可注入到Agent中的tools封装
     *
     * @return Map&lt;ToolSpecification, ToolExecutor&gt;
     */
    default Map<ToolSpecification, ToolExecutor> buildHttpTools() {
        // 校验特定参数是否传递
        doCheckProps();

        // 获取自定义HttpPlugin的参数配置
        HttpPlugin httpPlugin = this.getHttpPlugin();
        if (httpPlugin == null) {
            throw new IllegalArgumentException(
                    String.format(
                            "插件方法 %s 未实现方法 %s", this.getClass().getName(), "getHttpPlugin()"));
        }
        return HttpToolFactory.buildHttpTools(httpPlugin);
    }

    /** check是否已填充必填参数，如果没有填写则会直接报错提示 */
    void doCheckProps();
}
