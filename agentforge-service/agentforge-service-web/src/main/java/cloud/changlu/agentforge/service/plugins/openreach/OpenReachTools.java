package cloud.changlu.agentforge.service.plugins.openreach;

import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.util.Map;

/**
 * @description OpenReach 联网工具实现类（local 模式）：把 OpenReach 的四个 Web 原语封装为 {@link Tool} 方法，由 {@code
 *     LocalToolFactory} 扫描后注册进 {@code ToolService}。
 *     <p>工具返回 OpenReach 原始 JSON 文本，直接交给模型消费。
 * @author changlu
 * @date 2026/10/07
 */
public class OpenReachTools {

    private final OpenReachClient client;

    public OpenReachTools(OpenReachClient client) {
        this.client = client;
    }

    @Tool(
            name = "webSearch",
            value =
                    "Search the public web for up-to-date information, external facts, official docs, "
                            + "news or articles. Returns JSON candidate results (title/url/snippet). "
                            + "Use this first to discover sources, then call webRead on the promising URL.")
    public String webSearch(
            @P(name = "query", description = "The search query") String query,
            @P(name = "limit", description = "Max number of results, default 5") Integer limit,
            @P(
                            name = "region",
                            description =
                                    "Region route hint: auto/CN/US/JP/SG/GLOBAL, default auto")
                    String region,
            @P(name = "provider", description = "Search provider, default auto (auto-routed)")
                    String provider,
            @P(
                            name = "timeRange",
                            description = "Freshness filter: any/day/week/month/year, default any")
                    String timeRange) {
        return client.search(query, limit, region, provider, timeRange);
    }

    @Tool(
            name = "webImageSearch",
            value =
                    "Search the public web for images. Returns JSON items whose imageUrl is validated "
                            + "and downloadable. Use for illustrations, wallpapers or picture sources.")
    public String webImageSearch(
            @P(name = "query", description = "The image search query") String query,
            @P(name = "limit", description = "Max number of images, default 8") Integer limit,
            @P(
                            name = "region",
                            description =
                                    "Region route hint: auto/CN/US/JP/SG/GLOBAL, default auto")
                    String region,
            @P(name = "provider", description = "Image provider, default auto (auto-routed)")
                    String provider) {
        return client.imageSearch(query, limit, region, provider);
    }

    @Tool(
            name = "webRead",
            value =
                    "Read the main text content of a public web page (HTML/XHTML/plain text on public "
                            + "HTTP/HTTPS 80/443). Returns JSON with the page text. Do not pass private, "
                            + "localhost, non-80/443 or binary/image URLs.")
    public String webRead(
            @P(name = "url", description = "Public web page URL to read") String url,
            @P(name = "maxChars", description = "Max characters to return, default 20000")
                    Integer maxChars) {
        return client.read(url, maxChars);
    }

    @Tool(
            name = "webCurl",
            value =
                    "Read public machine-readable text resources (GitHub REST API, raw source, public "
                            + "JSON/text APIs) with a safe read-only GET/HEAD request. Returns JSON with "
                            + "status/headers/text. Only public HTTP/HTTPS 80/443 is allowed.")
    public String webCurl(
            @P(name = "url", description = "Public machine-readable resource URL") String url,
            @P(name = "method", description = "HTTP method, GET or HEAD, default GET")
                    String method,
            @P(name = "headers", description = "Optional request headers as a JSON object")
                    Map<String, String> headers,
            @P(name = "maxChars", description = "Max characters to return, default 100000")
                    Integer maxChars) {
        return client.curl(url, method, headers, maxChars);
    }
}
