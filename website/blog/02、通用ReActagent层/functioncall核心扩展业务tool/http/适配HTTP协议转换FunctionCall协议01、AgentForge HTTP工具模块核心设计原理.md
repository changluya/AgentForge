---
title: "适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理"
date: 2026-10-08
tags: [AgentForge, 通用ReActagent层, Function Calling, HTTP]
---

# AgentForge HTTP 工具模块核心设计原理

> 更新日期：2026-10-05  
> 适用模块：`agentforge-agent-core`（`cloud.changlu.agentforge.agent.tool.http`）  
> 依赖契约：`agentforge-model-api` / `agentforge-model-core`  
> 维护者：changlu

{/* truncate */}


一句话结论：**HTTP 工具模块把"一组 HTTP 接口"声明式地映射为 AgentForge 的 `ToolSpecification` + `ToolExecutor`，让模型能像调用本地方法一样调用任意 REST 接口，而无需为零依赖核心引入任何 HTTP / JSON 第三方库。**

---

## 一、背景与问题引入

## 1.1、场景驱动：接入钉钉机器人时，我们卡在了哪

在为 AgentForge 做外部能力接入时，我们遇到一个很现实的场景：

> 团队希望 Agent 能发钉钉消息、查 CRM 客户、拉工单列表。这些能力**既没有 MCP Server，也不值得为每个接口写一个 Java `@Tool` 方法**——它们只是普通的 REST API。

由此带来三个具体痛点：

1. **接口多**：一个 SaaS 动辄几十个接口，逐个写 `@Tool` 方法维护成本极高；
2. **模型无从下手**：把"一个通用 HTTP 工具"丢给模型，它会自己编 URL、乱加参数，既不可控也不安全；
3. **核心零依赖**：AgentForge 核心是 Java 8 + 零第三方库，不能为接 HTTP 引入 OkHttp / Retrofit / Jackson。

<br/>

## 1.2、问题引导：如何让模型"说人话"就能调 HTTP？

> **问题**：如何让模型把一句自然语言，变成一次**参数正确、地址可控**的 HTTP 调用？

答案不是"让模型自己拼 HTTP"，而是：

- 由开发者**预先声明**每个接口（地址、方法、参数）→ 生成 `ToolSpecification`；
- 模型只在**已声明的工具**里选择、只填**声明的参数**；
- 运行时由执行器把参数"填"进 URL / Header / Body 并发起请求。

这正是本模块要做的事。

<br/>

## 1.3、设计目标与约束

| 目标 | 说明 |
|---|---|
| 声明式 | 用 `HttpPlugin` 描述一组接口，不写胶水代码 |
| 零依赖 | 复用内置 `Json` 与 `HttpTransport`（`JdkHttpTransport`） |
| 可控 | 模型只能调用已声明工具，URL 由插件固定 |
| 统一 | 产物是 `Map<ToolSpecification, ToolExecutor>`，与 local / mcp 同一张工具表 |
| 可扩展 | `IHttpPlugin` SPI + curl 互转，降低接入成本 |

---

## 二、核心概念讲解

## 2.1、三个核心抽象

```text
HttpPlugin                    # 一个服务：baseUrl + 公共请求头 + 一组方法
  └── HttpPluginMethod        # 一个工具：name / description / httpMethod / uri / parameters
        └── HttpToolParameter # 一个参数：参数名 + 映射名 + 使用位置 + 数据类型 + 默认值 + 是否必填
```

<br/>

## 2.2、参数的四维模型（重点）

一个参数被四个维度描述，正是它让"声明"到"真实请求"的映射成为可能：

| 维度 | 枚举 | 作用 |
|---|---|---|
| 使用位置 | `ParameterUseType`：QUERY / BODY / PATH / HEADER | 决定参数最终落到 URL、请求体还是请求头 |
| 数据类型 | `ParameterType`：STRING / INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT | 决定 JSON Schema 类型与运行时类型转换 |
| 映射名 | `mappedName` | 模型参数名 ↔ 真实接口参数名（内外解耦，可改名） |
| 约束 | `defaultValue` / `required` | 缺省填充与必填校验 |

> **重点**：模型看到的是 `methodParamName`（工具参数名），真正发给接口的是 `mappedName`——这让"给模型看的名字"和"接口要的名字"可以不同。

<br/>

## 2.3、与 Agent 工具抽象的关系

