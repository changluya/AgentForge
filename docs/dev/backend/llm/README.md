# AgentForge LLM Core 设计与开发文档

> 更新日期：2026-09-13  
> 适用版本：release_1.x  
> 适用模块：`agentforge-llm-core`  
> 包前缀：`com.changlu.agentforge.llm`  
> 维护者：changlu

本文件统一沉淀 AgentForge `agentforge-llm-core` 的核心接口、请求响应模型、消息体系、HTTP SPI、异常边界与 release_1.x 演进约束。Provider 相关的 Wire Protocol 单独放在 `openai/` 与 `anthropic/` 目录中，避免 Core 设计与厂商协议混杂。

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前 `README.md` | `agentforge-llm-core` 核心接口、对象模型、`ChatMessage`、HTTP SPI、异常与扩展设计 |
| [openai/chatmodel-protocol.md](./openai/chatmodel-protocol.md) | OpenAI ChatModel 标准入参、出参、字段映射与流式协议 |
| [anthropic/chatmodel-protocol.md](./anthropic/chatmodel-protocol.md) | Anthropic Messages ChatModel 标准入参、出参与字段映射协议 |

## 当前模块关系

```text
agentforge-llm
├── agentforge-llm-core
│   ├── ChatModel / StreamingChatModel
│   ├── ChatMessage（AiMessage / UserMessage + Content / ToolExecutionRequest / ToolExecutionResultMessage ...）
│   ├── ChatRequest / ChatRequestParameters（tools / toolChoice / toolChoiceName）
│   ├── tool（Tool / ToolExecutor，spec / execution / error 子包）
│   ├── ToolChoice
│   ├── ChatResponse / TokenUsage / FinishReason
│   ├── HttpTransport / JdkHttpTransport
│   └── LlmException / Json
├── agentforge-llm-openai
│   ├── OpenAiChatModel
│   └── OpenAiStreamingChatModel
└── agentforge-llm-anthropic
    ├── AnthropicChatModel
    ├── AnthropicStreamingChatModel
    └── AnthropicProtocol（Blocking / Streaming 共享 wire mapping）
```

设计原则是：**Core 只定义稳定、Provider-neutral 的 LLM 边界；OpenAI、Anthropic 等模块只负责协议转换，不把供应商 SDK 类型泄露到上层。**

---

## 1. 模块定位与设计目标

`agentforge-llm-core` 是 AgentForge 最底层、最稳定的模型访问抽象层。它不直接绑定 OpenAI、Anthropic 或任何第三方 Java SDK，而是定义统一的请求、响应、消息、流式回调、HTTP 传输和异常边界。

核心目标有四个：

1. **Provider-neutral**：Agent、Memory、Tool Calling 等上层模块依赖 Core，而不是依赖供应商 SDK。
2. **协议适配下沉**：OpenAI / Anthropic 的字段名、Header、SSE 格式、finish reason 等差异全部由 Provider 模块消化。
3. **Java 8 API 兼容**：默认开发环境可以使用 JDK 17，但公共 LLM API 避免依赖 Java 9+ 语言特性和集合工厂。
4. **为后续 Agent 能力预留扩展点**：消息类型、`customParameters`、`metadata`、`HttpTransport` 都保留扩展空间。

整体调用链如下：

```text
Agent / Application
       │
       ▼
ChatModel / StreamingChatModel
       │
       ▼
ChatRequest + ChatMessage + ChatRequestParameters
       │
       ▼
Provider Adapter(OpenAI / Anthropic / ...)
       │
       ▼
HttpTransport
       │
       ▼
Provider HTTP API
       │
       ▼
Provider JSON / SSE
       │
       ▼
ChatResponse + TokenUsage + FinishReason
```

### 1.1 包结构

```text
com.changlu.agentforge.llm
├── tool
│   ├── Tool.java / P.java / ReturnBehavior.java / ToolExecutor.java   // 核心契约
│   ├── spec
│   │   ├── ToolSpecification.java     // 工具（函数）声明：name/description/parameters/strict
│   │   ├── ToolParameters.java        // JSON-Schema 风格参数定义（纯 Map，无第三方依赖）
│   │   └── ToolSpecifications.java    // @Tool 方法 -> ToolSpecification/ToolParameters
│   ├── execution
│   │   ├── ToolService.java           // 工具注册 + 推理/执行循环
│   │   ├── DefaultToolExecutor.java   // @Tool 方法反射执行器
│   │   ├── ToolExecution.java / ToolExecutionResult.java / ToolExecutionRequestUtil.java
│   └── error
│       ├── ToolArgumentsException.java / ToolExecutionException.java
│       └── ToolArgumentsErrorHandler.java / ToolExecutionErrorHandler.java /
│           ToolErrorContext.java / ToolErrorHandlerResult.java
├── chat
│   ├── ChatModel.java
│   ├── StreamingChatModel.java
│   ├── message
│   │   ├── ChatMessage.java
│   │   ├── ChatMessageType.java
│   │   ├── SystemMessage.java
│   │   ├── UserMessage.java           // name + List<Content>
│   │   ├── AiMessage.java             // text + thinking + toolExecutionRequests + attributes
│   │   ├── ToolExecutionRequest.java  // 模型发起的一次工具调用（id/name/arguments）
│   │   ├── ToolExecutionResultMessage.java
│   │   ├── CustomMessage.java
│   │   ├── Content.java               // 多模态内容基接口
│   │   ├── ContentType.java
│   │   └── TextContent.java
│   ├── request
│   │   ├── ChatRequest.java
│   │   ├── ChatRequestParameters.java // 新增 tools() / toolChoice() / toolChoiceName()
│   │   ├── DefaultChatRequestParameters.java
│   │   └── ToolChoice.java            // AUTO / NONE / REQUIRED / SPECIFIC
│   └── response
├── exception
│   └── LlmException.java
├── http
│   ├── HttpRequest.java
│   ├── HttpResponse.java
│   ├── HttpTransport.java
│   ├── JdkHttpTransport.java
│   └── StreamingHttpResponseHandler.java
└── internal
    └── json
        └── Json.java
```

