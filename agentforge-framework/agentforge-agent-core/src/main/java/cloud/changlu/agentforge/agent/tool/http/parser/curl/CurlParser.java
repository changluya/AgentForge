package cloud.changlu.agentforge.agent.tool.http.parser.curl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @description CURL解析器：把curl命令解析为{@link CurlParseResult}
 * @author changlu
 * @date 2026/10/04
 */
public class CurlParser {

    public CurlParseResult parse(String curlCommand) {
        CurlParseResult result = new CurlParseResult();

        if (curlCommand == null || curlCommand.trim().isEmpty()) {
            return result;
        }

        // 预处理：处理续行符和引号
        String normalizedCommand = normalizeCurlCommand(curlCommand);

        // 分割参数，考虑引号内的空格
        List<String> args = parseCommandLine(normalizedCommand);

        Iterator<String> iterator = args.iterator();

        // 跳过开头的 "curl"
        if (iterator.hasNext() && iterator.next().equals("curl")) {
            // 继续处理
        }

        while (iterator.hasNext()) {
            String arg = iterator.next();

            if (arg.startsWith("--")) {
                parseLongOption(arg, iterator, result);
            } else if (arg.startsWith("-")) {
                parseShortOption(arg, iterator, result);
            } else if (arg.startsWith("http://") || arg.startsWith("https://")) {
                result.setUrl(arg);
                // 解析查询参数
                result.parseQueryParamsFromUrl(arg);
            }
        }

        // 自动推断请求方法
        inferMethodFromData(result);

        return result;
    }

    private String normalizeCurlCommand(String curlCommand) {
        // 移除换行符和续行符
        return curlCommand.replace("\\\n", " ").replace("\\\r\n", " ").trim();
    }

    // 在 parse 方法的最后添加自动推断请求方法的逻辑
    private void inferMethodFromData(CurlParseResult result) {
        // 如果有数据体但没有明确设置方法，默认为 POST
        if ((result.getData() != null && !result.getData().isEmpty())
                || !result.getFormData().isEmpty()) {
            if (result.getMethod().equals("GET")) {
                result.setMethod("POST");
            }
        }
    }