| HTTP 模块 | AgentForge 契约 |
|---|---|
| `HttpPluginMethod` + 参数 | `ToolSpecification`（`parameters` → JSON Schema） |
| `HttpToolExecutor` | `ToolExecutor`（`execute` / `executeWithResult`） |
| `HttpToolFactory.buildHttpTools` | 产出 `Map<ToolSpecification, ToolExecutor>`，交给 `ToolService` |

<br/>

## 2.4、为什么还要支持 curl

开发者手里最常见的"接口样本"就是一段 curl。支持 `curl → HttpPlugin`，能把"复制粘贴一段 curl"直接变成可注入 Agent 的工具；反向 `HttpPlugin → curl` 则方便调试与文档化。

---

## 三、实现思路

## 3.1、三种方案对比

**方案一：每个接口写一个 `@Tool` 方法**

- 优点：类型安全、可复用 local 模式。
- 弊端说明：接口多则代码膨胀；改一个字段要改代码；维护成本高。

**方案二：一个通用 HTTP 工具（模型传 url / method / body）**

- 优点：实现最简单。
- 弊端说明：**模型可任意构造 URL / 参数**，不可控、不安全，且易编造接口；难以做鉴权与白名单。

**方案三：声明式 `HttpPlugin` 映射（本模块采用）**

- 优点：地址固定、参数受控、零依赖、可批量描述、可与 local / mcp 统一进 `ToolService`。
- 弊端说明：需定义插件结构；复杂鉴权（OAuth 刷新等）仍需宿主层补。

> **结论**：采用**方案三**——用声明换可控，用映射换零依赖。

<br/>

## 3.2、核心流程：List → Spec + Executor

`HttpToolFactory.buildHttpTools(httpPlugin)` 对每个 `HttpPluginMethod` 生成一对：

```text
HttpPluginMethod
   ├─ methodName / description ───────────► ToolSpecification.name / description
   ├─ parameters[].toJsonSchema() ────────► ToolSpecification.parameters（JSON Schema）
   └─ (baseUrl+uri, httpMethod, headers, 参数配置) ─► new HttpToolExecutor(...)
```

> **重点**：与 MCP 的 `serverAlias__tool` 不同，HTTP 工具**不加前缀**——工具名直接用 `methodName`，天然由开发者控制唯一性。

<br/>

## 3.3、参数处理流水线（核心）

`HttpToolExecutor.execute` 内部按固定顺序处理：

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

- **必填缺失**：抛 `IllegalArgumentException("缺少必填参数 'x'")`；
- **类型转换**：`convertValueToCorrectType` 支持 INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT / STRING，并对 JSON 字符串做容错解析。

<br/>

## 3.4、请求体的"智能判断"

```text
GET / DELETE   → 强制用 Query（即使配了 BODY 也不发 Body）
POST / PUT / PATCH → 只有当存在 BODY 参数（已传或有默认值）时才发 JSON Body
```

> **注意**：GET 即使配了 BODY 类型参数也不会发送请求体（由 `shouldNotSendJsonBodyForGetEvenWithBodyConfig` 用例保证）。

<br/>

## 3.5、重试与容错

- 仅对 **GET / DELETE** 的 `connection reset` 做**一次**重试（幂等语义下安全）；
- 其余异常在 `execute` 中兜底为文本 `"HTTP请求失败: {message}"`。

> **弊端说明**：当前 `execute` 把错误降级为**文本**，`isError` 仍为 `false`；需要结构化错误时可在上层用 `ToolExecutionResult` 包装（对比：MCP 执行器已做 `isError` 无损映射）。

<br/>

## 3.6、curl 解析与互转

| 方向 | 入口 | 说明 |
|---|---|---|
| curl → 插件 | `CurlParser.parse` + `CurlToHttpPluginConverter.convert` | 拆出 url / method / headers / query / body，生成参数 |
| 插件 → curl | `HttpPluginToCurlConverter.convert(plugin[, methodName])` | 反向生成可复现命令；多方法插件需指定方法名 |

> **注意**：一个插件含多个方法时，`convert(plugin)` 会拒绝，必须显式指定 `methodName`。

<br/>

## 3.7、能力矩阵：请求方法与参数格式

**① 支持的 HTTP 方法（`HttpPluginEnums.HttpMethod`）**

