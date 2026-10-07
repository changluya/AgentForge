---
title: "Adapting HTTP Protocol to FunctionCall Protocol 01, Core Design Principles of the AgentForge HTTP Tool Module"
date: 2026-10-08
tags: [AgentForge, General ReAct Agent Layer, Function Calling, HTTP]
---

# Core Design Principles of the AgentForge HTTP Tool Module

> Updated: 2026-10-05  
> Applicable module: `agentforge-agent-core` (`cloud.changlu.agentforge.agent.tool.http`)  
> Dependent contracts: `agentforge-model-api` / `agentforge-model-core`  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: **The HTTP tool module declaratively maps "a set of HTTP endpoints" into AgentForge's `ToolSpecification` + `ToolExecutor`, letting the model call any REST endpoint just like calling a local method—without introducing any HTTP / JSON third-party library into the zero-dependency core.**

---

## 1. Background and Problem Introduction

## 1.1 Scenario-driven: where we got stuck while integrating a DingTalk bot

While building external capability integration for AgentForge, we encountered a very realistic scenario:

> The team wants the Agent to send DingTalk messages, query CRM customers, and pull ticket lists. These capabilities **have neither an MCP Server, nor are worth writing a Java `@Tool` method for each endpoint**—they are just ordinary REST APIs.

This brought three concrete pain points:

1. **Many endpoints**: A single SaaS has dozens of endpoints; writing `@Tool` methods one by one has an extremely high maintenance cost;
2. **The model has no way to start**: Handing "a generic HTTP tool" to the model makes it invent URLs and add parameters arbitrarily—neither controllable nor safe;
3. **Zero-dependency core**: AgentForge's core is Java 8 + zero third-party libraries, so OkHttp / Retrofit / Jackson cannot be introduced for HTTP access.

<br/>

## 1.2 Problem-led: how to let the model call HTTP by "speaking natural language"?

> **Problem**: How can the model turn a natural-language sentence into an HTTP call with **correct parameters and a controllable address**?

The answer is not "let the model assemble HTTP itself", but rather:

- The developer **pre-declares** each endpoint (address, method, parameters) → generating a `ToolSpecification`;
- The model only chooses among **declared tools** and fills in only **declared parameters**;
- At runtime, the executor "fills" the parameters into the URL / Header / Body and issues the request.

This is exactly what this module does.

<br/>

## 1.3 Design goals and constraints

| Goal | Description |
|---|---|
| Declarative | Describe a group of endpoints with an `HttpPlugin`; write no glue code |
| Zero dependencies | Reuse the built-in `Json` and `HttpTransport` (`JdkHttpTransport`) |
| Controllable | The model can only call declared tools; URLs are fixed by the plugin |
| Unified | The output is `Map<ToolSpecification, ToolExecutor>`, the same tool table as local / mcp |
| Extensible | `IHttpPlugin` SPI + curl interconversion, lowering integration cost |

---

## 2. Core Concepts

## 2.1 Three core abstractions

```text
HttpPlugin                    # 一个服务：baseUrl + 公共请求头 + 一组方法
  └── HttpPluginMethod        # 一个工具：name / description / httpMethod / uri / parameters
        └── HttpToolParameter # 一个参数：参数名 + 映射名 + 使用位置 + 数据类型 + 默认值 + 是否必填
```

<br/>

## 2.2 The four-dimensional parameter model (key)

A parameter is described by four dimensions, and this is precisely what makes the mapping from "declaration" to "actual request" possible:

| Dimension | Enum | Purpose |
|---|---|---|
| Use location | `ParameterUseType`: QUERY / BODY / PATH / HEADER | Determines whether the parameter ultimately lands in the URL, request body, or request header |
| Data type | `ParameterType`: STRING / INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT | Determines the JSON Schema type and runtime type conversion |
| Mapped name | `mappedName` | Model parameter name ↔ actual endpoint parameter name (internal/external decoupling, renamable) |
| Constraint | `defaultValue` / `required` | Default filling and required validation |

> **Key point**: What the model sees is `methodParamName` (the tool parameter name), while what is actually sent to the endpoint is `mappedName`—this lets "the name shown to the model" and "the name the endpoint wants" differ.

<br/>

## 2.3 Relationship with the Agent tool abstraction

