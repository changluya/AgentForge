package cloud.changlu.agentforge.agent.tool.http.domain;

import java.util.List;

/**
 * @description 插件方法：描述一个可被模型调用的HTTP工具（名称、描述、请求方法、URI与参数）
 * @author changlu
 * @date 2026/10/04
 */
public class HttpPluginMethod {

    // 方法名称
    private String methodName;
    // 方法描述
    private String methodDescription;

    // 请求方法类型Code
    private Integer httpMethodType;
    // 请求资源点 例如：https://baidu.com/news，其中uri就是/news
    private String uri;

    // 请求参数集合
    private List<HttpToolParameter> parameters;

    public HttpPluginMethod() {}

    public HttpPluginMethod(
            String methodName,
            String methodDescription,
            Integer httpMethodType,
            String uri,
            List<HttpToolParameter> parameters) {
        this.methodName = methodName;
        this.methodDescription = methodDescription;
        this.httpMethodType = httpMethodType;
        this.uri = uri;
        this.parameters = parameters;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodDescription() {
        return methodDescription;
    }

    public Integer getHttpMethodType() {
        return httpMethodType;
    }

    public String getUri() {
        return uri;
    }

    public List<HttpToolParameter> getParameters() {
        return parameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String methodName;
        private String methodDescription;
        private Integer httpMethodType;
        private String uri;
        private List<HttpToolParameter> parameters;

        private Builder() {}

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder methodDescription(String methodDescription) {
            this.methodDescription = methodDescription;
            return this;
        }

        public Builder httpMethodType(Integer httpMethodType) {
            this.httpMethodType = httpMethodType;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder parameters(List<HttpToolParameter> parameters) {
            this.parameters = parameters;
            return this;
        }

        public HttpPluginMethod build() {
            return new HttpPluginMethod(
                    methodName, methodDescription, httpMethodType, uri, parameters);
        }
    }
}
