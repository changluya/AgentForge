package cloud.changlu.agentforge.service.plugins.openreach;

import cloud.changlu.agentforge.model.internal.json.Json;

import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @description OpenReach 真实服务联调测试（默认访问 http://openreach.changlu.cloud）。
 *     <p>可用 {@code -Dagentforge.openreach.live=false} 跳过，或用 {@code
 *     -Dagentforge.openreach.base-url=<url>} 指定其他地址。
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachLiveTest {

    private static final String DEFAULT_BASE_URL = "http://openreach.changlu.cloud";

    private static OpenReachClient client() {
        Assume.assumeTrue(
                "set -Dagentforge.openreach.live=false to skip",
                Boolean.parseBoolean(System.getProperty("agentforge.openreach.live", "true")));
        String baseUrl = System.getProperty("agentforge.openreach.base-url", DEFAULT_BASE_URL);
        return new OpenReachClient(baseUrl);
    }

    @Test
    public void searchReturnsCandidates() {
        String body = client().search("Spring Boot official documentation", 3, "US", "auto", "any");

        Map<String, Object> result = Json.parseObject(body);
        List<Object> items = Json.array(result.get("items"));
        assertNotNull("search response has no items", items);
        assertFalse("search returned no results", items.isEmpty());
        assertNotNull(Json.object(items.get(0)).get("url"));
    }

    @Test
    public void readReturnsPageContent() {
        String body = client().read("https://spring.io/projects/spring-boot/", 20000);

        Map<String, Object> result = Json.parseObject(body);
        String content = Json.string(result.get("content"));
        assertNotNull("read response has no content", content);
        assertTrue("read content is empty", content.length() > 0);
    }

    @Test
    public void curlGitHubApi() {
        String body =
                client().curl(
                                "https://api.github.com/repos/spring-projects/spring-boot",
                                "GET",
                                null,
                                50000);

        Map<String, Object> result = Json.parseObject(body);
        String curlBody = Json.string(result.get("body"));
        assertNotNull(curlBody);
        assertTrue("unexpected body: " + curlBody, curlBody.contains("spring-boot"));
    }
}