| 方法 | Code | 请求体 | 幂等重试 | 说明 |
|---|---|---|---|---|
| GET | 1 | 否（**强制 Query**） | ✅ 连接重置重试 1 次 | 查询 |
| POST | 2 | 有 BODY 参数时发 JSON | ❌ | 创建 |
| PUT | 3 | 有 BODY 参数时发 JSON | ❌ | 全量更新 |
| DELETE | 4 | 否（强制 Query） | ✅ 连接重置重试 1 次 | 删除（幂等） |
| PATCH | 5 | 有 BODY 参数时发 JSON | ❌ | 局部更新 |

> **重点**：GET / DELETE 配置为 BODY 的参数**不会**进请求体，会被忽略（见 `shouldNotSendJsonBodyForGetEvenWithBodyConfig`）。

<br/>

**② 参数使用位置（`ParameterUseType`）**

| 位置 | Code | 落点 | 生成规则 |
|---|---|---|---|
| QUERY | 1 | URL 查询串 | `encode(mappedName)=encode(value)` |
| BODY | 2 | JSON 请求体 | `{mappedName: value}` → `Json.stringify` |
| PATH | 3 | URL 路径占位符 | 替换 `{mappedName}` |
| HEADER | 4 | 请求头 | `mappedName: value` |

<br/>

**③ 参数数据类型（`ParameterType`）**

| 类型 | Code | JSON Schema | 运行时转换 |
|---|---|---|---|
| STRING | 1 | `string` | `value.toString()` |
| INTEGER | 2 | `integer` | `Integer.parseInt` / `Number.intValue()` |
| NUMBER | 3 | `number` | `Double.parseDouble` / `Number.doubleValue()` |
| BOOLEAN | 4 | `boolean` | `"true"/"1"/"yes"` → true，否则 false |
| ARRAY | 5 | `array`（items: string） | List 直用；JSON 字符串解析；否则包成单元素数组 |
| OBJECT | 6 | `object` | Map 直用；JSON 字符串解析；否则空 Map |

<br/>

**④ 必填与默认值（`RequiredStatus` + `defaultValue`）**

| 项 | Code | 行为 |
|---|---|---|
| REQUIRED | 1 | 实参与默认值都缺失 → 抛 `IllegalArgumentException("缺少必填参数 'x'")` |
| NOT_REQUIRED | 0 | 缺失即不参与本次请求 |

> **重点**：取值优先级为 **实参 > `defaultValue` > 必填校验**；`methodParamName` 是模型侧名字，`mappedName` 才是发给接口的名字。

---

## 四、实战代码

## 4.1、最小接入

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

**运行输出（模拟终端）**

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

## 4.2、通过 `IHttpPlugin` 扩展

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

## 4.3、curl 一键转换

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

**运行输出（模拟终端）**

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

## 4.4、一次模型调用如何被闭环

> **对应单测**：`HttpToolLoopTest#shouldCloseTheWeatherLoopThroughToolService`——注入 `RecordingHttpTransport`，离线完整走通"声明 → 注册 → 命中 → 组装 → 发请求 → 回灌"。

以 4.1 的 `getWeather` 为例，逐环节拆解：

**① 声明与注册（build 期）**

```java
Map<ToolSpecification, ToolExecutor> tools =
        HttpToolFactory.buildHttpTools(weatherPlugin(), transport); // transport 可注入（测试用假实现）
ToolService toolService = new ToolService();
toolService.tools(tools);                                           // toolExecutors["getWeather"] = executor
```

- `ToolSpecification.name = "getWeather"`，`parameters = { city: string(required) }`；
- `HttpToolExecutor(baseUrl + "/v1/weather", GET, {Authorization=Bearer token}, {city → (QUERY, STRING, required)})`。

**② 模型侧 function call**

```json
{"name": "getWeather", "arguments": "{\"city\":\"杭州\"}"}
```

> `arguments` 是 **JSON 字符串**，不是对象。

**③ 归一化为请求**

```text
ToolExecutionRequest{ id="call_1", name="getWeather", arguments="{\"city\":\"杭州\"}" }
```

**④ ToolService 定位执行器**

`toolExecutors.get("getWeather")` → 命中 `HttpToolExecutor`（HTTP 工具**不加前缀**，名字即 `methodName`）。

**⑤ 参数处理（processArguments）**

| 步骤 | 输入 | 结果 |
|---|---|---|
| `argumentsAsMap` | `"{\"city\":\"杭州\"}"` | `{city: 杭州}` |
| 取实参 | key = `city` | `杭州` |
| 必填校验 | REQUIRED | 通过 |
| 类型转换 | STRING | `"杭州"` |
| 位置判定 | QUERY，映射名 `q` | 待拼到 URL |

