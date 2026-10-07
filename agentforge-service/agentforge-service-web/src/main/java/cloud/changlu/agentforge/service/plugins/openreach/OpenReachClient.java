package cloud.changlu.agentforge.service.plugins.openreach;

import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @description OpenReach 客户端：封装 search / image-search / read / curl 四个公开 JSON API， 只依赖 AgentForge 的
 *     HttpTransport SPI，无第三方 HTTP 依赖。
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachClient {

    private static final String USER_AGENT = "agentforge-service/1.0.0";

    private final String baseUrl;
    private final HttpTransport transport;

    public OpenReachClient(String baseUrl) {
        this(baseUrl, new JdkHttpTransport());
    }

    public OpenReachClient(String baseUrl, HttpTransport transport) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.transport = transport;
    }

    public String search(
            String query, Integer limit, String region, String provider, String timeRange) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("query", query);
        payload.put("limit", limit == null ? 5 : limit);
        payload.put("region", defaultValue(region, "auto"));
        payload.put("provider", defaultValue(provider, "auto"));
        payload.put("timeRange", defaultValue(timeRange, "any"));
        return post("/api/web/search", payload);
    }

    public String imageSearch(String query, Integer limit, String region, String provider) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("query", query);
        payload.put("limit", limit == null ? 8 : limit);
        payload.put("region", defaultValue(region, "auto"));
        payload.put("provider", defaultValue(provider, "auto"));
        return post("/api/web/image-search", payload);
    }

    public String read(String url, Integer maxChars) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("url", url);
        payload.put("maxChars", maxChars == null ? 20000 : maxChars);
        return post("/api/web/read", payload);
    }

    public String curl(String url, String method, Map<String, String> headers, Integer maxChars) {
        String normalizedMethod =
                method == null || method.trim().isEmpty() ? "GET" : method.trim().toUpperCase();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("url", url);
        payload.put("method", normalizedMethod);
        payload.put(
                "headers",
                headers == null
                        ? Collections.<String, String>emptyMap()
                        : new LinkedHashMap<String, String>(headers));
        payload.put("maxChars", maxChars == null ? 100000 : maxChars);
        return post("/api/web/curl", payload);
    }

    private String post(String path, Map<String, Object> payload) {
        if (baseUrl.isEmpty()) {
            throw new OpenReachException(
                    "OpenReach base url is not configured. Set agentforge.openreach.base-url "
                            + "(or OPENREACH_BASE_URL).");
        }
        HttpRequest request =
                HttpRequest.builder()
                        .url(trimTrailingSlash(baseUrl) + path)
                        .method("POST")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .body(Json.stringify(payload))
                        .build();
        HttpResponse response;
        try {
            response = transport.execute(request);
        } catch (IOException e) {
            throw new OpenReachException(
                    "Cannot reach OpenReach at " + baseUrl + ": " + e.getMessage(), e);
        }
        if (!response.isSuccessful()) {
            throw new OpenReachException(
                    "OpenReach "
                            + path
                            + " failed: HTTP "
                            + response.statusCode()
                            + " "
                            + response.body());
        }
        return response.body();
    }

    static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