## 2. ChatModel 请求侧核心抽象

### 2.1 ChatModel

`ChatModel` 是同步模型的最低稳定边界：

```java
@FunctionalInterface
public interface ChatModel {
    ChatResponse chat(ChatRequest chatRequest);
}
```

所有 Provider 最终只需要实现一个主入口：

```java
ChatResponse chat(ChatRequest chatRequest)
```

同时 Core 提供三个便利方法：

```java
String chat(String userMessage)
ChatResponse chat(ChatMessage... messages)
ChatResponse chat(List<? extends ChatMessage> messages)
```

它们最终都会收敛成 `ChatRequest`，因此 Provider 不需要重复实现多套入口。

推荐上层模块始终依赖：

```java
ChatModel model = ...;
ChatResponse response = model.chat(request);
```

而不是判断 Provider 类型：

```java
// 不推荐
if (model instanceof OpenAiChatModel) {
    ...
}
```

### 2.2 StreamingChatModel

流式模型独立定义为：

```java
public interface StreamingChatModel {
    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);
}
```

这里没有使用 `Flow`、Reactive Streams 或 Reactor，主要原因是当前 Core 需要保持轻量和 Java 8 兼容，不希望最底层协议层强依赖某个响应式框架。

流式生命周期被规范为：

```text
开始请求
   │
   ├── onPartialResponse(partialText)  0..N 次
   │
   ├── onCompleteResponse(response)   成功时恰好 1 次
   │
   └── onError(error)                 失败时 1 次
```

`onCompleteResponse` 返回的是已经聚合完成的标准 `ChatResponse`，因此流式与非流式最终可以复用统一的响应模型。

### 2.3 ChatRequest

`ChatRequest` 是 Provider-neutral 的不可变请求对象：

```java
public final class ChatRequest {
    private final List<ChatMessage> messages;
    private final ChatRequestParameters parameters;
}
```

约束：

- `messages` 必须至少包含一条消息；
- Builder 接收单条或批量消息；
- 构建后对消息列表进行不可变包装；
- `parameters` 允许为空，此时使用 Provider Model Builder 的默认参数。

示例：

```java
ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("You are a Java assistant."))
        .message(UserMessage.from("Explain CompletableFuture."))
        .parameters(DefaultChatRequestParameters.builder()
                .modelName("provider-model")
                .temperature(0.2)
                .maxTokens(1024)
                .build())
        .build();
```

### 2.4 ChatRequestParameters

Core 当前抽象的通用参数为：

| Core 字段 | Java 类型 | 含义 |
|---|---|---|
| `modelName()` | `String` | Provider 模型 ID |
| `temperature()` | `Double` | 采样随机度，是否支持由 Provider 决定 |
| `maxTokens()` | `Integer` | 最大输出 Token 数 |
| `topP()` | `Double` | nucleus sampling 参数 |
| `stopSequences()` | `List<String>` | 停止序列 |
| `customParameters()` | `Map<String,Object>` | Provider 特有扩展字段 |
| `tools()` | `List<ToolSpecification>` | 本次请求允许模型调用的工具声明 |
| `toolChoice()` | `ToolChoice` | `AUTO` / `NONE` / `REQUIRED` / `SPECIFIC` |
| `toolChoiceName()` | `String` | `SPECIFIC` 时指定的工具名 |

`tools()` / `toolChoice()` / `toolChoiceName()` 均以接口默认方法提供（返回 `null`），因此已有
`ChatRequestParameters` 实现无需改动即可编译。`DefaultChatRequestParameters` 提供对应 Builder
方法：`tools(List)`、`tool(ToolSpecification)`、`toolChoice(ToolChoice)`、`toolChoiceName(String)`。

Function Calling 请求侧映射：

```text
DefaultChatRequestParameters.tools()/toolChoice()
        │
        ├── OpenAI      -> payload.tools[]（type=function） + payload.tool_choice
        └── Anthropic   -> payload.tools[]（input_schema）  + payload.tool_choice
```