    private List<String> parseCommandLine(String command) {
        List<String> args = new ArrayList<String>();
        StringBuilder currentArg = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '"';

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '"' || c == '\'') {
                if (inQuotes) {
                    if (c == quoteChar) {
                        inQuotes = false;
                        if (currentArg.length() > 0) {
                            args.add(currentArg.toString());
                            currentArg = new StringBuilder();
                        }
                    } else {
                        currentArg.append(c);
                    }
                } else {
                    inQuotes = true;
                    quoteChar = c;
                    if (currentArg.length() > 0) {
                        args.add(currentArg.toString());
                        currentArg = new StringBuilder();
                    }
                }
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (currentArg.length() > 0) {
                    args.add(currentArg.toString());
                    currentArg = new StringBuilder();
                }
            } else {
                currentArg.append(c);
            }
        }

        if (currentArg.length() > 0) {
            args.add(currentArg.toString());
        }

        return args;
    }

    private void parseLongOption(String arg, Iterator<String> iterator, CurlParseResult result) {
        switch (arg) {
            case "--request":
                setNextValue(iterator, result::setMethod);
                break;
            case "--header":
                parseHeader(getNextValue(iterator), result.getHeaders());
                break;
            case "--data":
            case "--data-ascii":
            case "--data-raw":
            case "--data-binary":
                setNextValue(iterator, result::setData);
                break;
            case "--form":
            case "-F":
                parseFormData(getNextValue(iterator), result.getFormData());
                break;
            case "--user":
            case "-u":
                setNextValue(iterator, result::setUser);
                break;
            case "--url":
                setNextValue(iterator, result::setUrl);
                break;
            case "--cookie":
            case "-b":
                setNextValue(iterator, result::setCookie);
                break;
            case "--referer":
            case "-e":
                setNextValue(iterator, result::setReferer);
                break;
            case "--user-agent":
            case "-A":
                setNextValue(iterator, result::setUserAgent);
                break;
            case "--location":
            case "-L":
                result.setFollowRedirects(true);
                break;
            case "--compressed":
                result.getHeaders().put("Accept-Encoding", "gzip, deflate");
                break;
            case "--insecure":
            case "-k":
                result.setInsecure(true);
                break;
            default:
                // 处理带值的选项，如 --max-time 30
                if (arg.contains("=")) {
                    String[] parts = arg.split("=", 2);
                    String option = parts[0];
                    String value = parts[1];
                    parseLongOptionWithValue(option, value, result);
                }
                break;
        }
    }

    private void parseLongOptionWithValue(String option, String value, CurlParseResult result) {
        switch (option) {
            case "--max-time":
            case "--connect-timeout":
                // 超时设置，暂不处理
                break;
            case "--proxy":
                result.setProxy(value);
                break;
            default:
                // 忽略未知选项
                break;
        }
    }

    private void parseShortOption(String arg, Iterator<String> iterator, CurlParseResult result) {
        if (arg.length() == 1) {
            return; // 单个 '-'，忽略
        }

        // 处理组合短选项如 -XPOST
        if (arg.length() > 2) {
            String option = arg.substring(0, 2);
            String value = arg.substring(2);
            parseShortOptionWithValue(option, value, result);
            return;
        }

        // 标准短选项
        switch (arg) {
            case "-X":
                setNextValue(iterator, result::setMethod);
                break;
            case "-H":
                parseHeader(getNextValue(iterator), result.getHeaders());
                break;
            case "-d":
                setNextValue(iterator, result::setData);
                break;
            case "-F":
                parseFormData(getNextValue(iterator), result.getFormData());
                break;
            case "-u":
                setNextValue(iterator, result::setUser);
                break;
            case "-b":
                setNextValue(iterator, result::setCookie);
                break;
            case "-e":
                setNextValue(iterator, result::setReferer);
                break;
            case "-A":
                setNextValue(iterator, result::setUserAgent);
                break;
            case "-L":
                result.setFollowRedirects(true);
                break;
            case "-k":
                result.setInsecure(true);
                break;
            default:
                // 忽略未知选项
                break;
        }
    }

    private void parseShortOptionWithValue(String option, String value, CurlParseResult result) {
        switch (option) {
            case "-X":
                result.setMethod(value.toUpperCase());
                break;
            case "-H":
                parseHeader(value, result.getHeaders());
                break;
            case "-d":
                result.setData(value);
                break;
            case "-F":
                parseFormData(value, result.getFormData());
                break;
            case "-u":
                result.setUser(value);
                break;
            case "-b":
                result.setCookie(value);
                break;
            case "-e":
                result.setReferer(value);
                break;
            case "-A":
                result.setUserAgent(value);
                break;
            default:
                // 忽略未知选项
                break;
        }
    }

    private void parseHeader(String headerStr, Map<String, String> headers) {
        int colonIndex = headerStr.indexOf(':');
        if (colonIndex > 0) {
            String key = headerStr.substring(0, colonIndex).trim();
            String value = headerStr.substring(colonIndex + 1).trim();

            // 移除可能的引号
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }

            headers.put(key, value);
        }
    }

    private void parseFormData(String formDataStr, List<CurlParseResult.FormData> formDataList) {
        int equalsIndex = formDataStr.indexOf('=');
        if (equalsIndex > 0) {
            String name = formDataStr.substring(0, equalsIndex);
            String value = formDataStr.substring(equalsIndex + 1);

            CurlParseResult.FormData formData = new CurlParseResult.FormData();
            formData.setName(name);

            // 检查是否是文件上传
            if (value.startsWith("@")) {
                formData.setFilename(value.substring(1));
                formData.setContentType("application/octet-stream");
            } else {
                formData.setValue(value);
            }

            formDataList.add(formData);
        }
    }

    private String getNextValue(Iterator<String> iterator) {
        return iterator.hasNext() ? iterator.next() : "";
    }

    private void setNextValue(
            Iterator<String> iterator, java.util.function.Consumer<String> setter) {
        if (iterator.hasNext()) {
            setter.accept(iterator.next());
        }
    }
}
