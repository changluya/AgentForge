package cloud.changlu.agentforge.agent.tool.http.parser.curl;

import cloud.changlu.agentforge.agent.tool.http.domain.HttpPlugin;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpPluginMethod;
import cloud.changlu.agentforge.agent.tool.http.domain.HttpToolParameter;
import cloud.changlu.agentforge.agent.tool.http.enums.HttpPluginEnums;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @description CURL转换为HttpPlugin
 * @author changlu
 * @date 2026/10/04
 */
public class CurlToHttpPluginConverter {

    public HttpPlugin convert(CurlParseResult curl) {
        String baseUrl = extractBaseUrl(curl.getUrl());
        String uri = extractUri(curl.getUrl());

        // 构建静态头部
        Map<String, String> staticHeaders = buildStaticHeaders(curl);

        // 构建插件方法
        HttpPluginMethod method = buildPluginMethod(curl, uri);

        // 使用 builder 模式构建 HttpPlugin
        return HttpPlugin.builder()
                .baseUrl(baseUrl)
                .staticHeaders(staticHeaders)
                .pluginMethods(Collections.singletonList(method))
                .build();
    }

    private Map<String, String> buildStaticHeaders(CurlParseResult curl) {
        Map<String, String> staticHeaders = new HashMap<String, String>(curl.getHeaders());

        // 添加认证信息
        if (curl.getUser() != null) {
            staticHeaders.put(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(curl.getUser().getBytes()));
        }

        // 添加其他头部
        if (curl.getCookie() != null) {
            staticHeaders.put("Cookie", curl.getCookie());
        }
        if (curl.getReferer() != null) {
            staticHeaders.put("Referer", curl.getReferer());
        }
        if (curl.getUserAgent() != null) {
            staticHeaders.put("User-Agent", curl.getUserAgent());
        }

        return staticHeaders;
    }

    private HttpPluginMethod buildPluginMethod(CurlParseResult curl, String uri) {
        // 构建参数列表
        List<HttpToolParameter> parameters = parseParameters(curl);

        // 使用方法名生成策略
        String methodName = generateMethodName(curl);

        // 使用 builder 模式构建 HttpPluginMethod
        return HttpPluginMethod.builder()
                .methodName(methodName)
                .methodDescription("Generated from curl command: " + curl.getMethod() + " " + uri)
                .httpMethodType(getMethodTypeValue(curl.getMethod()))
                .uri(uri)
                .parameters(parameters)
                .build();
    }

    private String extractBaseUrl(String url) {
        if (url == null) return "";

        int protocolEnd = url.indexOf("://");
        if (protocolEnd == -1) return url;

        int pathStart = url.indexOf('/', protocolEnd + 3);
        return pathStart == -1 ? url : url.substring(0, pathStart);
    }

    private String extractUri(String url) {
        if (url == null) return "/";

        int protocolEnd = url.indexOf("://");
        if (protocolEnd == -1) {
            // 移除查询参数
            int queryStart = url.indexOf('?');
            return queryStart == -1 ? url : url.substring(0, queryStart);
        }

        int pathStart = url.indexOf('/', protocolEnd + 3);
        if (pathStart == -1) return "/";

        // 移除查询参数
        String pathAndQuery = url.substring(pathStart);
        int queryStart = pathAndQuery.indexOf('?');
        return queryStart == -1 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
    }

    private List<HttpToolParameter> parseParameters(CurlParseResult curl) {
        List<HttpToolParameter> params = new ArrayList<HttpToolParameter>();

        // 处理查询参数
        if (!curl.getQueryParams().isEmpty()) {
            for (Map.Entry<String, String> entry : curl.getQueryParams().entrySet()) {
                HttpToolParameter param =
                        HttpToolParameter.builder()
                                .mappedName(entry.getKey())
                                .methodParamName(entry.getKey())
                                .useTypeValue(HttpPluginEnums.ParameterUseType.QUERY.getValue())
                                .dataTypeValue(HttpPluginEnums.ParameterType.STRING.getValue())
                                .defaultValue(entry.getValue())
                                .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
                                .methodParamDescription(
                                        "Query parameter from URL: " + entry.getKey())
                                .build();
                params.add(param);
            }
        }

        // 处理表单数据
        if (!curl.getFormData().isEmpty()) {
            for (CurlParseResult.FormData formData : curl.getFormData()) {
                HttpToolParameter param =
                        HttpToolParameter.builder()
                                .mappedName(formData.getName())
                                .methodParamName(formData.getName())
                                .useTypeValue(HttpPluginEnums.ParameterUseType.BODY.getValue())
                                .dataTypeValue(HttpPluginEnums.ParameterType.STRING.getValue())
                                .defaultValue(formData.getValue())
                                .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
                                .methodParamDescription(
                                        "Form data parameter: " + formData.getName())
                                .build();
                params.add(param);
            }
        }

        // 解析 JSON 数据为参数
        if (curl.getData() != null) {
            if (curl.getData().startsWith("{") && curl.getData().endsWith("}")) {
                parseJsonData(curl.getData(), params);
            } else {
                // 非JSON数据作为单个参数处理
                HttpToolParameter bodyParam =
                        HttpToolParameter.builder()
                                .mappedName("body")
                                .methodParamName("body")
                                .useTypeValue(HttpPluginEnums.ParameterUseType.BODY.getValue())
                                .dataTypeValue(HttpPluginEnums.ParameterType.STRING.getValue())
                                .defaultValue(curl.getData())
                                .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
                                .methodParamDescription("Request body data")
                                .build();
                params.add(bodyParam);
            }
        }

        return params;
    }