Core 不强行规定每个 Provider 必须支持全部字段。Provider 应明确完成以下三种处理之一：

```text
Core 通用参数
   ├── Provider 原生支持 -> 映射成供应商字段
   ├── Provider 不支持   -> 忽略或显式校验失败
   └── Provider 特有能力 -> 通过 customParameters 扩展
```

### 2.5 DefaultChatRequestParameters 合并规则

Provider Model Builder 中保存一套 model-level defaults；单次 `ChatRequest.parameters()` 作为 request-level overrides。

当前合并规则是：**请求级非空值覆盖模型级默认值**。

```text
Model Builder defaults
        │
        ▼
DefaultChatRequestParameters.merge(defaults, overrides)
        ▲
        │
Request overrides
```

例如：

```java
OpenAiChatModel.builder()
        .modelName("model-a")
        .temperature(0.7)
        .maxTokens(2048)
        .build();
```

某次请求只覆盖温度：

```java
DefaultChatRequestParameters.builder()
        .temperature(0.1)
        .build();
```

最终参数：

```text
modelName   = model-a
maxTokens   = 2048
temperature = 0.1
```

`customParameters` 采用 Map 合并方式，请求级同名 key 会覆盖模型级同名 key。

## 3. 消息与响应模型

### 3.1 ChatMessage

`ChatMessage` 只定义消息类型，同时保留 text-first 兼容桥接：

```java
public interface ChatMessage {
    ChatMessageType type();

    default String text() {
        throw new UnsupportedOperationException(...);
    }
}
```

当前消息类型：

| 类型 | 实现类 | 当前语义 |
|---|---|---|
| `SYSTEM` | `SystemMessage` | 系统指令 |
| `USER` | `UserMessage` | 用户输入，可携带 `name` 与多模态 `List<Content>` |
| `AI` | `AiMessage` | 模型输出 / 历史助手消息，可携带 `thinking` 与 `toolExecutionRequests` |
| `TOOL_EXECUTION_RESULT` | `ToolExecutionResultMessage` | 工具执行结果 |
| `CUSTOM` | `CustomMessage` | Provider 特有消息 |

`SystemMessage` 继续继承 `AbstractTextMessage`。`AiMessage`、`UserMessage` 已改为直接实现
`ChatMessage`：`AiMessage` 承载 `text / thinking / toolExecutionRequests / attributes`，`UserMessage`
承载 `name / List<Content>`；`ToolExecutionResultMessage` 额外携带 `id / toolName / isError / attributes`；
`CustomMessage` 不具备强制文本语义。

详细消息设计见本文 **第 6 章：ChatMessage 消息体系设计**，Function Calling 调用链见
**第 7 章：Function Calling（工具调用）设计**。

### 3.2 ChatResponse

所有 Provider 的返回最终标准化为：

```java
public final class ChatResponse {
    private final AiMessage aiMessage;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;
}
```

字段职责：

| 字段 | 是否必须 | 说明 |
|---|---|---|
| `aiMessage` | 是 | 最终模型文本消息 |
| `tokenUsage` | 否 | 标准化 Token 统计 |
| `finishReason` | 否 | 标准化结束原因 |
| `metadata` | 否 | 保存 Provider 原始但通用层不需要理解的元数据 |

设计重点是：Provider 的原始 JSON 不直接向上泄露，但关键 Provider 信息可以保留到 `metadata` 中。

例如 OpenAI 当前保存：

```text
id
model
created
```

Anthropic 当前保存：

```text
id
model
type
```

### 3.3 TokenUsage

统一 Token 统计：

```java
public final class TokenUsage {
    long inputTokens;
    long outputTokens;
    long totalTokens;
}
```

不同 Provider 映射关系不同：

```text
OpenAI:
prompt_tokens     -> inputTokens
completion_tokens -> outputTokens
total_tokens      -> totalTokens

Anthropic:
input_tokens      -> inputTokens
output_tokens     -> outputTokens
input + output    -> totalTokens
```

### 3.4 FinishReason

Core 当前统一为：

```java
public enum FinishReason {
    STOP,
    LENGTH,
    TOOL_EXECUTION,
    CONTENT_FILTER,
    OTHER
}
```

Provider 适配器负责完成原生枚举到 Core 枚举的转换。

典型映射：

```text
OpenAI stop          -> STOP
OpenAI length        -> LENGTH
OpenAI tool_calls    -> TOOL_EXECUTION
OpenAI content_filter-> CONTENT_FILTER

Anthropic end_turn      -> STOP
Anthropic stop_sequence -> STOP
Anthropic max_tokens    -> LENGTH
Anthropic tool_use      -> TOOL_EXECUTION
```

这里刻意不把 Provider 原生枚举放进 Core，避免上层代码出现供应商分支。

## 4. HTTP、JSON 与异常边界

### 4.1 HttpTransport SPI