| HTTP module | AgentForge contract |
|---|---|
| `HttpPluginMethod` + parameters | `ToolSpecification` (`parameters` → JSON Schema) |
| `HttpToolExecutor` | `ToolExecutor` (`execute` / `executeWithResult`) |
| `HttpToolFactory.buildHttpTools` | Produces `Map<ToolSpecification, ToolExecutor>`, handed to `ToolService` |

<br/>

## 2.4 Why curl support is also needed

The most common "endpoint sample" in a developer's hands is a curl command. Supporting `curl → HttpPlugin` lets "copy-paste a curl command" directly become an Agent-injectable tool; the reverse `HttpPlugin → curl` is convenient for debugging and documentation.

---

## 3. Implementation Approach

## 3.1 Comparison of three approaches

**Approach 1: Write a `@Tool` method for every endpoint**

- Pros: Type-safe, can reuse the local pattern.
- Drawbacks: Code bloat when there are many endpoints; changing one field requires a code change; high maintenance cost.

**Approach 2: A single generic HTTP tool (the model passes url / method / body)**

- Pros: Simplest implementation.
- Drawbacks: **The model can construct arbitrary URLs / parameters**, uncontrollable, unsafe, and prone to inventing endpoints; hard to do authentication and allowlisting.

**Approach 3: Declarative `HttpPlugin` mapping (adopted by this module)**

- Pros: Fixed addresses, controlled parameters, zero dependencies, batch description, and can be unified into `ToolService` with local / mcp.
- Drawbacks: Requires defining the plugin structure; complex authentication (OAuth refresh, etc.) still needs to be supplemented at the host layer.

> **Conclusion**: Adopt **Approach 3**—trade declarations for controllability, and mapping for zero dependencies.

<br/>

## 3.2 Core flow: List → Spec + Executor

`HttpToolFactory.buildHttpTools(httpPlugin)` produces a pair for each `HttpPluginMethod`:

```text
HttpPluginMethod
   ├─ methodName / description ───────────► ToolSpecification.name / description
   ├─ parameters[].toJsonSchema() ────────► ToolSpecification.parameters（JSON Schema）
   └─ (baseUrl+uri, httpMethod, headers, 参数配置) ─► new HttpToolExecutor(...)
```

> **Key point**: Unlike MCP's `serverAlias__tool`, HTTP tools are **not prefixed**—the tool name directly uses `methodName`, and uniqueness is naturally under the developer's control.

<br/>

## 3.3 Parameter processing pipeline (core)

Inside `HttpToolExecutor.execute`, processing follows a fixed order:

```text
arguments(JSON) ──► argumentsAsMap
        │
        ▼
processArguments      ① 取实参 / 套默认值 / 校验必填 / 按 dataType 转换
        │
        ├─ buildUrlWithPathParams   ② PATH 参数替换 {mappedName}
        ├─ addHeaderParameters      ③ HEADER 参数写入请求头
        ├─ shouldUseJsonBody ?
        │     ├─ true  → BODY 参数 → JSON Body（Content-Type: application/json）
        │     └─ false → appendQueryParameters（QUERY 参数拼到 URL）
        ▼
HttpRequest(url, method, headers, body) ──► HttpTransport.execute
```

- **Missing required parameter**: throws `IllegalArgumentException("缺少必填参数 'x'")`;
- **Type conversion**: `convertValueToCorrectType` supports INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT / STRING, and performs tolerant parsing of JSON strings.

<br/>

## 3.4 "Intelligent judgment" of the request body

```text
GET / DELETE   → 强制用 Query（即使配了 BODY 也不发 Body）
POST / PUT / PATCH → 只有当存在 BODY 参数（已传或有默认值）时才发 JSON Body
```

> **Note**: GET does not send a request body even if BODY-type parameters are configured (guaranteed by the `shouldNotSendJsonBodyForGetEvenWithBodyConfig` case).

<br/>

## 3.5 Retry and fault tolerance

- Retry **once** only on `connection reset` for **GET / DELETE** (safe under idempotent semantics);
- Other exceptions are caught in `execute` and degraded to the text `"HTTP请求失败: {message}"`.

> **Drawbacks**: The current `execute` degrades errors to **text** while `isError` remains `false`; when structured errors are needed, wrap them at the upper layer with `ToolExecutionResult` (for comparison: the MCP executor already performs lossless `isError` mapping).

<br/>

## 3.6 curl parsing and interconversion