    private void parseJsonData(String jsonData, List<HttpToolParameter> params) {
        try {
            Map<String, Object> rootNode = Json.parseObject(jsonData);

            for (Map.Entry<String, Object> field : rootNode.entrySet()) {
                Object valueNode = field.getValue();

                Object paramValue;
                Integer dataTypeValue;

                if (valueNode instanceof List) {
                    paramValue = Json.stringify(valueNode);
                    dataTypeValue = HttpPluginEnums.ParameterType.ARRAY.getValue();
                } else if (valueNode instanceof Map) {
                    paramValue = Json.stringify(valueNode);
                    dataTypeValue = HttpPluginEnums.ParameterType.OBJECT.getValue();
                } else if (valueNode instanceof String) {
                    paramValue = valueNode;
                    dataTypeValue = HttpPluginEnums.ParameterType.STRING.getValue();
                } else if (valueNode instanceof Boolean) {
                    paramValue = valueNode;
                    dataTypeValue = HttpPluginEnums.ParameterType.BOOLEAN.getValue();
                } else if (valueNode instanceof Long || valueNode instanceof Integer) {
                    paramValue = ((Number) valueNode).intValue();
                    dataTypeValue = HttpPluginEnums.ParameterType.INTEGER.getValue();
                } else if (valueNode instanceof Number) {
                    paramValue = ((Number) valueNode).doubleValue();
                    dataTypeValue = HttpPluginEnums.ParameterType.NUMBER.getValue();
                } else if (valueNode == null) {
                    paramValue = null;
                    dataTypeValue = HttpPluginEnums.ParameterType.STRING.getValue();
                } else {
                    paramValue = Json.stringify(valueNode);
                    dataTypeValue = HttpPluginEnums.ParameterType.STRING.getValue();
                }

                HttpToolParameter param =
                        HttpToolParameter.builder()
                                .mappedName(field.getKey())
                                .methodParamName(field.getKey())
                                .useTypeValue(HttpPluginEnums.ParameterUseType.BODY.getValue())
                                .dataTypeValue(dataTypeValue)
                                .defaultValue(paramValue)
                                .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
                                .methodParamDescription("Body parameter from curl data")
                                .build();
                params.add(param);
            }
        } catch (Exception e) {
            // 如果解析失败，作为单个参数处理
            HttpToolParameter bodyParam =
                    HttpToolParameter.builder()
                            .mappedName("body")
                            .methodParamName("body")
                            .useTypeValue(HttpPluginEnums.ParameterUseType.BODY.getValue())
                            .dataTypeValue(HttpPluginEnums.ParameterType.STRING.getValue())
                            .defaultValue(jsonData)
                            .required(HttpPluginEnums.RequiredStatus.REQUIRED.getCode())
                            .methodParamDescription("Request body data")
                            .build();
            params.add(bodyParam);
        }
    }

    private Integer getMethodTypeValue(String method) {
        try {
            return HttpPluginEnums.HttpMethod.fromName(method.toUpperCase()).getValue();
        } catch (Exception e) {
            return HttpPluginEnums.HttpMethod.GET.getValue();
        }
    }

    private String generateMethodName(CurlParseResult curl) {
        String methodName = "execute" + curl.getMethod().toUpperCase();
        if (curl.getUrl() != null) {
            String[] parts = curl.getUrl().split("/");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                if (!lastPart.isEmpty()) {
                    methodName +=
                            Character.toUpperCase(lastPart.charAt(0))
                                    + lastPart.substring(1).replaceAll("[^a-zA-Z0-9]", "");
                }
            }
        }
        return methodName;
    }
}