Core 定义非常小的 HTTP SPI：

```java
public interface HttpTransport {
    HttpResponse execute(HttpRequest request) throws IOException;

    default void executeStreaming(
            HttpRequest request,
            StreamingHttpResponseHandler handler) {
        ...
    }
}
```

Provider 只依赖 `HttpTransport`，因此可以：

- 默认使用 `JdkHttpTransport`；
- 测试时注入 Fake Transport；
- 后续适配 OkHttp、Apache HttpClient 或企业内部网关；
- 不修改 ChatModel API。

### 4.2 JdkHttpTransport

当前默认实现基于 `HttpURLConnection`，不引入第三方 HTTP 依赖。

同步请求：

```text
HttpRequest
   -> openConnection
   -> writeBody
   -> responseCode
   -> readBody
   -> HttpResponse
```

流式请求会使用 daemon worker thread 异步读取响应行：

```text
executeStreaming
   -> daemon thread
   -> onOpen(status, headers)
   -> onLine(line) ...
   -> onComplete()
```

底层 Transport **不解析 SSE**。SSE 是供应商协议的一部分，必须由 OpenAI / Anthropic Provider 自己解析。

### 4.3 StreamingHttpResponseHandler

底层流处理接口只认识 HTTP：

```java
void onOpen(int statusCode, Map<String, List<String>> headers);
void onLine(String line);
void onComplete();
void onError(Throwable error);
```

这与上层 `StreamingChatResponseHandler` 有意分离：

```text
HTTP line/SSE event
       │
       ▼
Provider Stream Parser
       │
       ▼
partial text / ChatResponse
```

因此未来 Anthropic SSE 即使事件格式与 OpenAI 完全不同，也不需要修改 Core。

### 4.4 LlmException

Provider/Transport 统一使用 `LlmException` 向上抛出模型调用错误：

```java
public class LlmException extends RuntimeException {
    private final Integer statusCode;
    private final String responseBody;
}
```

它既可以表达：

- HTTP 4xx / 5xx；
- 网络 IOException；
- JSON 解析失败；
- Provider 响应结构不符合预期。

HTTP 非 2xx 时建议保留：

```text
message
statusCode
responseBody
```

便于后续统一做 retry、rate-limit、日志和可观测性。

### 4.5 internal.json.Json

当前 JSON 能力放在 `internal` 包中，意味着它不是 AgentForge 对外公共 API。

Provider 通过它完成：

```text
Java Map/List -> JSON String
JSON String    -> Map/List
```

这让 `agentforge-llm-core` 保持依赖轻量。未来即使内部替换 JSON 实现，也不应影响 `ChatModel`、`ChatRequest`、`ChatResponse` 等公共契约。

## 5. 扩展规范与 release_1.x 边界

### 5.1 新增 Provider 的标准实现方式

新增供应商时建议只创建新的 Provider 模块：

```text
agentforge-llm-xxx
└── XxxChatModel implements ChatModel
```

实现过程固定为五步：

```text
1. merge 默认参数和请求参数
2. ChatRequest -> Provider Request JSON
3. HttpTransport 执行请求
4. Provider Response JSON -> ChatResponse
5. Provider Error -> LlmException
```

不要在 Provider 模块重新定义一套 `ChatRequest` 或 `ChatResponse`。

### 5.2 customParameters 的边界

`customParameters` 用于快速支持 Provider 新字段，例如：

```java
DefaultChatRequestParameters.builder()
        .customParameter("reasoning_effort", "high")
        .build();
```

但它不应该成为长期绕过 Core 类型系统的方式。如果某能力同时被多个 Provider 稳定支持，并且已经成为 AgentForge 核心能力，应升级成 Core 的强类型字段。

建议判断标准：

```text
仅单一 Provider 使用             -> customParameters
多个 Provider 都出现且语义稳定   -> Core 强类型参数
Agent/Tool 核心流程必须理解       -> Core 强类型模型
```

### 5.3 当前已知边界

release_1.x 的 LLM 基础层已完成“文本 Chat + Function Calling（Blocking / Streaming）+ OpenAI / Anthropic
双协议 + Provider-neutral Core”。以下能力已经落地：

- Function Calling：`AiMessage.toolExecutionRequests()` + `ToolExecutionRequest`；
- Tool 声明：`ToolSpecification` / `ToolParameters`（JSON-Schema 风格，纯 `Map`）/ `ToolChoice`；
- OpenAI / Anthropic 的 tool 请求发送链路与 tool-result wire mapping；
- OpenAI 流式 `tool_calls` delta 按 `index` 聚合；
- `AnthropicStreamingChatModel`（含 `tool_use` `input_json_delta` 聚合）；
- `AiMessage.thinking()` 字段与 `attributes` 扩展位；
- `UserMessage` 的 `name` + `List<Content>` 多模态建模（当前提供 `TextContent`）。

以下能力仍未进入统一协议，属于后续阶段：