| Direction | Entry point | Description |
|---|---|---|
| curl → plugin | `CurlParser.parse` + `CurlToHttpPluginConverter.convert` | Splits out url / method / headers / query / body and generates parameters |
| plugin → curl | `HttpPluginToCurlConverter.convert(plugin[, methodName])` | Generates a reproducible command in reverse; for multi-method plugins a method name must be specified |

> **Note**: When a plugin contains multiple methods, `convert(plugin)` is rejected and `methodName` must be specified explicitly.

<br/>

## 3.7 Capability matrix: request methods and parameter formats

**① Supported HTTP methods (`HttpPluginEnums.HttpMethod`)**

| Method | Code | Request body | Idempotent retry | Description |
|---|---|---|---|---|
| GET | 1 | No (**forced Query**) | ✅ Retry once on connection reset | Query |
| POST | 2 | JSON when there is a BODY parameter | ❌ | Create |
| PUT | 3 | JSON when there is a BODY parameter | ❌ | Full update |
| DELETE | 4 | No (forced Query) | ✅ Retry once on connection reset | Delete (idempotent) |
| PATCH | 5 | JSON when there is a BODY parameter | ❌ | Partial update |

> **Key point**: Parameters configured as BODY for GET / DELETE **are not** put into the request body and are ignored (see `shouldNotSendJsonBodyForGetEvenWithBodyConfig`).

<br/>

**② Parameter use locations (`ParameterUseType`)**

| Location | Code | Landing point | Generation rule |
|---|---|---|---|
| QUERY | 1 | URL query string | `encode(mappedName)=encode(value)` |
| BODY | 2 | JSON request body | `{mappedName: value}` → `Json.stringify` |
| PATH | 3 | URL path placeholder | Replace `{mappedName}` |
| HEADER | 4 | Request header | `mappedName: value` |

<br/>

**③ Parameter data types (`ParameterType`)**

| Type | Code | JSON Schema | Runtime conversion |
|---|---|---|---|
| STRING | 1 | `string` | `value.toString()` |
| INTEGER | 2 | `integer` | `Integer.parseInt` / `Number.intValue()` |
| NUMBER | 3 | `number` | `Double.parseDouble` / `Number.doubleValue()` |
| BOOLEAN | 4 | `boolean` | `"true"/"1"/"yes"` → true, otherwise false |
| ARRAY | 5 | `array` (items: string) | List used directly; JSON string parsed; otherwise wrapped as a single-element array |
| OBJECT | 6 | `object` | Map used directly; JSON string parsed; otherwise an empty Map |

<br/>

**④ Required and default values (`RequiredStatus` + `defaultValue`)**

| Item | Code | Behavior |
|---|---|---|
| REQUIRED | 1 | Both the actual argument and the default value are missing → throws `IllegalArgumentException("缺少必填参数 'x'")` |
| NOT_REQUIRED | 0 | If missing, it does not participate in this request |

> **Key point**: The value priority is **actual argument > `defaultValue` > required validation**; `methodParamName` is the model-side name, and `mappedName` is the name sent to the endpoint.

---

## 4. Practical Code

## 4.1 Minimal integration

```java
HttpPlugin plugin =
        HttpPlugin.builder()
                .baseUrl("https://api.example.com")
                .staticHeaders(Collections.singletonMap("Authorization", "Bearer " + token))
                .pluginMethods(
                        Collections.singletonList(
                                HttpPluginMethod.builder()
                                        .methodName("getWeather")
                                        .methodDescription("查询城市天气")
                                        .httpMethodType(HttpPluginEnums.HttpMethod.GET.getValue())
                                        .uri("/v1/weather")
                                        .parameters(
                                                Collections.singletonList(
                                                        HttpToolParameter.builder()
                                                                .methodParamName("city")
                                                                .methodParamDescription("城市名")
                                                                .mappedName("q")
                                                                .useTypeValue(
                                                                        HttpPluginEnums.ParameterUseType
                                                                                .QUERY
                                                                                .getValue())
                                                                .dataTypeValue(
                                                                        HttpPluginEnums.ParameterType
                                                                                .STRING
                                                                                .getValue())
                                                                .required(
                                                                        HttpPluginEnums.RequiredStatus
                                                                                .REQUIRED
                                                                                .getCode())
                                                                .build()))
                                        .build()))
                .build();

ToolService toolService = new ToolService();
toolService.tools(HttpToolFactory.buildHttpTools(plugin));

ReActAgent agent =
        ReActAgent.builder().chatModel(chatModel).toolService(toolService).build();
```

