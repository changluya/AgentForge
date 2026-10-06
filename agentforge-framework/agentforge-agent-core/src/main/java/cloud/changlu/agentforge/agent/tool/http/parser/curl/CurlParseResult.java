package cloud.changlu.agentforge.agent.tool.http.parser.curl;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description CURL解析结果类
 * @author changlu
 * @date 2026/10/04
 */
public class CurlParseResult {
    private String url;
    private String method = "GET";
    private Map<String, String> headers = new HashMap<String, String>();
    private String data;
    private String user;
    private List<FormData> formData = new ArrayList<FormData>();
    private String cookie;
    private String referer;
    private String userAgent;
    private boolean followRedirects = false;
    private boolean insecure = false;
    private String proxy;
    // 添加查询参数映射
    private Map<String, String> queryParams = new HashMap<String, String>();

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public List<FormData> getFormData() {
        return formData;
    }

    public void setFormData(List<FormData> formData) {
        this.formData = formData;
    }

    public String getCookie() {
        return cookie;
    }

    public void setCookie(String cookie) {
        this.cookie = cookie;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    public String getProxy() {
        return proxy;
    }

    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public static class FormData {
        private String name;
        private String value;
        private String filename;
        private String contentType;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
    }

    // 添加解析查询参数的方法
    public void parseQueryParamsFromUrl(String url) {
        if (url == null) return;

        int queryStart = url.indexOf('?');
        if (queryStart == -1) return;

        String queryString = url.substring(queryStart + 1);
        String[] pairs = queryString.split("&");

        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                    String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name());
                    queryParams.put(key, value);
                } catch (UnsupportedEncodingException e) {
                    // 忽略解码错误
                    queryParams.put(keyValue[0], keyValue[1]);
                }
            } else if (keyValue.length == 1) {
                queryParams.put(keyValue[0], "");
            }
        }
    }
}