- `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` 等多模态 `Content` 实现与 Provider wire mapping；
- 流式过程暴露 `onPartialToolCall(...)` 增量回调（当前仅最终 `ChatResponse` 暴露完整工具调用）；
- Structured Output / JSON Schema 强类型参数绑定与运行时校验；
- `thinking` 的 Provider 级强类型协议（当前只保留字段位）；
- Prompt Cache；
- retry / backoff / rate-limit policy；
- metrics / tracing / request-id 标准化。

### 5.4 兼容性原则

release_1.x 后续扩展遵循：

1. 优先新增接口默认方法或新类型，避免破坏已有 Provider；
2. Core 不暴露供应商 SDK 类型；
3. Provider 特有协议优先留在 Provider module；
4. request/response 对象尽量不可变；
5. 公共 API 保持 Java 8 可编译；
6. 默认推荐用户运行在 JDK 17；
7. Streaming 与 Blocking 最终必须收敛到同一 `ChatResponse` 语义。

这套边界保证后续 AgentForge 从 LLM 层继续向 Tool Calling、Agent Runtime、Memory 和 Workflow 演进时，上层不会被某一家模型协议锁死。

---

## 6. ChatMessage 消息体系设计

### 6.1 本次补齐范围

AgentForge LLM Core 已具备 `SystemMessage`、`UserMessage`、`AiMessage`、`ToolExecutionResultMessage`、
`CustomMessage`，本次继续补齐 Function Calling 与多模态内容建模的 Core 侧结构：

- `ToolExecutionRequest`（模型发起的工具调用）
- `AiMessage` 扩展：`thinking` / `toolExecutionRequests` / `attributes`
- `UserMessage` 改造为 `name` + `List<Content>`
- `Content` / `ContentType` / `TextContent` 多模态内容基座（当前先落地 `TEXT`）
- `ToolSpecification` / `ToolParameters` / `ToolChoice` 请求侧工具声明

设计参考 LangChain4j 当前主线的消息模型，同时保持 AgentForge 现阶段 Java 8 bytecode 兼容与
Provider-neutral 的实现边界。

### 6.2 ToolExecutionResultMessage

`ToolExecutionResultMessage` 表示一次工具执行后的结果，当前字段包括：

- `id`：对应工具调用 ID；
- `toolName`：工具名称；
- `text`：文本结果；
- `isError`：执行是否失败，允许 `null` 表示未知；
- `attributes`：框架内部或 Provider 扩展元数据。

同时提供构造器、`builder()`、`toBuilder()`、`from(...)` 和 `toolExecutionResultMessage(...)` 工厂方法。

Core 的多模态 `Content` 层已在本次起步：新增 `Content` / `ContentType` / `TextContent`，`UserMessage`
已改为 `name + List<Content>`（见 **6.5**）。`ImageContent` / `AudioContent` / `VideoContent` /
`PdfFileContent` 及对应 Provider wire mapping 尚未实现，可在不改变消息类型语义的前提下继续扩展。

### 6.3 CustomMessage

`CustomMessage` 用于承载 Provider 特有、框架无法预定义结构的消息：

```java
Map<String, Object> attributes = new LinkedHashMap<String, Object>();
attributes.put("provider", "custom");
attributes.put("payload", "value");

CustomMessage message = CustomMessage.from(attributes);
```

它没有固定文本语义，只暴露不可变的 `attributes()`。当前 `ChatMessage.text()` 保留为兼容桥接默认方法；对 `CustomMessage` 调用时会明确抛出 `UnsupportedOperationException`。

### 6.4 ChatMessage 兼容性原则

1. 默认开发/运行环境继续推荐 JDK 17；
2. LLM 公共 API 继续保持 Java 8 语法与 bytecode 兼容；
3. 新消息类型不引入第三方依赖；
4. `attributes` 使用防御性复制并以只读 Map 暴露；
5. Provider 若不支持 `CustomMessage`，应显式拒绝，而不是静默降级为普通 user message。

### 6.5 UserMessage 与 Content

`UserMessage` 参考 LangChain4j `public class UserMessage implements ChatMessage { String name; List<Content> contents; }`：

```java
public final class UserMessage implements ChatMessage {
    private final String name;
    private final List<Content> contents;
}
```

- `name` 可为空；不是所有 Provider 都支持用户昵称，Provider 可忽略；
- `contents` 至少一条，构建后不可变；
- `Content` 为多模态内容基接口，`type()` 返回 `ContentType`；当前实现 `TextContent`，
  后续 `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` 可在不改语义的情况下扩展。

保留的历史兼容访问器：

| 方法 | 语义 |
|---|---|
| `text()` | 单一 `TextContent` 时返回文本；否则抛 `UnsupportedOperationException` |
| `hasSingleText()` | 是否只有一条 `TextContent` |
| `singleText()` | 只有一条 `TextContent` 时返回，否则抛异常 |

多种构造入口，覆盖旧的纯文本用法与新的多内容用法：

