package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.HttpMethod;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterUseType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.RequiredStatus;
import cloud.changlu.agentforge.agent.tool.http.support.RecordingHttpTransport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @description http模式端到端闭环测试：对齐文档 4.1 的 getWeather 示例，从 HttpPlugin 构建 → ToolService 注册 → 模型
 *     function call → 命中执行器 → 组装并发出 GET 请求 → 回灌响应，全程用 RecordingHttpTransport 离线验证
 * @author changlu
 * @date 2026/10/05
 */
public class HttpToolLoopTest {

    @Test
    public void shouldCloseTheWeatherLoopThroughToolService() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "{\"city\":\"杭州\",\"temp\":26,\"text\":\"晴\"}"));

        // 步骤①：声明式插件 → spec + executor（把 city 映射到接口的 q，落到 QUERY）
        Map<ToolSpecification, ToolExecutor> tools =
                HttpToolFactory.buildHttpTools(weatherPlugin(), transport);

        // 步骤①（续）：注册进统一工具表
        ToolService toolService = new ToolService();
        toolService.tools(tools);
        assertTrue(toolService.toolExecutors().containsKey("getWeather"));

        // 步骤③~⑦：模型 function call → 命中执行器 → 组装 GET → 返回响应体文本
        String result =
                toolService
                        .toolExecutors()
                        .get("getWeather")
                        .execute(
                                ToolExecutionRequest.from(
                                        "call_1", "getWeather", "{\"city\":\"杭州\"}"),
                                null);

        assertEquals("{\"city\":\"杭州\",\"temp\":26,\"text\":\"晴\"}", result);

        HttpRequest request = transport.lastRequest();
        assertEquals("GET", request.method());
        assertEquals("https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E", request.url());
        assertEquals("Bearer token", request.headers().get("Authorization"));
        assertTrue(request.body().isEmpty());
    }

    private static HttpPlugin weatherPlugin() {
        HttpPluginMethod method =
                HttpPluginMethod.builder()
                        .methodName("getWeather")
                        .methodDescription("查询城市天气")
                        .httpMethodType(HttpMethod.GET.getValue())
                        .uri("/v1/weather")
                        .parameters(
                                Collections.singletonList(
                                        HttpToolParameter.builder()
                                                .methodParamName("city")
                                                .methodParamDescription("城市名")
                                                .mappedName("q")
                                                .useTypeValue(ParameterUseType.QUERY.getValue())
                                                .dataTypeValue(ParameterType.STRING.getValue())
                                                .required(RequiredStatus.REQUIRED.getCode())
                                                .build()))
                        .build();

        return HttpPlugin.builder()
                .baseUrl("https://api.example.com")
                .staticHeaders(Collections.singletonMap("Authorization", "Bearer token"))
                .pluginMethods(Collections.singletonList(method))
                .build();
    }
}
