package cloud.changlu.agentforge.agent.tool.http;

import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.agent.tool.http.support.ToolExecutionRequestUtil;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;
import cloud.changlu.agentforge.model.http.JdkHttpTransport;
import cloud.changlu.agentforge.model.internal.json.Json;
import cloud.changlu.agentforge.model.tool.ToolExecutor;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @description HTTP工具执行器（AgentForge版）：支持Query/Body/Path/Header参数与参数映射、默认值， 基于AgentForge自带的{@link
 *     HttpTransport}发起请求，支持智能判断请求体类型。
 * @author changlu
 * @date 2026/10/04
 */
public class HttpToolExecutor implements ToolExecutor {

    private static final Logger log = Logger.getLogger(HttpToolExecutor.class.getName());

    private final HttpTransport httpTransport;
    private final String baseUrl;
    private final Map<String, ParameterConfig> parameterConfigs;
    private final Map<String, String> staticHeaders;
    private final HttpPluginEnums.HttpMethod method;

    /** 参数配置类 */
    public static class ParameterConfig {
        public final String mappedName; // 映射后的参数名
        public final HttpPluginEnums.ParameterUseType type; // 参数类型
        public final HttpPluginEnums.ParameterType dataType; // 数据类型
        public final Object defaultValue; // 默认值
        public final int required; // 是否必填

        public ParameterConfig(
                String mappedName,
                HttpPluginEnums.ParameterUseType type,
                HttpPluginEnums.ParameterType dataType,
                Object defaultValue,
                int required) {
            this.mappedName = mappedName;
            this.type = type;
            this.dataType = dataType;
            this.defaultValue = defaultValue;
            this.required = required;
        }
    }

    /**
     * 构造函数，使用默认的JDK HttpTransport
     *
     * @param baseUrl 基础URL
     * @param methodValue HTTP方法
     * @param staticHeaders 静态请求头（固定值）
     * @param parameterConfigs 参数配置映射（参数名 -> 参数配置）
     */
    public HttpToolExecutor(
            String baseUrl,
            int methodValue,
            Map<String, String> staticHeaders,
            Map<String, ParameterConfig> parameterConfigs) {
        this(baseUrl, methodValue, staticHeaders, parameterConfigs, new JdkHttpTransport());
    }

    /**
     * 构造函数
     *
     * @param baseUrl 基础URL
     * @param methodValue HTTP方法
     * @param staticHeaders 静态请求头（固定值）
     * @param parameterConfigs 参数配置映射（参数名 -> 参数配置）
     * @param httpTransport HTTP传输实现，为null时使用默认的JDK实现
     */
    public HttpToolExecutor(
            String baseUrl,
            int methodValue,
            Map<String, String> staticHeaders,
            Map<String, ParameterConfig> parameterConfigs,
            HttpTransport httpTransport) {
        this.httpTransport = httpTransport != null ? httpTransport : new JdkHttpTransport();
        this.baseUrl = baseUrl;
        this.method = HttpPluginEnums.HttpMethod.fromValue(methodValue);
        this.staticHeaders = staticHeaders != null ? staticHeaders : new HashMap<String, String>();
        this.parameterConfigs =
                parameterConfigs != null
                        ? parameterConfigs
                        : new HashMap<String, ParameterConfig>();
    }

    @Override
    public String execute(ToolExecutionRequest toolExecutionRequest, Object memoryId) {
        long startTime = System.currentTimeMillis();

        // 将工具执行请求的参数转换为Map
        Map<String, Object> arguments =
                ToolExecutionRequestUtil.argumentsAsMap(toolExecutionRequest.arguments());

        // 处理参数：应用默认值并验证必填参数
        Map<String, Object> finalArguments = processArguments(arguments);

        // 构建包含路径参数的URL
        String url = buildUrlWithPathParams(finalArguments);

        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> staticHeader : staticHeaders.entrySet()) {
            headers.put(staticHeader.getKey(), staticHeader.getValue());
        }

        // 处理动态请求头参数【类型为HEADER会封装到请求头中】
        addHeaderParameters(headers, finalArguments);

