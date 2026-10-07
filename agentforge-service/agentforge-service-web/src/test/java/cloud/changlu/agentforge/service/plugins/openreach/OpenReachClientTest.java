package cloud.changlu.agentforge.service.plugins.openreach;

import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * @description OpenReach 客户端请求构造与错误处理单测（使用假 Transport，零网络）
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachClientTest {

    @Test
    public void searchBuildsRequestAndReturnsBody() {
        RecordingTransport transport = new RecordingTransport();
        transport.body = "{\"items\":[]}";
        OpenReachClient client = new OpenReachClient("http://openreach.local:8089/", transport);

        String result = client.search("spring ai", 3, null, null, "month");

        assertEquals("{\"items\":[]}", result);
        assertEquals("http://openreach.local:8089/api/web/search", transport.lastRequest.url());
        assertEquals("POST", transport.lastRequest.method());
        assertEquals("application/json", transport.lastRequest.headers().get("Content-Type"));
        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("spring ai", payload.get("query"));
        assertEquals(3L, ((Number) payload.get("limit")).longValue());
        assertEquals("auto", payload.get("region"));
        assertEquals("auto", payload.get("provider"));
        assertEquals("month", payload.get("timeRange"));
    }

    @Test
    public void readAppliesDefaultMaxChars() {
        RecordingTransport transport = new RecordingTransport();
        OpenReachClient client = new OpenReachClient("http://openreach.local:8089", transport);

        client.read("https://example.com/article", null);

        assertEquals("http://openreach.local:8089/api/web/read", transport.lastRequest.url());
        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("https://example.com/article", payload.get("url"));
        assertEquals(20000L, ((Number) payload.get("maxChars")).longValue());
    }

    @Test
    public void curlDefaultsToGetAndEmptyHeaders() {
        RecordingTransport transport = new RecordingTransport();
        OpenReachClient client = new OpenReachClient("http://openreach.local:8089", transport);

        client.curl("https://api.github.com/repos/spring-projects/spring-boot", null, null, null);

        assertEquals("http://openreach.local:8089/api/web/curl", transport.lastRequest.url());
        Map<String, Object> payload = Json.parseObject(transport.lastRequest.body());
        assertEquals("GET", payload.get("method"));
        assertEquals(100000L, ((Number) payload.get("maxChars")).longValue());
        assertEquals("{}", Json.stringify(payload.get("headers")));
    }

    @Test(expected = OpenReachException.class)
    public void nonSuccessStatusThrows() {
        RecordingTransport transport = new RecordingTransport();
        transport.status = 502;
        transport.body = "{\"code\":\"UPSTREAM_ERROR\"}";
        OpenReachClient client = new OpenReachClient("http://openreach.local:8089", transport);

        client.search("q", null, null, null, null);
    }

    @Test(expected = OpenReachException.class)
    public void emptyBaseUrlThrows() {
        OpenReachClient client = new OpenReachClient("", new RecordingTransport());

        client.search("q", null, null, null, null);
    }

    private static final class RecordingTransport implements HttpTransport {

        private HttpRequest lastRequest;
        private int status = 200;
        private String body = "{}";

        @Override
        public HttpResponse execute(HttpRequest request) {
            this.lastRequest = request;
            return new HttpResponse(status, body);
        }
    }
}
