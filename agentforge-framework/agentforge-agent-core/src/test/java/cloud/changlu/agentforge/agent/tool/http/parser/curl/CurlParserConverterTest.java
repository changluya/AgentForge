package cloud.changlu.agentforge.agent.tool.http.parser.curl;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.HttpMethod;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterUseType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.RequiredStatus;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @description http模式下curl解析与HttpPlugin互转测试
 * @author changlu
 * @date 2026/10/04
 */
public class CurlParserConverterTest {

    private static final String CURL =
            "curl --location 'https://api.example.com/users/42?verbose=true' "
                    + "--request POST --header 'Content-Type: application/json' "
                    + "--data-raw '{\"name\":\"Alice\",\"age\":30}'";

    @Test
    public void shouldParseCurlCommand() {
        CurlParseResult result = new CurlParser().parse(CURL);

        assertEquals("POST", result.getMethod());
        assertEquals("https://api.example.com/users/42?verbose=true", result.getUrl());
        assertEquals("true", result.getQueryParams().get("verbose"));
        assertEquals("application/json", result.getHeaders().get("Content-Type"));
        assertNotNull(result.getData());
    }

    @Test
    public void shouldParseShortOptions() {
        CurlParseResult result =
                new CurlParser()
                        .parse(
                                "curl -X POST -H 'Content-Type: application/json' -d '{\"a\":1}' https://x/y");

        assertEquals("POST", result.getMethod());
        assertEquals("application/json", result.getHeaders().get("Content-Type"));
        assertEquals("{\"a\":1}", result.getData());
    }

    @Test
    public void shouldConvertCurlToPluginAndBack() {
        HttpPlugin plugin = new CurlToHttpPluginConverter().convert(new CurlParser().parse(CURL));

        assertEquals("https://api.example.com", plugin.getBaseUrl());
        HttpPluginMethod method = plugin.getPluginMethods().get(0);
        assertEquals("/users/42", method.getUri());
        assertEquals(HttpMethod.POST.getValue(), method.getHttpMethodType());
        assertTrue(method.getMethodName().startsWith("executePOST"));

        Set<String> names = new HashSet<String>();
        for (HttpToolParameter parameter : method.getParameters()) {
            names.add(parameter.getMethodParamName());
        }
        assertTrue(names.containsAll(Arrays.asList("verbose", "name", "age")));

        String curl = new HttpPluginToCurlConverter().convert(plugin);
        assertTrue(curl.startsWith("curl "));
        assertTrue(curl.contains("--request POST"));
        assertTrue(curl.contains("https://api.example.com/users/42?verbose=true"));
        assertTrue(curl.contains("\"name\":\"Alice\""));
        assertTrue(curl.contains("\"age\":30"));
    }

    @Test
    public void shouldConvertGetPluginToCurlWithoutRequestFlag() {
        HttpPluginMethod getMethod =
                HttpPluginMethod.builder()
                        .methodName("listUsers")
                        .methodDescription("List users")
                        .httpMethodType(HttpMethod.GET.getValue())
                        .uri("/users")
                        .parameters(
                                Collections.singletonList(
                                        param(
                                                "page",
                                                "page",
                                                ParameterUseType.QUERY,
                                                ParameterType.INTEGER,
                                                1,
                                                RequiredStatus.NOT_REQUIRED)))
                        .build();
        HttpPlugin plugin = plugin(getMethod);

        String curl = new HttpPluginToCurlConverter().convert(plugin);

        assertFalse(curl.contains("--request"));
        assertTrue(curl.contains("https://api.example.com/users?page=1"));
    }

    @Test
    public void shouldRejectMultiMethodConversion() {
        HttpPlugin plugin =
                plugin(
                        HttpPluginMethod.builder()
                                .methodName("a")
                                .httpMethodType(HttpMethod.GET.getValue())
                                .uri("/a")
                                .build(),
                        HttpPluginMethod.builder()
                                .methodName("b")
                                .httpMethodType(HttpMethod.GET.getValue())
                                .uri("/b")
                                .build());

        assertThrows(
                IllegalArgumentException.class,
                () -> new HttpPluginToCurlConverter().convert(plugin));
    }

    private static HttpPlugin plugin(HttpPluginMethod... methods) {
        return HttpPlugin.builder()
                .baseUrl("https://api.example.com")
                .staticHeaders(Collections.<String, String>emptyMap())
                .pluginMethods(Arrays.asList(methods))
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
