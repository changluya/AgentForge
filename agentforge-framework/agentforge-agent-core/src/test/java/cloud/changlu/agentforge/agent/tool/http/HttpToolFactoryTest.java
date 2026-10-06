package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.HttpMethod;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterUseType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.RequiredStatus;
import cloud.changlu.agentforge.model.tool.ToolExecutor;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @description http模式的HttpToolFactory与IHttpPlugin构建工具映射测试
 * @author changlu
 * @date 2026/10/04
 */
public class HttpToolFactoryTest {

    @Test
    public void shouldBuildSpecificationAndExecutorFromPlugin() {
        HttpPlugin plugin = samplePlugin();

        Map<ToolSpecification, ToolExecutor> tools = HttpToolFactory.buildHttpTools(plugin);

        assertEquals(1, tools.size());
        ToolSpecification spec = tools.keySet().iterator().next();
        assertEquals("getUser", spec.name());
        assertEquals("Get a user by id", spec.description());
        assertNotNull(spec.parameters());
        assertTrue(spec.parameters().properties().containsKey("id"));
        assertTrue(spec.parameters().properties().containsKey("verbose"));
        assertEquals(Collections.singletonList("id"), spec.parameters().required());
        assertTrue(tools.get(spec) instanceof HttpToolExecutor);
    }

    @Test
    public void shouldBuildToolsThroughIHttpPlugin() {
        final HttpPlugin plugin = samplePlugin();
        IHttpPlugin httpPlugin =
                new IHttpPlugin() {
                    @Override
                    public void init(Properties props) {
                        // no-op
                    }

                    @Override
                    public HttpPlugin getHttpPlugin() {
                        return plugin;
                    }

                    @Override
                    public String getPluginName() {
                        return "sample";
                    }

                    @Override
                    public void doCheckProps() {
                        // no-op
                    }
                };

        Map<ToolSpecification, ToolExecutor> tools = httpPlugin.buildHttpTools();

        assertEquals(1, tools.size());
    }

    @Test
    public void shouldRejectNullHttpPlugin() {
        IHttpPlugin httpPlugin =
                new IHttpPlugin() {
                    @Override
                    public void init(Properties props) {
                        // no-op
                    }

                    @Override
                    public HttpPlugin getHttpPlugin() {
                        return null;
                    }

                    @Override
                    public String getPluginName() {
                        return "empty";
                    }

                    @Override
                    public void doCheckProps() {
                        // no-op
                    }
                };

        assertThrows(IllegalArgumentException.class, httpPlugin::buildHttpTools);
    }

    private static HttpPlugin samplePlugin() {
        HttpPluginMethod method =
                HttpPluginMethod.builder()
                        .methodName("getUser")
                        .methodDescription("Get a user by id")
                        .httpMethodType(HttpMethod.GET.getValue())
                        .uri("/users/{id}")
                        .parameters(
                                Arrays.asList(
                                        param(
                                                "id",
                                                "id",
                                                ParameterUseType.PATH,
                                                ParameterType.INTEGER,
                                                null,
                                                RequiredStatus.REQUIRED),
                                        param(
                                                "verbose",
                                                "verbose",
                                                ParameterUseType.QUERY,
                                                ParameterType.BOOLEAN,
                                                Boolean.FALSE,
                                                RequiredStatus.NOT_REQUIRED)))
                        .build();

        return HttpPlugin.builder()
                .baseUrl("http://localhost")
                .staticHeaders(Collections.singletonMap("X-Api-Key", "k"))
                .pluginMethods(Collections.singletonList(method))
                .build();
    }

    private static HttpToolParameter param(
            String name,
            String mappedName,
            ParameterUseType useType,
            ParameterType dataType,
            Object defaultValue,
            RequiredStatus required) {
        return HttpToolParameter.builder()
                .methodParamName(name)
                .methodParamDescription(name)
                .mappedName(mappedName)
                .useTypeValue(useType.getValue())
                .dataTypeValue(dataType.getValue())
                .defaultValue(defaultValue)
                .required(required.getCode())
                .build();
    }
}