**⑥ 组装 `HttpRequest`**

- PATH：无；HEADER：无（静态 `Authorization` 已在 `staticHeaders`）；
- `shouldUseJsonBody`：GET → `false`（不发请求体）；
- `appendQueryParameters` → `?q=%E6%9D%AD%E5%B7%9E`；
- 最终：`GET https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E`，`body=""`。

**⑦ 发起并取回响应**

`JdkHttpTransport.execute(request)` → `200`，响应体 `{"city":"杭州","temp":26,"text":"晴"}`；`HttpToolExecutor.execute` 直接返回**响应体文本**。

**⑧ 回灌模型**

响应体文本作为工具结果回灌 → 模型据此给出最终答复。

**运行输出（模拟终端）**

```shell
[user]  查一下杭州天气
[think] 需要调用工具 getWeather
[act]   getWeather {"city":"杭州"}
[http]  GET https://api.example.com/v1/weather?q=%E6%9D%AD%E5%B7%9E → 200 {"city":"杭州","temp":26,"text":"晴"}
[think] 杭州当前 26℃，天气晴。
[final] 杭州当前 26℃，天气晴。
```

---

## 五、验证测试

## 5.1、单元测试概览

用 `RecordingHttpTransport` 记录收到的 `HttpRequest`，断言 URL / 请求头 / 请求体，无需真实网络。

## 5.2、测试清单（当前实现，全部通过）

| 用例 | 覆盖点 |
|---|---|
| `shouldSendGetQueryParameters` | QUERY 参数拼接 |
| `shouldSubstitutePathParameters` | PATH 占位符替换 |
| `shouldSendJsonBodyForPost` | BODY → JSON Body |
| `shouldConvertArrayAndObjectBodyValues` | ARRAY / OBJECT 类型转换 |
| `shouldParseJsonEncodedArrayString` | 字符串数组容错 |
| `shouldAddStaticAndDynamicHeaders` | 静态 + HEADER 参数 |
| `shouldApplyDefaultValueWhenArgumentMissing` | 默认值 |
| `shouldThrowWhenRequiredArgumentMissing` | 必填校验 |
| `shouldRetryOnceOnConnectionResetForGet` | 幂等重试 |
| `shouldReturnFailureTextWhenPostFails` | 失败降级文本 |
| `shouldNotSendJsonBodyForGetEvenWithBodyConfig` | GET 不发 Body |
| `HttpToolLoopTest#shouldCloseTheWeatherLoopThroughToolService` | **端到端闭环**：插件构建 → ToolService → function call → GET 请求 → 回灌（对齐 4.4） |
| `HttpToolFactoryTest` | Spec / Executor 构建、`IHttpPlugin`、null 拒绝 |
| `CurlParserConverterTest` | curl 解析、双向转换、多方法拒绝 |

<br/>

## 5.3、边界与风险

- **错误语义**：`execute` 把异常降级为文本，`isError=false`，需要结构化错误要自行包装。
- **鉴权复杂度**：静态头 / 参数够用；OAuth 刷新、签名等需宿主层实现。
- **超时**：默认连接 10 分钟、读取 15 分钟（构造时可调），偏宽松，按场景收紧。

---

## 六、总结与展望

1. **本质**：把"一组 HTTP 接口"声明式映射成模型可调用的 `ToolSpecification` + `ToolExecutor`。
2. **核心**：四维参数模型（位置 × 类型 × 映射名 × 约束）+ 固定顺序的参数流水线。
3. **零依赖**：复用内置 `Json` 与 `JdkHttpTransport`，与 Java 8 / 零依赖约束一致。
4. **统一**：产物与其他模式一样是 `Map<ToolSpecification, ToolExecutor>`，可同表共存。
5. **展望**：结构化错误（`isError`）、OpenAPI 导入、更细的超时 / 重试策略。

---

## 参考资料

[1]. [MDN HTTP 请求方法](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods)

[2]. [JSON Schema 官方站点](https://json-schema.org/)

[3]. [curl 命令手册](https://curl.se/docs/manpage.html)

[4]. [RFC 7231：HTTP/1.1 语义与方法](https://www.rfc-editor.org/rfc/rfc7231)

[5]. [OpenAPI 规范](https://spec.openapis.org/oas/latest.html)

[6]. 相关内部文档：[Local 工具模块核心设计原理](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/local/Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理)、[MCP 框架调研与 AgentForge 接入实现](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现)、[ChatModel 核心协议层设计](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
