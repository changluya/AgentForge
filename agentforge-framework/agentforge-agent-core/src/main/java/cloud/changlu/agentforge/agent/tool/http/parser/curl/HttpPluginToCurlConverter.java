package cloud.changlu.agentforge.agent.tool.http.parser.curl;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @description HttpPlugin转换为curl命令
 * @author changlu
 * @date 2026/10/04
 */
public class HttpPluginToCurlConverter {

    /** 将只包含一个方法的 HttpPlugin 转换为 curl 命令 */
    public String convert(HttpPlugin httpPlugin) {
        validatePlugin(httpPlugin);
        List<HttpPluginMethod> pluginMethods = httpPlugin.getPluginMethods();
        if (isEmpty(pluginMethods)) {
            throw new IllegalArgumentException("HttpPlugin 未配置 pluginMethods");
        }
        if (pluginMethods.size() != 1) {
            throw new IllegalArgumentException("HttpPlugin 包含多个 method，请显式指定要转换的 method");
        }
        return convert(httpPlugin, pluginMethods.get(0));
    }

    /** 根据方法名转换为 curl 命令 */
    public String convert(HttpPlugin httpPlugin, String methodName) {
        validatePlugin(httpPlugin);
        if (!hasText(methodName)) {
            throw new IllegalArgumentException("methodName 不能为空");
        }
        HttpPluginMethod method =
                httpPlugin.getPluginMethods().stream()
                        .filter(item -> methodName.equals(item.getMethodName()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("未找到 method: " + methodName));
        return convert(httpPlugin, method);
    }

    /** 将指定方法转换为 curl 命令 */
    public String convert(HttpPlugin httpPlugin, HttpPluginMethod method) {
        validatePlugin(httpPlugin);
        if (method == null) {
            throw new IllegalArgumentException("HttpPluginMethod 不能为空");
        }

        HttpPluginEnums.HttpMethod httpMethod =
                HttpPluginEnums.HttpMethod.fromValue(method.getHttpMethodType());
        List<HttpToolParameter> parameters =
                method.getParameters() == null
                        ? new ArrayList<HttpToolParameter>()
                        : method.getParameters();

        String url = buildUrl(httpPlugin.getBaseUrl(), method.getUri(), parameters);
        List<String> segments = new ArrayList<String>();
        segments.add("curl");
        segments.add("--location");
        segments.add(quote(url));

        if (httpMethod != HttpPluginEnums.HttpMethod.GET) {
            segments.add("--request");
            segments.add(httpMethod.getMethodName());
        }

        Map<String, String> headers = buildHeaders(httpPlugin.getStaticHeaders(), parameters);
        headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry ->
                                segments.add(
                                        "--header "
                                                + quote(entry.getKey() + ": " + entry.getValue())));

        String requestBody = buildRequestBody(httpMethod, parameters);
        if (hasText(requestBody)) {
            segments.add("--data-raw " + quote(requestBody));
        }

        return String.join(" ", segments);
    }

    private void validatePlugin(HttpPlugin httpPlugin) {
        if (httpPlugin == null) {
            throw new IllegalArgumentException("HttpPlugin 不能为空");
        }
        if (!hasText(httpPlugin.getBaseUrl())) {
            throw new IllegalArgumentException("HttpPlugin.baseUrl 不能为空");
        }
    }

    private String buildUrl(String baseUrl, String uri, List<HttpToolParameter> parameters) {
        String resolvedBaseUrl =
                baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String resolvedUri = hasText(uri) ? uri : "/";
        if (!resolvedUri.startsWith("/")) {
            resolvedUri = "/" + resolvedUri;
        }

        String path = resolvedUri;
        List<HttpToolParameter> pathParams =
                filterParameters(parameters, HttpPluginEnums.ParameterUseType.PATH);
        for (HttpToolParameter parameter : pathParams) {
            String placeholder = "{" + parameter.getMappedName() + "}";
            String replacement = stringifyPlainValue(resolveParameterValue(parameter));
            path = path.replace(placeholder, replacement);
        }

        List<HttpToolParameter> queryParams =
                filterParameters(parameters, HttpPluginEnums.ParameterUseType.QUERY);
        if (queryParams.isEmpty()) {
            return resolvedBaseUrl + path;
        }

        String queryString =
                queryParams.stream()
                        .sorted(Comparator.comparing(HttpToolParameter::getMappedName))
                        .map(
                                parameter ->
                                        parameter.getMappedName()
                                                + "="
                                                + stringifyQueryValue(
                                                        resolveParameterValue(parameter)))
                        .collect(Collectors.joining("&"));
        return resolvedBaseUrl + path + "?" + queryString;
    }