        // 智能判断请求体类型并处理参数
        String requestBody = "";
        if (shouldUseJsonBody(finalArguments)) {
            Map<String, Object> bodyParams = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
                ParameterConfig config = entry.getValue();
                if (config.type == HttpPluginEnums.ParameterUseType.BODY
                        && finalArguments.containsKey(entry.getKey())) {
                    bodyParams.put(config.mappedName, finalArguments.get(entry.getKey()));
                }
            }
            requestBody = ToolExecutionRequestUtil.toJson(bodyParams);
            headers.put("Content-Type", "application/json");
        } else {
            url = appendQueryParameters(url, finalArguments);
        }

        HttpRequest request =
                HttpRequest.builder()
                        .url(url)
                        .method(method.getMethodName())
                        .headers(headers)
                        .body(requestBody)
                        .connectTimeoutMillis((int) TimeUnit.MINUTES.toMillis(10))
                        .readTimeoutMillis((int) TimeUnit.MINUTES.toMillis(15))
                        .build();

        // 记录请求详情
        logRequestDetails(request, finalArguments, requestBody);

        try {
            return executeWithRetryIfNecessary(request, startTime);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            log.log(Level.SEVERE, "HTTP请求执行失败，耗时 " + (endTime - startTime) + " 毫秒", e);
            return "HTTP请求失败: " + e.getMessage();
        }
    }

    private String executeWithRetryIfNecessary(HttpRequest request, long startTime)
            throws IOException {
        try {
            return doExecute(request, startTime, false);
        } catch (IOException e) {
            if (!shouldRetryOnce(e, request)) {
                throw e;
            }
            log.warning(
                    "HTTP工具请求命中可重试连接异常，重试一次, method: "
                            + request.method()
                            + ", url: "
                            + request.url()
                            + ", error: "
                            + e.getMessage());
            return doExecute(request, startTime, true);
        }
    }

    private String doExecute(HttpRequest request, long startTime, boolean retryAttempt)
            throws IOException {
        HttpResponse response = httpTransport.execute(request);
        String responseContent = response.body() == null ? "Empty response" : response.body();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        logResponseDetails(response.statusCode(), responseContent, duration);
        if (retryAttempt) {
            log.info(
                    "HTTP工具请求重试成功, method: "
                            + request.method()
                            + ", url: "
                            + request.url()
                            + ", duration: "
                            + duration
                            + "ms");
        }
        return responseContent;
    }

    private boolean shouldRetryOnce(IOException error, HttpRequest request) {
        if (error == null || request == null) {
            return false;
        }
        if (!(method == HttpPluginEnums.HttpMethod.GET
                || method == HttpPluginEnums.HttpMethod.DELETE)) {
            return false;
        }
        return isConnectionReset(error);
    }

    private boolean isConnectionReset(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketException
                    && current.getMessage() != null
                    && current.getMessage().toLowerCase().contains("connection reset")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 智能判断是否使用JSON请求体 */
    private boolean shouldUseJsonBody(Map<String, Object> arguments) {
        // GET/DELETE方法强制使用查询参数
        if (method == HttpPluginEnums.HttpMethod.GET
                || method == HttpPluginEnums.HttpMethod.DELETE) {
            return false;
        }

        // 检查是否存在BODY类型参数
        boolean hasBodyParams =
                parameterConfigs.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        entry.getValue().type
                                                        == HttpPluginEnums.ParameterUseType.BODY
                                                && (arguments.containsKey(entry.getKey())
                                                        || entry.getValue().defaultValue != null));

        // POST/PUT/PATCH方法且存在BODY参数时使用JSON请求体
        return hasBodyParams;
    }

    private Map<String, Object> processArguments(Map<String, Object> providedArguments) {
        Map<String, Object> processed = new HashMap<String, Object>();
        for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
            String paramName = entry.getKey();
            ParameterConfig config = entry.getValue();
            Object value = null;

            if (providedArguments.containsKey(paramName)) {
                value = providedArguments.get(paramName);
            } else if (config.defaultValue != null) {
                value = config.defaultValue;
            } else if (HttpPluginEnums.RequiredStatus.REQUIRED.getCode() == config.required) {
                throw new IllegalArgumentException("缺少必填参数 '" + paramName + "'");
            }

            if (value != null) {
                // 根据参数的数据类型进行转换
                processed.put(paramName, convertValueToCorrectType(value, config.dataType));
            }
        }
        return processed;
    }

    /** 将值转换为正确的类型，如果没有匹配到数据类型，默认使用字符串类型 */
    private Object convertValueToCorrectType(
            Object value, HttpPluginEnums.ParameterType targetType) {
        if (value == null) {
            return null;
        }

        // 如果目标类型为null，默认使用STRING类型
        if (targetType == null) {
            targetType = HttpPluginEnums.ParameterType.STRING;
        }

        try {
            switch (targetType) {
                case INTEGER:
                    if (value instanceof Integer) {
                        return value;
                    } else if (value instanceof Number) {
                        return ((Number) value).intValue();
                    }
                    return Integer.parseInt(value.toString().trim());

                case NUMBER:
                    if (value instanceof Double) {
                        return value;
                    } else if (value instanceof Number) {
                        return ((Number) value).doubleValue();
                    }
                    return Double.parseDouble(value.toString().trim());

                case BOOLEAN:
                    if (value instanceof Boolean) {
                        return value;
                    }
                    String boolStr = value.toString().trim().toLowerCase();
                    return "true".equals(boolStr) || "1".equals(boolStr) || "yes".equals(boolStr);

                case ARRAY:
                    // 处理数组类型
                    if (value instanceof List) {
                        return value; // 已经是List对象，直接返回
                    } else if (value instanceof String) {
                        // 尝试从字符串解析为数组
                        String strValue = value.toString().trim();
                        if (strValue.startsWith("[") && strValue.endsWith("]")) {
                            try {
                                Object parsed = Json.parse(strValue);
                                if (parsed instanceof List) {
                                    return parsed;
                                }
                            } catch (Exception e) {
                                log.log(Level.WARNING, "无法将字符串解析为数组: " + strValue, e);
                            }
                            // 解析失败，返回原始字符串
                            return strValue;
                        } else {
                            // 如果不是JSON数组格式，将其作为单元素数组
                            return Collections.singletonList(strValue);
                        }
                    } else {
                        // 其他类型，包装为单元素数组
                        return Collections.singletonList(value);
                    }

                case OBJECT:
                    if (value instanceof Map) {
                        return value;
                    } else if (value instanceof String) {
                        String strValue = value.toString().trim();
                        if (strValue.isEmpty()) {
                            return Collections.emptyMap();
                        }
                        Object parsed = Json.parse(strValue);
                        if (parsed instanceof Map) {
                            return parsed;
                        }
                        return Collections.emptyMap();
                    } else {
                        return value;
                    }

                case STRING:
                default:
                    return value.toString();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "参数值 '" + value + "' 无法转换为类型: " + targetType.getName(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数值 '" + value + "' 类型转换失败", e);
        }
    }

    private String buildUrlWithPathParams(Map<String, Object> arguments) {
        String url = baseUrl;
        for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
            if (entry.getValue().type == HttpPluginEnums.ParameterUseType.PATH) {
                String paramName = entry.getKey();
                String placeholder = "{" + entry.getValue().mappedName + "}";
                if (arguments.containsKey(paramName)) {
                    url = url.replace(placeholder, arguments.get(paramName).toString());
                }
            }
        }
        return url;
    }

    private String appendQueryParameters(String url, Map<String, Object> arguments) {
        StringBuilder builder = new StringBuilder(url);
        boolean hasQuery = url.indexOf('?') >= 0;
        for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
            ParameterConfig config = entry.getValue();
            if (config.type == HttpPluginEnums.ParameterUseType.QUERY
                    && arguments.containsKey(entry.getKey())) {
                builder.append(hasQuery ? '&' : '?');
                hasQuery = true;
                builder.append(encode(config.mappedName))
                        .append('=')
                        .append(encode(String.valueOf(arguments.get(entry.getKey()))));
            }
        }
        return builder.toString();
    }

    private void addHeaderParameters(Map<String, String> headers, Map<String, Object> arguments) {
        for (Map.Entry<String, ParameterConfig> entry : parameterConfigs.entrySet()) {
            ParameterConfig config = entry.getValue();
            if (config.type == HttpPluginEnums.ParameterUseType.HEADER
                    && arguments.containsKey(entry.getKey())) {
                headers.put(config.mappedName, String.valueOf(arguments.get(entry.getKey())));
            }
        }
    }

    private void logRequestDetails(
            HttpRequest request, Map<String, Object> arguments, String requestBody) {
        log.info("=== HTTP 请求详情 ===");
        log.info("URL: " + request.url());
        log.info("方法: " + method);
        log.info("请求头: " + request.headers());
        Map<HttpPluginEnums.ParameterUseType, Map<String, Object>> paramsByType =
                arguments.entrySet().stream()
                        .collect(
                                Collectors.groupingBy(
                                        e -> parameterConfigs.get(e.getKey()).type,
                                        Collectors.toMap(
                                                e -> parameterConfigs.get(e.getKey()).mappedName,
                                                Map.Entry::getValue)));
        paramsByType.forEach((type, params) -> log.info(type + " 参数: " + params));
        if (requestBody != null && !requestBody.isEmpty()) {
            log.info("请求体: " + requestBody);
        }
        log.info("=====================");
    }

    private void logResponseDetails(int statusCode, String responseContent, long durationMs) {
        log.info("=== HTTP 响应详情 ===");
        log.info("状态码: " + statusCode);
        log.info("耗时: " + durationMs + " 毫秒");
        String truncatedResponse =
                responseContent.length() > 1000
                        ? responseContent.substring(0, 1000) + "...[截断]"
                        : responseContent;
        log.info("响应体: " + truncatedResponse);
        log.info("=====================");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 encoding is not supported", e);
        }
    }
}