**Run output (simulated terminal)**

```shell
# 1) 注册后模型可见的工具（tools[] 摘要）
[getWeather] 查询城市天气
  parameters: { city: string (required) }   # 模型参数名 city → 实际接口参数名 ?q

# 2) 执行 getWeather(city=杭州) 的 HTTP 往返（java.util.logging，INFO）
=== HTTP 请求详情 ===
URL: https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E
方法: GET
请求头: {Authorization=Bearer sk-***}
QUERY 参数: {q=杭州}
=====================
=== HTTP 响应详情 ===
状态码: 200
耗时: 143 毫秒
响应体: {"city":"杭州","temp":26,"text":"晴"}
=====================
```

<br/>

## 4.2 Extending via `IHttpPlugin`

```java
public class DingTalkPlugin implements IHttpPlugin {
    private Properties props;

    @Override public void init(Properties props) { this.props = props; }
    @Override public String getPluginName() { return "dingtalk"; }

    @Override public void doCheckProps() {
        if (props.getProperty("token") == null) {
            throw new IllegalArgumentException("missing token");
        }
    }

    @Override public HttpPlugin getHttpPlugin() { /* 依据 props 构建 */ return plugin; }
}

toolService.tools(new DingTalkPlugin().buildHttpTools());
```

<br/>

## 4.3 One-click curl conversion

```java
CurlParseResult curl =
        new CurlParser()
                .parse(
                        "curl -X POST 'https://api.example.com/v1/tickets' "
                                + "-H 'Content-Type: application/json' "
                                + "-d '{\"title\":\"bug\"}'");
HttpPlugin plugin = new CurlToHttpPluginConverter().convert(curl);
toolService.tools(HttpToolFactory.buildHttpTools(plugin));
```

**Run output (simulated terminal)**

```shell
# CurlParser.parse(...) 解析出的关键字段
method  = POST
url     = https://api.example.com/v1/tickets
headers = {Content-Type=application/json}
data    = {"title":"bug"}

# CurlToHttpPluginConverter.convert(...) 生成的 HttpPlugin
baseUrl = https://api.example.com
uri     = /v1/tickets
method  = POST
params  = [ title: string (BODY:title, required) ]

# buildHttpTools(...) → 模型可见工具（方法名由 curl 自动生成 executePOST）
[executePOST] （由 curl 生成）  body: {"title": "..."}
```

<br/>

## 4.4 How a single model invocation is closed out

> **Corresponding unit test**: `HttpToolLoopTest#shouldCloseTheWeatherLoopThroughToolService`—injects a `RecordingHttpTransport` and runs the full offline path "declare → register → hit → assemble → send request → feed back".

Using `getWeather` from 4.1 as an example, breaking it down step by step:

**① Declaration and registration (build time)**

```java
Map<ToolSpecification, ToolExecutor> tools =
        HttpToolFactory.buildHttpTools(weatherPlugin(), transport); // transport 可注入（测试用假实现）
ToolService toolService = new ToolService();
toolService.tools(tools);                                           // toolExecutors["getWeather"] = executor
```

- `ToolSpecification.name = "getWeather"`, `parameters = { city: string(required) }`;
- `HttpToolExecutor(baseUrl + "/v1/weather", GET, {Authorization=Bearer token}, {city → (QUERY, STRING, required)})`.

**② Model-side function call**

```json
{"name": "getWeather", "arguments": "{\"city\":\"杭州\"}"}
```

> `arguments` is a **JSON string**, not an object.

**③ Normalized into a request**

```text
ToolExecutionRequest{ id="call_1", name="getWeather", arguments="{\"city\":\"杭州\"}" }
```

**④ ToolService locates the executor**

`toolExecutors.get("getWeather")` → hits `HttpToolExecutor` (HTTP tools are **not prefixed**; the name is exactly `methodName`).

**⑤ Parameter processing (processArguments)**

| Step | Input | Result |
|---|---|---|
| `argumentsAsMap` | `"{\"city\":\"杭州\"}"` | `{city: 杭州}` |
| Take actual argument | key = `city` | `杭州` |
| Required validation | REQUIRED | Pass |
| Type conversion | STRING | `"杭州"` |
| Location determination | QUERY, mapped name `q` | To be appended to the URL |

**⑥ Assemble `HttpRequest`**