```java
UserMessage.from("hello");
UserMessage.from("alice", "hello");
UserMessage.from(TextContent.from("describe this"), TextContent.from("..."));
UserMessage.from("alice", Arrays.<Content>asList(TextContent.from("hi")));
```

Provider 侧的映射规则：当 `UserMessage` 只包含一条文本时，直接下发字符串 content；
当包含多条 `Content` 时，映射成 Provider 的内容块数组（OpenAI 的 `content[]`、Anthropic 的 `content[]`）。

---

## 7. Function Calling（工具调用）设计

### 7.1 Core 侧模型

模型返回的工具调用统一收敛到 `AiMessage`，不散落在各 Provider 实现里：

```java
public final class AiMessage implements ChatMessage {
    private final String text;
    private final String thinking;
    private final List<ToolExecutionRequest> toolExecutionRequests;
    private final Map<String, Object> attributes;
}
```

`ToolExecutionRequest` 描述一次调用：`id` / `name` / `arguments`（`arguments` 为原始 JSON 字符串）。

常用 API：

```java
AiMessage.from("hello");                                  // 纯文本
AiMessage.from(new ToolExecutionRequest(...));            // 纯工具调用，text() 为 null
AiMessage.from("thinking out loud", toolRequests);        // 文本 + 工具调用

message.hasToolExecutionRequests();
message.toolExecutionRequests();
```

当 `AiMessage.hasToolExecutionRequests()` 为真时，应用侧应执行这些工具，并在下一轮以
`ToolExecutionResultMessage.from(id, toolName, result)` 回填，`id` 必须与被调用的
`ToolExecutionRequest.id()` 一致，用于关联。

### 7.2 请求侧工具声明

```java
ToolParameters parameters = ToolParameters.builder()
        .addProperty("city", "string", "city name", true)
        .build();

ToolSpecification weather = ToolSpecification.builder()
        .name("getWeather")
        .description("query weather of a city")
        .parameters(parameters)
        .build();

ChatRequest request = ChatRequest.builder()
        .message(UserMessage.from("weather in Hangzhou?"))
        .parameters(DefaultChatRequestParameters.builder()
                .tool(weather)
                .toolChoice(ToolChoice.AUTO)
                .build())
        .build();
```

`ToolChoice` 与两个 Provider wire 字段的映射：

| `ToolChoice` | OpenAI `tool_choice` | Anthropic `tool_choice` |
|---|---|---|
| `AUTO` | `"auto"` | `{"type":"auto"}` |
| `NONE` | `"none"` | `{"type":"none"}` |
| `REQUIRED` | `"required"` | `{"type":"any"}` |
| `SPECIFIC` | `{"type":"function","function":{"name":X}}` | `{"type":"tool","name":X}` |

`ToolParameters` 的 JSON-Schema 在 OpenAI 落到 `tools[].function.parameters`，
在 Anthropic 落到 `tools[].input_schema`。

### 7.3 非流式响应解析

```text
OpenAI assistant message         Anthropic assistant message
  tool_calls[] {id,type,            content[] {type:"tool_use",
    function{name,arguments}}         id,name,input{...}}
        │                                   │
        ▼                                   ▼
   ToolExecutionRequest              ToolExecutionRequest
        └───────────┬───────────────────────┘
                    ▼
        AiMessage.toolExecutionRequests()
        finishReason = TOOL_EXECUTION
```

要点：

- OpenAI `finish_reason=tool_calls`（或旧版 `function_call`）与 Anthropic `stop_reason=tool_use`
  都映射为 `FinishReason.TOOL_EXECUTION`；
- 纯工具调用时 `content` 可能为 `null`，此时 `AiMessage.text()` 返回 `null`；
- Anthropic `tool_use.input` 是对象，会序列化成 JSON 字符串存入 `arguments`，与 OpenAI 对齐。

### 7.4 流式响应聚合

流式与 LangChain4j 保持一致：文本增量走 `onPartialResponse()`，工具调用增量先在内部按索引累加，
最终在 `onCompleteResponse()` 的 `ChatResponse` 上暴露完整 `AiMessage.toolExecutionRequests()`。

OpenAI Chat Completions 的 `delta.tool_calls[]` 按 `index` 分桶，累加 `id` / `name` / `arguments`：

```text
chunk 1: {index:0, id:"call_1", function:{name:"getWeather", arguments:""}}
chunk 2: {index:0, function:{arguments:"{\"city\":"}}
chunk 3: {index:0, function:{arguments:"\"hangzhou\"}"}}
        │
        ▼  merge by index
ToolExecutionRequest(id=call_1, name=getWeather, arguments={"city":"hangzhou"})
```

- 多数 OpenAI 兼容端点带 `index`；当 `index` 缺省时，以“出现新的 `id`”作为新调用起点做兜底分桶；
- 同一个 `index` 的多个 `arguments` 片段按到达顺序拼接，原始 JSON 文本不重新格式化。

Anthropic Messages 的流式工具调用以 `content_block_start`（`tool_use`）开块，
随后多个 `content_block_delta` 的 `input_json_delta.partial_json` 追加参数，按 `index` 归属：

