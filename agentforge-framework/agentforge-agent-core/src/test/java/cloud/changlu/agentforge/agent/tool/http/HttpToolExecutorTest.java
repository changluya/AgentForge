package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.HttpMethod;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterType;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums.ParameterUseType;
import cloud.changlu.agentforge.agent.tool.http.support.RecordingHttpTransport;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @description http模式的HttpToolExecutor单元测试：通过RecordingHttpTransport验证URL、请求头、请求体与重试
 * @author changlu
 * @date 2026/10/04
 */
public class HttpToolExecutorTest {

    @Test
    public void shouldSendGetQueryParameters() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "q",
                config("query", ParameterUseType.QUERY, ParameterType.STRING, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/echo",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        String result =
                executor.execute(
                        ToolExecutionRequest.from("1", "search", "{\"q\":\"hello world\"}"), null);

        assertEquals("ok", result);
        HttpRequest request = transport.lastRequest();
        assertEquals("GET", request.method());
        assertTrue(request.url().startsWith("http://localhost/echo?"));
        assertTrue(request.url().contains("query=hello+world"));
        assertTrue(request.body().isEmpty());
    }

    @Test
    public void shouldSubstitutePathParameters() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "user"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "id", config("id", ParameterUseType.PATH, ParameterType.INTEGER, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/users/{id}",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        String result =
                executor.execute(ToolExecutionRequest.from("1", "getUser", "{\"id\":42}"), null);

        assertEquals("user", result);
        assertEquals("http://localhost/users/42", transport.lastRequest().url());
    }

    @Test
    public void shouldSendJsonBodyForPost() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "created"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "name",
                config("name", ParameterUseType.BODY, ParameterType.STRING, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/users",
                        HttpMethod.POST.getValue(),
                        null,
                        configs,
                        transport);

        String result =
                executor.execute(
                        ToolExecutionRequest.from("1", "createUser", "{\"name\":\"Alice\"}"), null);

        assertEquals("created", result);
        HttpRequest request = transport.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("application/json", request.headers().get("Content-Type"));
        assertEquals("{\"name\":\"Alice\"}", request.body());
    }

    @Test
    public void shouldConvertArrayAndObjectBodyValues() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "tags",
                config("tags", ParameterUseType.BODY, ParameterType.ARRAY, null, required()));
        configs.put(
                "meta",
                config("meta", ParameterUseType.BODY, ParameterType.OBJECT, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/items",
                        HttpMethod.POST.getValue(),
                        null,
                        configs,
                        transport);

        executor.execute(
                ToolExecutionRequest.from(
                        "1", "createItem", "{\"tags\":[\"a\",\"b\"],\"meta\":{\"k\":\"v\"}}"),
                null);

        String body = transport.lastRequest().body();
        assertTrue(body.contains("\"tags\":[\"a\",\"b\"]"));
        assertTrue(body.contains("\"meta\":{\"k\":\"v\"}"));
    }

    @Test
    public void shouldParseJsonEncodedArrayString() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "tags",
                config("tags", ParameterUseType.BODY, ParameterType.ARRAY, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/items",
                        HttpMethod.POST.getValue(),
                        null,
                        configs,
                        transport);

        executor.execute(
                ToolExecutionRequest.from(
                        "1", "createItem", "{\"tags\":\"[\\\"a\\\",\\\"b\\\"]\"}"),
                null);

        assertTrue(transport.lastRequest().body().contains("\"tags\":[\"a\",\"b\"]"));
    }

    @Test
    public void shouldAddStaticAndDynamicHeaders() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, String> staticHeaders = new LinkedHashMap<String, String>();
        staticHeaders.put("X-Static", "s");

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "trace",
                config("X-Trace", ParameterUseType.HEADER, ParameterType.STRING, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/ping",
                        HttpMethod.GET.getValue(),
                        staticHeaders,
                        configs,
                        transport);

        executor.execute(ToolExecutionRequest.from("1", "ping", "{\"trace\":\"t-1\"}"), null);

        Map<String, String> headers = transport.lastRequest().headers();
        assertEquals("s", headers.get("X-Static"));
        assertEquals("t-1", headers.get("X-Trace"));
    }

    @Test
    public void shouldApplyDefaultValueWhenArgumentMissing() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "limit",
                config("limit", ParameterUseType.QUERY, ParameterType.INTEGER, 10, optional()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/list",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        executor.execute(ToolExecutionRequest.from("1", "list", "{}"), null);

        assertTrue(transport.lastRequest().url().contains("limit=10"));
    }

    @Test
    public void shouldThrowWhenRequiredArgumentMissing() {
        RecordingHttpTransport transport = new RecordingHttpTransport();

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "q",
                config("query", ParameterUseType.QUERY, ParameterType.STRING, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/search",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(ToolExecutionRequest.from("1", "search", "{}"), null));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void shouldRetryOnceOnConnectionResetForGet() throws IOException {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new IOException(new SocketException("Connection reset by peer")));
        transport.enqueue(new HttpResponse(200, "retried"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "q",
                config("query", ParameterUseType.QUERY, ParameterType.STRING, null, optional()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/search",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        String result =
                executor.execute(ToolExecutionRequest.from("1", "search", "{\"q\":\"x\"}"), null);

        assertEquals("retried", result);
        assertEquals(2, transport.requests.size());
    }

    @Test
    public void shouldReturnFailureTextWhenPostFails() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new IOException("boom"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "name",
                config("name", ParameterUseType.BODY, ParameterType.STRING, null, required()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/users",
                        HttpMethod.POST.getValue(),
                        null,
                        configs,
                        transport);

        String result =
                executor.execute(
                        ToolExecutionRequest.from("1", "createUser", "{\"name\":\"Alice\"}"), null);

        assertTrue(result.startsWith("HTTP请求失败: "));
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void shouldNotSendJsonBodyForGetEvenWithBodyConfig() {
        RecordingHttpTransport transport = new RecordingHttpTransport();
        transport.enqueue(new HttpResponse(200, "ok"));

        Map<String, HttpToolExecutor.ParameterConfig> configs =
                new LinkedHashMap<String, HttpToolExecutor.ParameterConfig>();
        configs.put(
                "filter",
                config("filter", ParameterUseType.BODY, ParameterType.STRING, null, optional()));

        HttpToolExecutor executor =
                new HttpToolExecutor(
                        "http://localhost/list",
                        HttpMethod.GET.getValue(),
                        null,
                        configs,
                        transport);

        executor.execute(ToolExecutionRequest.from("1", "list", "{\"filter\":\"all\"}"), null);

        HttpRequest request = transport.lastRequest();
        assertEquals("GET", request.method());
        assertTrue(request.body().isEmpty());
        assertFalse(request.headers().containsKey("Content-Type"));
    }

    private static int required() {
        return HttpPluginEnums.RequiredStatus.REQUIRED.getCode();
    }

    private static int optional() {
        return HttpPluginEnums.RequiredStatus.NOT_REQUIRED.getCode();
    }

    private static HttpToolExecutor.ParameterConfig config(
            String mappedName,
            ParameterUseType useType,
            ParameterType dataType,
            Object defaultValue,
            int required) {
        return new HttpToolExecutor.ParameterConfig(
                mappedName, useType, dataType, defaultValue, required);
    }
}