    private Map<String, String> buildHeaders(
            Map<String, String> staticHeaders, List<HttpToolParameter> parameters) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (!isEmpty(staticHeaders)) {
            headers.putAll(staticHeaders);
        }
        for (HttpToolParameter parameter :
                filterParameters(parameters, HttpPluginEnums.ParameterUseType.HEADER)) {
            headers.put(
                    parameter.getMappedName(),
                    stringifyPlainValue(resolveParameterValue(parameter)));
        }
        return headers;
    }

    private String buildRequestBody(
            HttpPluginEnums.HttpMethod httpMethod, List<HttpToolParameter> parameters) {
        if (httpMethod == HttpPluginEnums.HttpMethod.GET
                || httpMethod == HttpPluginEnums.HttpMethod.DELETE) {
            return null;
        }

        List<HttpToolParameter> bodyParams =
                filterParameters(parameters, HttpPluginEnums.ParameterUseType.BODY);
        if (bodyParams.isEmpty()) {
            return null;
        }

        if (bodyParams.size() == 1 && isRawBodyParameter(bodyParams.get(0))) {
            return stringifyPlainValue(resolveParameterValue(bodyParams.get(0)));
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        bodyParams.stream()
                .sorted(Comparator.comparing(HttpToolParameter::getMappedName))
                .forEach(
                        parameter ->
                                body.put(parameter.getMappedName(), convertTypedValue(parameter)));
        return Json.stringify(body);
    }

    private List<HttpToolParameter> filterParameters(
            List<HttpToolParameter> parameters, HttpPluginEnums.ParameterUseType useType) {
        List<HttpToolParameter> result = new ArrayList<HttpToolParameter>();
        for (HttpToolParameter parameter : parameters) {
            if (parameter != null && useType.getValue().equals(parameter.getUseTypeValue())) {
                result.add(parameter);
            }
        }
        return result;
    }

    private boolean isRawBodyParameter(HttpToolParameter parameter) {
        return HttpPluginEnums.ParameterType.STRING.getValue().equals(parameter.getDataTypeValue())
                && ("body".equals(parameter.getMappedName())
                        || "body".equals(parameter.getMethodParamName()));
    }

    private Object convertTypedValue(HttpToolParameter parameter) {
        Object value = resolveParameterValue(parameter);
        if (value == null) {
            return null;
        }

        HttpPluginEnums.ParameterType parameterType =
                HttpPluginEnums.ParameterType.fromValue(parameter.getDataTypeValue());
        try {
            switch (parameterType) {
                case INTEGER:
                    return value instanceof Number
                            ? ((Number) value).intValue()
                            : Integer.parseInt(String.valueOf(value));
                case NUMBER:
                    return value instanceof Number
                            ? ((Number) value).doubleValue()
                            : Double.parseDouble(String.valueOf(value));
                case BOOLEAN:
                    return value instanceof Boolean
                            ? value
                            : Boolean.parseBoolean(String.valueOf(value));
                case ARRAY:
                case OBJECT:
                    return value instanceof String ? Json.parse((String) value) : value;
                case STRING:
                default:
                    return String.valueOf(value);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "参数 " + parameter.getMethodParamName() + " 无法转换为 " + parameterType.getName(),
                    e);
        }
    }

    private Object resolveParameterValue(HttpToolParameter parameter) {
        Object value = parameter.getDefaultValue();
        if (value != null) {
            return value;
        }
        return "${" + parameter.getMethodParamName() + "}";
    }

    private String stringifyQueryValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        return value instanceof Map || value instanceof List
                ? Json.stringify(value)
                : String.valueOf(value);
    }

    private String stringifyPlainValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private String quote(String value) {
        String normalized = value == null ? "" : value;
        return "'" + normalized.replace("'", "'\"'\"'") + "'";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private boolean isEmpty(Map<?, ?> values) {
        return values == null || values.isEmpty();
    }
}