- PATH: none; HEADER: none (the static `Authorization` is already in `staticHeaders`);
- `shouldUseJsonBody`: GET → `false` (no request body sent);
- `appendQueryParameters` → `?q=%E6%9D%AD%E5%B7%9E`;
- Finally: `GET https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E`, `body=""`.

**⑦ Issue and retrieve the response**

`JdkHttpTransport.execute(request)` → `200`, response body `{"city":"杭州","temp":26,"text":"晴"}`; `HttpToolExecutor.execute` returns the **response body text** directly.

**⑧ Feed back to the model**

The response body text is fed back as the tool result → the model produces its final answer accordingly.

**Run output (simulated terminal)**

```shell
[user]  查一下杭州天气
[think] 需要调用工具 getWeather
[act]   getWeather {"city":"杭州"}
[http]  GET https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E → 200 {"city":"杭州","temp":26,"text":"晴"}
[think] 杭州当前 26℃，天气晴。
[final] 杭州当前 26℃，天气晴。
```

---

## 5. Verification and Testing

## 5.1 Unit test overview

A `RecordingHttpTransport` records the received `HttpRequest`, asserting the URL / request headers / request body, with no real network required.

## 5.2 Test list (current implementation, all passing)

| Case | Coverage |
|---|---|
| `shouldSendGetQueryParameters` | QUERY parameter concatenation |
| `shouldSubstitutePathParameters` | PATH placeholder substitution |
| `shouldSendJsonBodyForPost` | BODY → JSON Body |
| `shouldConvertArrayAndObjectBodyValues` | ARRAY / OBJECT type conversion |
| `shouldParseJsonEncodedArrayString` | String array tolerance |
| `shouldAddStaticAndDynamicHeaders` | Static + HEADER parameters |
| `shouldApplyDefaultValueWhenArgumentMissing` | Default value |
| `shouldThrowWhenRequiredArgumentMissing` | Required validation |
| `shouldRetryOnceOnConnectionResetForGet` | Idempotent retry |
| `shouldReturnFailureTextWhenPostFails` | Failure-degradation text |
| `shouldNotSendJsonBodyForGetEvenWithBodyConfig` | GET does not send Body |
| `HttpToolLoopTest#shouldCloseTheWeatherLoopThroughToolService` | **End-to-end closure**: plugin build → ToolService → function call → GET request → feed back (aligned with 4.4) |
| `HttpToolFactoryTest` | Spec / Executor construction, `IHttpPlugin`, null rejection |
| `CurlParserConverterTest` | curl parsing, bidirectional conversion, multi-method rejection |

<br/>

## 5.3 Boundaries and risks

- **Error semantics**: `execute` degrades exceptions to text, with `isError=false`; structured errors must be wrapped by the caller.
- **Authentication complexity**: Static headers / parameters suffice; OAuth refresh, signing, etc. must be implemented at the host layer.
- **Timeouts**: Default connect 10 minutes, read 15 minutes (adjustable at construction time), rather lenient; tighten according to the scenario.

---

## 6. Summary and Outlook

1. **Essence**: Declaratively map "a set of HTTP endpoints" into model-callable `ToolSpecification` + `ToolExecutor`.
2. **Core**: The four-dimensional parameter model (location × type × mapped name × constraint) + a fixed-order parameter pipeline.
3. **Zero dependencies**: Reuses the built-in `Json` and `JdkHttpTransport`, consistent with the Java 8 / zero-dependency constraint.
4. **Unified**: The output is, like the other modes, `Map<ToolSpecification, ToolExecutor>` that can coexist in the same table.
5. **Outlook**: Structured errors (`isError`), OpenAPI import, finer timeout / retry strategies.

---

## References

[1]. [MDN HTTP Request Methods](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods)

[2]. [JSON Schema Official Site](https://json-schema.org/)

[3]. [curl Command Manual](https://curl.se/docs/manpage.html)

[4]. [RFC 7231: HTTP/1.1 Semantics and Methods](https://www.rfc-editor.org/rfc/rfc7231)

[5]. [OpenAPI Specification](https://spec.openapis.org/oas/latest.html)

[6]. Related internal documents: [Core Design Principles of the Local Tool Module](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/local/Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理), [MCP Framework Research and AgentForge Integration Implementation](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现), [ChatModel Core Protocol Layer Design](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现)

<br/>

Compiler: changlu Created: 2026.10.5 Updated: 2026.10.5