```text
content_block_start (index=1, tool_use, id=toolu_1, name=get_weather)
content_block_delta (index=1, input_json_delta: '{"city":')
content_block_delta (index=1, input_json_delta: '"hangzhou"}')
        │
        ▼  accumulate by content block index
ToolExecutionRequest(id=toolu_1, name=get_weather, arguments={"city":"hangzhou"})
```

`text_delta` 仍然实时走 `onPartialResponse()`；只有当参数块闭合、整个流结束时才产出完整工具调用。

### 7.5 一次完整的多轮工具调用

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(apiKey).modelName("gpt-4o-mini").build();

List<ChatMessage> history = new ArrayList<ChatMessage>();
history.add(UserMessage.from("What is the weather in Hangzhou?"));

ChatResponse first = model.chat(ChatRequest.builder()
        .messages(history)
        .parameters(DefaultChatRequestParameters.builder().tool(weatherTool).build())
        .build());

if (first.aiMessage().hasToolExecutionRequests()) {
    history.add(first.aiMessage());
    for (ToolExecutionRequest call : first.aiMessage().toolExecutionRequests()) {
        String result = myToolExecutor.execute(call.name(), call.arguments());
        history.add(ToolExecutionResultMessage.from(call.id(), call.name(), result));
    }
    ChatResponse second = model.chat(ChatRequest.builder().messages(history).build());
    System.out.println(second.aiMessage().text());   // "It is 22°C and sunny in Hangzhou."
}
```

Anthropic 与 OpenAI 共用同一套 Core 类型；上层 Agent Runtime 只需要面向 `ChatMessage` /
`ToolExecutionRequest` 编程，不需要感知供应商协议差异。

### 7.6 单测覆盖矩阵

| 模块 | 测试类 | 覆盖点 |
|---|---|---|
| `agentforge-llm-core` | `AiMessageToolCallTest` | 单/多工具调用、文本+工具调用、thinking/attributes、值相等与不可变、`UserMessage` name + 多 `Content`、`ToolExecutionRequest` 字段往返 |
| `agentforge-llm-openai` | `OpenAiFunctionCallTest` | 单 `tool_calls`、多 `tool_calls` 与混合文本、`tools`/`tool_choice`（含 SPECIFIC）序列化、assistant tool_calls 与 tool result 回流、纯文本行为不变 |
| `agentforge-llm-openai` | `OpenAiStreamingFunctionCallTest` | 单工具调用 delta 合并、多工具交错合并、文本与工具调用同流、无 `index` 端点兜底 |
| `agentforge-llm-anthropic` | `AnthropicToolUseTest` | `tool_use` 解析、多 `tool_use`、assistant `tool_use` 与 `tool_result` 回流、`tools`/`tool_choice` 序列化、多 `Content` 用户消息、纯文本行为不变 |
| `agentforge-llm-anthropic` | `AnthropicStreamingToolUseTest` | `input_json_delta` 聚合、`text_delta` 与多 `tool_use` 混合流式、metadata / token usage |

### 7.7 兼容性约束

1. Function Calling 不引入任何第三方依赖，`ToolParameters` 以纯 `Map` 承载 JSON-Schema；
2. 所有新增访问器优先使用接口默认方法（`ChatRequestParameters.tools()` 等），既有实现零改动可编译；
3. `AiMessage` / `UserMessage` 的旧构造与 `from(String)` / `text()` 入口保留；
4. 纯工具调用时 `AiMessage.text()` 允许为 `null`，Provider 序列化需按各自协议输出 `content:null`（OpenAI）
   或省略 text 块（Anthropic）；
5. 阻塞与流式最终必须收敛到同一个 `ChatResponse` / `AiMessage` 语义，工具调用只在最终响应上暴露，
   避免上层为了流式额外实现一套工具调用聚合逻辑。

---

## 8. Tool layer（ToolService / ToolExecutor 复刻）

### 8.1 定位

除 Provider 层的 Function Calling 之外，LLM Core 额外沉淀了一套 **Tool 执行层**（对应包
`com.changlu.agentforge.llm.tool`，拆分为顶层 + 三个子包）：

```text
com.changlu.agentforge.llm.tool            // 核心契约：注解 + 工具执行接口
├── Tool                        // @Tool 方法注解
├── P                           // @P 参数注解
├── ReturnBehavior              // TO_LLM / IMMEDIATE / IMMEDIATE_IF_LAST
├── ToolExecutor                // 工具执行函数式接口
│
├── spec                        // 工具规范
│   ├── ToolSpecification
│   ├── ToolParameters
│   └── ToolSpecifications      // @Tool 方法 -> ToolSpecification/ToolParameters
│
├── execution                   // 执行
│   ├── ToolService             // 工具注册 + 推理/执行循环
│   ├── DefaultToolExecutor     // @Tool 方法反射执行器
│   ├── ToolExecution           // 一次工具执行（请求+结果+耗时）
│   ├── ToolExecutionResult     // 执行结果值对象（文本+原始对象+isError）
│   └── ToolExecutionRequestUtil// arguments JSON -> Map
│
└── error                       // 异常与错误处理
    ├── ToolArgumentsException / ToolExecutionException
    ├── ToolArgumentsErrorHandler / ToolExecutionErrorHandler
    ├── ToolErrorContext / ToolErrorHandlerResult
