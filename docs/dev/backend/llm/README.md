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
│   ├── ChatMessage
│   ├── ChatRequest / ChatRequestParameters
│   ├── ChatResponse / TokenUsage / FinishReason
│   ├── HttpTransport / JdkHttpTransport
│   └── LlmException / Json
├── agentforge-llm-openai
│   ├── OpenAiChatModel
│   └── OpenAiStreamingChatModel
└── agentforge-llm-anthropic
    └── AnthropicChatModel
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
├── chat
│   ├── ChatModel.java
│   ├── StreamingChatModel.java
│   ├── message
│   ├── request
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
| `USER` | `UserMessage` | 用户输入 |
| `AI` | `AiMessage` | 模型输出 / 历史助手消息 |
| `TOOL_EXECUTION_RESULT` | `ToolExecutionResultMessage` | 工具执行结果 |
| `CUSTOM` | `CustomMessage` | Provider 特有消息 |

前三类继承 `AbstractTextMessage`；`ToolExecutionResultMessage` 当前也是文本结果，但额外携带 `id / toolName / isError / attributes`；`CustomMessage` 不具备强制文本语义。

详细消息设计见本文 **第 6 章：ChatMessage 消息体系设计**。

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

release_1.x 当前属于 LLM 基础层第一阶段，已实现重点是“文本 Chat + OpenAI Streaming + Provider-neutral Core”。以下能力尚未完全进入统一协议：

- 多模态 `Content` 层；
- Tool Specification / Tool Call 强类型对象；
- Provider tool-result 的完整 wire mapping；
- Structured Output / JSON Schema 强类型参数；
- reasoning/thinking 强类型协议；
- Prompt Cache；
- Anthropic StreamingChatModel；
- retry / backoff / rate-limit policy；
- metrics / tracing / request-id 标准化。

其中 `ToolExecutionResultMessage` 已经提前建立 Core 消息模型，但当前 Provider adapter 尚未完整实现 Tool Calling 发送链路。

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

AgentForge 当前 LLM Core 已具备 `SystemMessage`、`UserMessage`、`AiMessage`，本次继续补齐：

- `ToolExecutionResultMessage`
- `CustomMessage`
- `ChatMessageType.TOOL_EXECUTION_RESULT`
- `ChatMessageType.CUSTOM`

设计参考 LangChain4j 当前主线的消息模型，同时保持 AgentForge 现阶段 Java 8 bytecode 兼容与 text-first 的实现边界。

### 6.2 ToolExecutionResultMessage

`ToolExecutionResultMessage` 表示一次工具执行后的结果，当前字段包括：

- `id`：对应工具调用 ID；
- `toolName`：工具名称；
- `text`：文本结果；
- `isError`：执行是否失败，允许 `null` 表示未知；
- `attributes`：框架内部或 Provider 扩展元数据。

同时提供构造器、`builder()`、`toBuilder()`、`from(...)` 和 `toolExecutionResultMessage(...)` 工厂方法。

当前版本暂不引入 LangChain4j 新版的 `Content` / `TextContent` / `ImageContent` 多模态层，避免在 Tool Calling 正式实现前扩大 LLM Core 的依赖面。后续进入 Multimodal Message 阶段后，可以在不改变消息类型语义的情况下继续扩展。

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
