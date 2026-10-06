package cloud.changlu.agentforge.agent.tool.http.domain;

import java.util.List;
import java.util.Map;

/**
 * @description HTTP插件定义：由基础服务地址、公共请求头以及一组插件方法组成
 * @author changlu
 * @date 2026/10/04
 */
public class HttpPlugin {

    // 基础服务名称
    private String baseUrl;

    // 公共请求头
    private Map<String, String> staticHeaders;

    // 包含多个插件方法
    private List<HttpPluginMethod> pluginMethods;

    public HttpPlugin() {}

    public HttpPlugin(
            String baseUrl,
            Map<String, String> staticHeaders,
            List<HttpPluginMethod> pluginMethods) {
        this.baseUrl = baseUrl;
        this.staticHeaders = staticHeaders;
        this.pluginMethods = pluginMethods;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Map<String, String> getStaticHeaders() {
        return staticHeaders;
    }

    public List<HttpPluginMethod> getPluginMethods() {
        return pluginMethods;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String baseUrl;
        private Map<String, String> staticHeaders;
        private List<HttpPluginMethod> pluginMethods;

        private Builder() {}

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder staticHeaders(Map<String, String> staticHeaders) {
            this.staticHeaders = staticHeaders;
            return this;
        }

        public Builder pluginMethods(List<HttpPluginMethod> pluginMethods) {
            this.pluginMethods = pluginMethods;
            return this;
        }

        public HttpPlugin build() {
            return new HttpPlugin(baseUrl, staticHeaders, pluginMethods);
        }
    }
}