```

它解决的是“上层 Agent 如何把普通 Java 方法快速变成可被 LLM 调用的工具，并自动驱动多轮工具执行”。
核心可复用的字段/方法形态（与 LangChain4j 一致的简单风格）：

```java
// ToolService
private static final ToolExecutionErrorHandler DEFAULT_TOOL_EXECUTION_ERROR_HANDLER =
        (error, context) -> ToolErrorHandlerResult.text(error.getMessage());

private final List<ToolSpecification> toolSpecifications = new ArrayList<>();
private final Map<String, ToolExecutor> toolExecutors = new HashMap<>();

public List<ToolSpecification> toolSpecifications() {
    return this.toolSpecifications;
}
```

### 8.2 快速构建工具

用 `@Tool` 标注一个方法，交给 `ToolService` 注册即可：

```java
public class WeatherTools {

    @Tool(value = "Returns the weather for the given city")
    public String getWeather(@P("city name") String city) {
        return "Weather in " + city + ": 22C sunny";
    }
}
```

```java
ToolService toolService = new ToolService();
toolService.tools(Arrays.asList(new WeatherTools()));   // 扫描全部 @Tool 方法

ToolChatResult result = toolService.chat(model, parameters, messages);
System.out.println(result.finalResponse().aiMessage().text());
```

`ToolSpecifications` 会把方法名/`@P` 参数名/类型自动转成 `ToolSpecification` + `ToolParameters`
（JSON-Schema），与 Provider 一侧的 `tools()`/`toolChoice()` 对接。

### 8.3 ToolExecutor

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(ToolExecutionRequest request, Object memoryId);

    default ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) { ... }
}
```

- `DefaultToolExecutor` 是默认反射实现：把 `request.arguments()`（JSON）绑定到 `@Tool` 方法参数
  （执行轻量类型转换），反射调用方法，并把返回值转成文本：
  - `String` → 原样返回；
  - `void` → 字面量 `"Success"`；
  - 其它 → `Json.stringify(...)`。
- 你也可以提供自定义 `ToolExecutor`（Lambda 即可）直接实现工具逻辑。

### 8.4 推理与工具执行循环

`ToolService.chat(...)` 实现与 LangChain4j 一致的循环：

```text
1. 携带已注册 tools 调用 ChatModel
2. 若 aiMessage.hasToolExecutionRequests()：
     a. 逐个执行工具（找不到 executor -> 幻觉工具策略，默认抛异常）
     b. 把每个 ToolExecutionResult 转成 ToolExecutionResultMessage（id 关联）
     c. 追加到消息列表
     d. 若 ReturnBehavior 要求立即返回 -> 停止
     e. 否则用新消息继续下一轮
3. 若无工具调用 -> 返回最终 ChatResponse + 全部 ToolExecution
```

- 默认 `maxToolCallingRoundTrips = 100`，防止死循环；
- `ReturnBehavior`：`TO_LLM`（默认，结果回给 LLM 继续）、`IMMEDIATE`（执行后立即返回）、
  `IMMEDIATE_IF_LAST`（仅当它是最后一个工具调用时立即返回）；
- 任意工具出错都会强制再跑一轮，让 LLM 看到错误并纠正重试。

### 8.5 错误处理

| 异常 | 触发点 | 默认处理 |
|---|---|---|
| `ToolArgumentsException` | 参数 JSON 无法解析 / 参数类型不符 / 缺必填参数 | 抛异常（`RETHROW`） |
| `ToolExecutionException` | 工具方法执行失败 | 把错误消息以 `ToolErrorHandlerResult.text(...)` 回给 LLM |

可通过 `toolService.argumentsErrorHandler(...)` / `toolService.executionErrorHandler(...)` 定制：
自定义 handler 要么返回 `ToolErrorHandlerResult.text(msg)`（回给 LLM），要么直接抛异常（终止调用）。

### 8.6 与 Function Calling 协议层的关系

```text
ToolService.chat()                     <- 上层 Agent 入口（复用本章 Tool layer）
        │ 调用
ChatModel / ChatRequest / ChatResponse <- 第 2/3 章协议抽象
        │
OpenAI / Anthropic Adapter             <- 第 7 章 Function Calling wire 映射
        │
AiMessage.toolExecutionRequests()      <- 模型给出的工具调用
        │
ToolService 执行 -> ToolExecutionResultMessage 回填
```

本章 Tool layer 与第 7 章 Provider wire 层解耦：无论底层是 OpenAI 还是 Anthropic，
上层 Agent 都只面向 `ChatMessage` / `ToolExecutionRequest` / `ToolExecutor` 编程。
