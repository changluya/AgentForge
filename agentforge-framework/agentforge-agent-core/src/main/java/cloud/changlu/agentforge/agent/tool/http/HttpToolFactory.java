package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolParameters;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description http模式的工具工厂类（AgentForge版）：把HttpPlugin转换为AgentForge的
 *     ToolSpecification与ToolExecutor映射，是http模式对外唯一构建入口
 * @author changlu
 * @date 2026/10/04
 */
public class HttpToolFactory {

    private HttpToolFactory() {}

    /**
     * 将多个HttpPlugin转换为统一的Map<ToolSpecification, ToolExecutor>
     *
     * @param httpPlugins HttpPlugin列表
     * @return 合并后的工具映射
     */
    public static Map<ToolSpecification, ToolExecutor> buildHttpTools(
            List<HttpPlugin> httpPlugins) {
        return buildHttpTools(httpPlugins, null);
    }

    /**
     * 将多个HttpPlugin转换为统一的Map<ToolSpecification, ToolExecutor>，可注入自定义传输实现
     *
     * @param httpPlugins HttpPlugin列表
     * @param httpTransport HTTP传输实现，为null时使用默认的JDK实现
     * @return 合并后的工具映射
     */
    public static Map<ToolSpecification, ToolExecutor> buildHttpTools(
            List<HttpPlugin> httpPlugins, HttpTransport httpTransport) {
        Map<ToolSpecification, ToolExecutor> result =
                new HashMap<ToolSpecification, ToolExecutor>();

        if (httpPlugins == null || httpPlugins.isEmpty()) {
            return result;
        }

        for (HttpPlugin httpPlugin : httpPlugins) {
            if (httpPlugin != null) {
                result.putAll(buildHttpTools(httpPlugin, httpTransport));
            }
        }

        return result;
    }

    /**
     * 构建单个HttpPlugin对应的工具映射
     *
     * @param httpPlugin HttpPlugin
     * @return ToolSpecification 方法定义、ToolExecutor 方法执行器（http插件执行器）
     */
    public static Map<ToolSpecification, ToolExecutor> buildHttpTools(HttpPlugin httpPlugin) {
        return buildHttpTools(httpPlugin, null);
    }

    /**
     * 构建单个HttpPlugin对应的工具映射，可注入自定义传输实现
     *
     * @param httpPlugin HttpPlugin
     * @param httpTransport HTTP传输实现，为null时使用默认的JDK实现
     * @return ToolSpecification 方法定义、ToolExecutor 方法执行器（http插件执行器）
     */
    public static Map<ToolSpecification, ToolExecutor> buildHttpTools(
            HttpPlugin httpPlugin, HttpTransport httpTransport) {
        Map<ToolSpecification, ToolExecutor> result =
                new HashMap<ToolSpecification, ToolExecutor>();

        String baseUrl = httpPlugin.getBaseUrl();
        Map<String, String> staticHeaders = httpPlugin.getStaticHeaders();
        List<HttpPluginMethod> pluginMethods = httpPlugin.getPluginMethods();
        if (pluginMethods == null || pluginMethods.isEmpty()) {
            return result;
        }

        for (HttpPluginMethod httpPluginMethod : pluginMethods) {
            ToolSpecification toolSpecification =
                    buildToolSpecification(
                            httpPluginMethod.getMethodName(),
                            httpPluginMethod.getMethodDescription(),
                            httpPluginMethod.getParameters());

            Map<String, HttpToolExecutor.ParameterConfig> parameterConfigs =
                    new HashMap<String, HttpToolExecutor.ParameterConfig>();
            List<HttpToolParameter> parameters = httpPluginMethod.getParameters();
            if (parameters != null) {
                for (HttpToolParameter parameter : parameters) {
                    parameterConfigs.put(
                            parameter.getMethodParamName(), parameter.toParameterConfig());
                }
            }

            HttpToolExecutor httpToolExecutor =
                    new HttpToolExecutor(
                            baseUrl + httpPluginMethod.getUri(),
                            httpPluginMethod.getHttpMethodType(),
                            staticHeaders,
                            parameterConfigs,
                            httpTransport);

            result.put(toolSpecification, httpToolExecutor);
        }
        return result;
    }

    private static ToolSpecification buildToolSpecification(
            String methodName, String methodDescription, List<HttpToolParameter> parameters) {
        ToolParameters.Builder builder = ToolParameters.builder();
        if (parameters != null) {
            for (HttpToolParameter param : parameters) {
                boolean required =
                        HttpPluginEnums.RequiredStatus.REQUIRED
                                .getCode()
                                .equals(param.getRequired());
                builder.addProperty(param.getMethodParamName(), param.toJsonSchema(), required);
            }
        }

        return ToolSpecification.builder()
                .name(methodName)
                .description(methodDescription)
                .parameters(builder.build())
                .build();
    }
}
