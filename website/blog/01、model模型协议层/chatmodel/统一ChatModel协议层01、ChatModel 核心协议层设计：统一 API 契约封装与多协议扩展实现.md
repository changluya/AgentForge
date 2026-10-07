---
title: "AgentForge模型协议层 ChatModel 原理01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现"
date: 2026-10-01
tags: [AgentForge, 模型协议层, ChatModel]
---

# AgentForge模型协议层 ChatModel 原理01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现

> 适用版本：release_1.x  
> 适用模块：`agentforge-model`（核心契约：`agentforge-model-api`）  
> 包前缀：`cloud.changlu.agentforge.model`  
> 维护者：长路

{/* truncate */}


系列定位：本文属于「AgentForge 核心模块设计原理文章系列 / 01、model 模型协议层 / chatmodel」，是
ChatModel 类别原理课的第 01 篇。全文只讲两件事：

1. `agentforge-model-api` 如何用一套 **Provider-neutral 的接口** 封装 ChatModel 的统一出入参；
2. OpenAI、Anthropic 等 **不同协议模型如何基于这套标准 ChatModel 协议扩展落地**。

Provider 的底层 wire 协议（字段名、Header、SSE、finish reason）单独放在 `openai/` 与 `anthropic/`
目录，避免 Core 设计与厂商协议混杂。

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前文章（ChatModel 原理01） | `agentforge-model-api` 接口封装、标准 ChatModel 协议、多协议扩展设计 |
| [ChatModel原理02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环.md](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环) | 第 02 篇：`@Tool` 声明、工具规范、反射执行与 `ToolService` 执行循环 |
| [openai/OpenAI协议01、OpenAI底层协议快速理解.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议01、OpenAI底层协议快速理解) | OpenAI Chat Completions 底层协议：请求 / 响应 / 流式 / 工具调用 wire |
| [openai/OpenAI协议02、AgentForge OpenAI接入核心实践.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践) | AgentForge OpenAI Provider 映射、Builder、流式聚合、错误与演进 |
| [anthropic/Anthropic协议01、Anthropic底层协议快速理解.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议01、Anthropic底层协议快速理解) | Anthropic Messages 底层协议：system 顶层字段 / content block / SSE / tool_use |
| [anthropic/Anthropic协议02、AgentForge Anthropic接入核心实践.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践) | AgentForge Anthropic Provider 映射、流式聚合、兼容风险与演进 |

<br/>

## 目录

- [一、背景：为什么需要一层 ChatModel 协议层？](#一背景为什么需要一层-chatmodel-协议层)
- [二、核心概念：一套标准 ChatModel 协议长什么样？](#二核心概念一套标准-chatmodel-协议长什么样)
- [三、实现思路：agentforge-model-api 如何封装统一出入参](#三实现思路agentforge-model-api-如何封装统一出入参)
- [四、多协议扩展：不同协议模型如何基于标准协议扩展](#四多协议扩展不同协议模型如何基于标准协议扩展)
- [五、实战：一次完整的多轮工具调用](#五实战一次完整的多轮工具调用)
- [六、实战用例：真实 curl 与返回结果](#六实战用例真实-curl-与返回结果)
- [七、验证测试](#七验证测试)
- [八、边界、兼容与演进](#八边界兼容与演进)
- [九、总结](#九总结)
- [参考资料](#参考资料)

---

## 一、背景：为什么需要一层 ChatModel 协议层？

### 1.1、从一个具体问题说起

在开发 AgentForge 的 Agent 层时，我们遇到了这样一个具体问题：

> 上层 ReAct Agent 的业务代码里，今天接的是 OpenAI，明天可能就要接 Anthropic，后天还要接一个
> 企业内部网关。怎样保证换模型时，Agent 主循环一行代码都不用改？

如果没有统一抽象，最常见的写法是这样的：

```java
// 不推荐：Agent 里直接 new 供应商 SDK
OpenAiClient client = new OpenAiClient(apiKey);
ChatCompletionResult result = client.chatCompletion(messages, "gpt-4o-mini");
String text = result.getChoices().get(0).getMessage().getContent();
```

这段代码把三样东西牢牢绑死了：**供应商 SDK 类型**、**供应商请求/响应结构**、**供应商字段命名**。
一旦要换 Anthropic Messages（它的 system 是顶层字段、工具叫 `tool_use`、返回是 content block 数组），
Agent 层就得推倒重写。

<br/>

### 1.2、如果直接绑定厂商 SDK 会怎样？

把「不抽象」的代价逐条列出来：

- **上层污染**：Agent / Memory / Tool Calling 充斥 `if (provider == OPENAI)` 之类的分支；
- **测试困难**：每次单测都要真实 API Key、真实网络，CI 跑不起来；
- **扩展昂贵**：新增一家模型 = 改一处上层业务，回归范围不可控；
- **协议差异外泄**：`finish_reason`、`prompt_tokens`、`tool_calls` 这些厂商专属概念渗透到业务代码。

> **重点**：模型协议层的核心价值，不是「多支持几家模型」，而是 **把厂商差异收敛到一个可替换的边界内**。

<br/>

### 1.3、这一层要达成的四个目标

因此 `agentforge-model-api` 的定位是：**AgentForge 最底层、最稳定的模型访问抽象层**。它不直接绑定
OpenAI、Anthropic 或任何第三方 Java SDK，只定义统一的请求、响应、消息、流式回调、HTTP 传输和异常边界。

四个设计目标：

1. **Provider-neutral**：Agent、Memory、Tool Calling 等上层模块只依赖契约层（`agentforge-model-api`）
   与 core，不依赖任何供应商 SDK；
2. **协议适配下沉**：OpenAI / Anthropic 的字段名、Header、SSE、finish reason 等差异全部由 Provider 模块消化；
3. **Java 8 API 兼容**：默认开发环境可用 JDK 17，但公共 LLM API 避免 Java 9+ 语言特性和集合工厂；
4. **为后续 Agent 能力预留扩展点**：消息类型、`customParameters`、`metadata`、`HttpTransport` 都保留扩展空间。

> **注意**：这里的 Java 8 是指 **公共 API 的语法与 bytecode 兼容**，并不代表项目只能在 JDK 8 上构建，
> 内部实现仍推荐运行在 JDK 17。

<br/>

### 1.4、从请求到响应：协议层全景

标准 ChatModel 协议贯穿始终，整条调用链如下：

```text
Agent / Application
       │
       ▼
ChatModel / StreamingChatModel          <- 统一入口：一套接口
       │
       ▼
ChatRequest + ChatMessage + ChatRequestParameters   <- 统一入参
       │
       ▼
Provider Adapter(OpenAI / Anthropic / ...)          <- 只做协议转换
       │
       ▼
HttpTransport                           <- 统一传输 SPI
       │
       ▼
Provider HTTP API  ->  Provider JSON / SSE          <- 厂商差异被隔离在 Provider 内
       │
       ▼
ChatResponse + TokenUsage + FinishReason            <- 统一出参
```

一句话概括：**上面看到的永远是 `ChatRequest` 进、`ChatResponse` 出；厂商协议只在 Provider 模块内部存在。**

---

## 二、核心概念：一套标准 ChatModel 协议长什么样？

在写代码之前，先回答一个问题：**所谓「标准 ChatModel 协议」，到底由哪些部分构成？**

答案可以拆成四层：模块分层、包结构、核心契约、以及 Provider 必须遵守的行为约束。

<br/>

### 2.1、模块分层与依赖方向

```text
agentforge-model
├── agentforge-model-api              # 契约层：接口 + req/vo，零第三方依赖
│   ├── chat.ChatModel / StreamingChatModel
│   ├── chat.message.*                # ChatMessage 体系 + Content 多模态基座
│   ├── chat.request.*                # ChatRequest / ChatRequestParameters / ToolChoice
│   ├── chat.response.*               # ChatResponse / StreamingChatResponseHandler / TokenUsage / FinishReason
│   ├── tool / tool.spec / tool.execution / tool.error
│   ├── http.*                        # HttpRequest / HttpResponse / HttpTransport / StreamingHttpResponseHandler
│   └── exception.ModelException
├── agentforge-model-core             # 无厂商依赖的默认实现与执行引擎
│   ├── chat.request.DefaultChatRequestParameters
│   ├── http.JdkHttpTransport         # 基于 HttpURLConnection
│   ├── internal.json.Json            # 内部 JSON 能力（非公共 API）
│   └── tool.execution.ToolService / DefaultToolExecutor / ToolExecutionRequestUtil
│       tool.spec.ToolSpecifications
├── agentforge-model-openai           # OpenAI Chat Completions 协议实现
│   ├── OpenAiChatModel / OpenAiStreamingChatModel
│   └── OpenAiMessages                # 包内共享 wire helper（非公共 API）
├── agentforge-model-anthropic        # Anthropic Messages 协议实现
│   ├── AnthropicChatModel / AnthropicStreamingChatModel
│   └── AnthropicProtocol             # Blocking / Streaming 共享 wire mapping
└── agentforge-model-registry         # 开箱即用的模型工厂
    ├── LlmFactory / LlmEnum / LlmBasicConfig
    └── models.IModel / BaseModel / OpenAiModel / AnthropicModel
```

依赖方向严格单向、无环：

```text
            agentforge-model-api   （契约，无依赖）
                    ▲
        ┌───────────┼───────────────┐
        │           │               │
  model-core   model-openai   model-anthropic
        ▲           ▲               ▲
        └───────────┴───────────────┘
                    │
            agentforge-model-registry
```

| 模块 | 职责 | 是否含厂商 SDK |
|---|---|---|
| `agentforge-model-api` | 稳定契约：接口、req/vo、消息、Tool 契约、HTTP SPI、异常 | 否 |
| `agentforge-model-core` | 无厂商依赖的默认实现：参数合并、JDK 传输、JSON、Tool 执行引擎 | 否 |
| `agentforge-model-openai` | OpenAI 协议映射 | 否（手写 wire，不引入 SDK） |
| `agentforge-model-anthropic` | Anthropic 协议映射 | 否（手写 wire，不引入 SDK） |
| `agentforge-model-registry` | 按配置装配 Provider，对上层提供工厂 | 否 |

> **重点**：Core 只定义稳定、Provider-neutral 的 LLM 边界；Provider 模块只负责协议转换，绝不把供应商
> SDK 类型泄露到上层。

<br/>

### 2.2、契约层包结构

契约层是本文重点，单独展开：

```text
cloud.changlu.agentforge.model
├── chat
│   ├── ChatModel.java
│   ├── StreamingChatModel.java
│   ├── message
│   │   ├── ChatMessage.java / ChatMessageType.java / AbstractTextMessage.java
│   │   ├── SystemMessage.java / UserMessage.java / AiMessage.java / CustomMessage.java
│   │   ├── ToolExecutionRequest.java / ToolExecutionResultMessage.java
│   │   └── Content.java / ContentType.java / TextContent.java
│   ├── request
│   │   ├── ChatRequest.java
│   │   ├── ChatRequestParameters.java
│   │   └── ToolChoice.java
│   └── response
│       ├── ChatResponse.java / StreamingChatResponseHandler.java
│       ├── TokenUsage.java
│       └── FinishReason.java
├── tool
│   ├── Tool.java / P.java / ReturnBehavior.java / ToolExecutor.java
│   ├── spec
│   │   ├── ToolSpecification.java
│   │   └── ToolParameters.java
│   ├── execution
│   │   ├── ToolExecution.java
│   │   └── ToolExecutionResult.java
│   └── error
│       ├── ToolArgumentsException.java / ToolExecutionException.java
│       ├── ToolArgumentsErrorHandler.java / ToolExecutionErrorHandler.java
│       └── ToolErrorContext.java / ToolErrorHandlerResult.java
├── http
│   ├── HttpRequest.java / HttpResponse.java
│   ├── HttpTransport.java
│   └── StreamingHttpResponseHandler.java
└── exception
    └── ModelException.java
```

`agentforge-model-core` 放置默认实现与执行引擎（**不在 api 中**）：

```text
cloud.changlu.agentforge.model
├── chat.request.DefaultChatRequestParameters   # 参数默认实现 + merge
├── http.JdkHttpTransport                       # HttpURLConnection 传输实现
├── internal.json.Json                          # 内部 JSON（非公共 API）
└── tool
    ├── spec.ToolSpecifications                 # @Tool 方法 -> ToolSpecification
    └── execution.ToolService / DefaultToolExecutor / ToolExecutionRequestUtil
```

> **注意**：`api` 是「契约」，`core` 是「默认实现」。上层依赖 api 拿能力，跑起来时通常引入 core。
> 千万不要把 `JdkHttpTransport`、`DefaultChatRequestParameters`、`Json` 这些实现类当成公共 API。

<br/>

### 2.3、标准协议由哪些核心契约组成？

```text
标准 ChatModel 协议
├── 入口契约：ChatModel / StreamingChatModel
├── 入参契约：ChatRequest + ChatRequestParameters（+ ToolChoice）
├── 消息契约：ChatMessage 体系（System / User / Ai / ToolResult / Custom + Content）
├── 出参契约：ChatResponse + TokenUsage + FinishReason
├── 流式契约：StreamingChatResponseHandler（partial / thinking / complete / error）
├── 传输契约：HttpTransport + StreamingHttpResponseHandler
└── 异常契约：ModelException
```

---

## 三、实现思路：agentforge-model-api 如何封装统一出入参

本章逐层拆解契约层的接口封装与实现。核心思想是：**入参统一收敛为 `ChatRequest`，出参统一收敛为
`ChatResponse`，同步与流式共享同一套消息与响应模型。**

<br/>

### 3.1、ChatModel：同步模型的最低稳定边界

```java
@FunctionalInterface
public interface ChatModel {

    ChatResponse chat(ChatRequest chatRequest);

    default String chat(String userMessage) { ... }
    default ChatResponse chat(ChatMessage... messages) { ... }
    default ChatResponse chat(List<? extends ChatMessage> messages) { ... }
}
```

设计要点：

- Provider **只需要实现一个主入口** `ChatResponse chat(ChatRequest)`；
- 三个便利方法（单条字符串、变长消息、消息列表）都是 `default`，最终都收敛成 `ChatRequest`，
  Provider 不需要重复实现多套入口；
- `@FunctionalInterface` 让简单的模型/测试实现可以直接用 Lambda。

> **重点**：便利方法收敛到 `ChatRequest`，是「一个 Provider 只实现一个方法」的关键。若每个便利方法都
> 要求 Provider 各写一遍，Provider 数量一多就会失控。

<br/>

### 3.2、StreamingChatModel：流式为什么用回调？

```java
public interface StreamingChatModel {

    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);

    default void chat(String userMessage, StreamingChatResponseHandler handler) { ... }
    default void chat(StreamingChatResponseHandler handler, ChatMessage... messages) { ... }
    default void chat(List<? extends ChatMessage> messages, StreamingChatResponseHandler handler) { ... }
}
```

**为什么不用 `Flow`、Reactive Streams 或 Reactor？** 我们来对比几种方案：

| 方案 | 优点 | 弊端说明 |
|---|---|---|
| 回调式（当前选型） | Java 8 兼容、零额外依赖、实现直观 | 需要自己处理回调时序与异常收敛 |
| `java.util.concurrent.Flow` | JDK 9+ 标准 | 公共 API 被迫升级到 JDK 9+，破坏 Java 8 兼容 |
| Reactor / RxJava | 背压、组合算子强大 | 最底层协议层强依赖响应式框架，依赖变重 |
| `CompletableFuture<Stream>` | 简单 | 流式增量语义表达不自然，取消/错误处理繁琐 |

最终选择回调式，理由是：**协议层要轻，响应式框架的复杂度不该下沉到最底层。**

流式生命周期被规范为：

```text
开始请求
   │
   ├── onPartialThinking(partialThinking)  0..N 次   （推理模型的思考增量，可选）
   │
   ├── onPartialResponse(partialText)      0..N 次   （可见文本增量）
   │
   ├── onCompleteResponse(response)        成功时恰好 1 次
   │
   └── onError(error)                      失败时 1 次
```

```java
public interface StreamingChatResponseHandler {

    void onPartialResponse(String partialResponse);

    default void onPartialThinking(String partialThinking) {}

    void onCompleteResponse(ChatResponse completeResponse);

    void onError(Throwable error);
}
```

> **注意**：`onPartialThinking` 提供默认空实现，专门承接 DeepSeek 风格 `reasoning_content`、Anthropic
> `thinking` 块等推理增量。这样新增回调也**不会破坏已有实现**（零改动可编译）。

**问题引导**：流式和非流式的结果模型会不会分裂成两套？

不会。`onCompleteResponse` 返回的是**已经聚合完成的标准 `ChatResponse`**，因此流式与非流式最终复用统一的
响应模型；Provider 负责在流结束前把工具调用增量、token usage、finish reason 全部聚合进 `ChatResponse`。

<br/>

### 3.3、ChatRequest：不可变统一入参

```java
public final class ChatRequest {
    private final List<ChatMessage> messages;
    private final ChatRequestParameters parameters;
}
```

约束：

- `messages` 必须至少包含一条消息，否则构造时抛 `IllegalArgumentException`；
- Builder 支持 `message(单条)` 与 `messages(集合)`；
- 构建后对消息列表做不可变包装（`Collections.unmodifiableList`）；
- `parameters` 允许为空，此时全部使用 Provider Model Builder 的 model-level 默认参数。

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

<br/>

### 3.4、ChatRequestParameters：参数契约与合并规则

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

前六个字段是抽象方法，后三个是**接口默认方法**：

```java
public interface ChatRequestParameters {

    String modelName();
    Double temperature();
    Integer maxTokens();
    Double topP();
    List<String> stopSequences();
    Map<String, Object> customParameters();

    default List<ToolSpecification> tools() { return null; }
    default ToolChoice toolChoice() { return null; }
    default String toolChoiceName() { return null; }
}
```

> **重点**：新增能力优先用**接口默认方法**。这样即便已有第三方实现了 `ChatRequestParameters`，也能零改动
> 继续编译。这是契约持续演进而不破坏兼容的关键手法。

Provider 面对通用参数有三种合法处理方式：

```text
Core 通用参数
   ├── Provider 原生支持 -> 映射成供应商字段
   ├── Provider 不支持   -> 忽略或显式校验失败
   └── Provider 特有能力 -> 通过 customParameters 扩展
```

#### 3.4.1、参数合并规则

`DefaultChatRequestParameters`（core 模块）负责 model-level defaults 与 request-level overrides 合并：

```text
Model Builder defaults
        │
        ▼
DefaultChatRequestParameters.merge(defaults, overrides)
        ▲
        │
Request overrides
```

规则是：**请求级非空值覆盖模型级默认值**，实现为「先 apply defaults，再 apply overrides」，每个字段仅在
非空时写入；`customParameters` 采用 Map 合并，请求级同名 key 覆盖模型级同名 key。

```java
public static DefaultChatRequestParameters merge(
        ChatRequestParameters defaults, ChatRequestParameters overrides) {
    Builder builder = builder();
    apply(builder, defaults);
    apply(builder, overrides);
    return builder.build();
}
```

示例：

```java
// model-level
OpenAiChatModel.builder()
        .modelName("model-a")
        .temperature(0.7)
        .maxTokens(2048)
        .build();

// request-level 只覆盖温度
DefaultChatRequestParameters.builder().temperature(0.1).build();

// 最终: modelName=model-a, maxTokens=2048, temperature=0.1
```

<br/>

### 3.5、ChatResponse / TokenUsage / FinishReason：统一出参

```java
public final class ChatResponse {
    private final AiMessage aiMessage;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;
}
```

| 字段 | 是否必须 | 说明 |
|---|---|---|
| `aiMessage` | 是 | 最终模型文本 / 思考 / 工具调用消息 |
| `tokenUsage` | 否 | 标准化 Token 统计 |
| `finishReason` | 否 | 标准化结束原因 |
| `metadata` | 否 | 保留 Provider 原始但通用层不需要理解的元数据 |

> **重点**：Provider 的原始 JSON 不直接向上泄露，但关键 Provider 信息可以保留到 `metadata` 中。例如
> OpenAI 保存 `id` / `model` / `created`，Anthropic 保存 `id` / `model` / `type`。

TokenUsage 统一三字段：

```java
public final class TokenUsage {
    long inputTokens;
    long outputTokens;
    long totalTokens;
}
```

```text
OpenAI:     prompt_tokens -> inputTokens, completion_tokens -> outputTokens, total_tokens -> totalTokens
Anthropic:  input_tokens  -> inputTokens, output_tokens    -> outputTokens, input + output -> totalTokens
```

FinishReason 统一枚举：

```java
public enum FinishReason { STOP, LENGTH, TOOL_EXECUTION, CONTENT_FILTER, OTHER }
```

```text
OpenAI stop/length/tool_calls/content_filter -> STOP/LENGTH/TOOL_EXECUTION/CONTENT_FILTER
Anthropic end_turn|stop_sequence/max_tokens/tool_use -> STOP/LENGTH/TOOL_EXECUTION
```

<br/>

### 3.6、ChatMessage：消息体系

```java
public interface ChatMessage {

    ChatMessageType type();

    default String text() {
        throw new UnsupportedOperationException(
                "Message type " + type() + " does not expose a text payload");
    }
}
```

消息类型由 `ChatMessageType` 枚举统一登记，并绑定到具体实现类：

| 类型 | 实现类 | 当前语义 |
|---|---|---|
| `SYSTEM` | `SystemMessage` | 系统指令（继承 `AbstractTextMessage`） |
| `USER` | `UserMessage` | 用户输入，可携带 `name` 与多模态 `List<Content>` |
| `AI` | `AiMessage` | 模型输出 / 历史助手消息，可携带 `thinking` 与 `toolExecutionRequests` |
| `TOOL_EXECUTION_RESULT` | `ToolExecutionResultMessage` | 工具执行结果 |
| `CUSTOM` | `CustomMessage` | Provider 特有消息 |

#### 3.6.1、AiMessage

```java
public final class AiMessage implements ChatMessage {
    private final String text;
    private final String thinking;
    private final List<ToolExecutionRequest> toolExecutionRequests;
    private final Map<String, Object> attributes;
}
```

- `text()` 允许为 `null`（纯工具调用时）；
- `thinking()` 保留推理内容字段位；
- `hasToolExecutionRequests()` / `toolExecutionRequests()` 暴露工具调用；
- `attributes()` 提供 Provider 扩展位，防御性复制为只读 Map。

#### 3.6.2、UserMessage 与 Content 多模态基座

```java
public final class UserMessage implements ChatMessage {
    private final String name;
    private final List<Content> contents;
}
```

- `name` 可为空，Provider 可忽略；
- `contents` 至少一条，构建后不可变；
- `Content` 是多模态内容基接口，`type()` 返回 `ContentType`；当前只落地 `TextContent`（`ContentType.TEXT`），
  后续 `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` 可**在不改消息语义**的前提下扩展。

保留的历史兼容访问器：

| 方法 | 语义 |
|---|---|
| `text()` | 单一 `TextContent` 时返回文本；否则抛 `UnsupportedOperationException` |
| `hasSingleText()` | 是否只有一条 `TextContent` |
| `singleText()` | 只有一条 `TextContent` 时返回，否则抛异常 |

```java
UserMessage.from("hello");
UserMessage.from("alice", "hello");
UserMessage.from(TextContent.from("describe this"), TextContent.from("..."));
UserMessage.from("alice", Arrays.<Content>asList(TextContent.from("hi")));
```

#### 3.6.3、工具消息与自定义消息

- `ToolExecutionRequest`：模型发起的一次工具调用，字段 `id` / `name` / `arguments`（原始 JSON 字符串）；
- `ToolExecutionResultMessage`：工具执行结果，字段 `id` / `toolName` / `text` / `isError` / `attributes`，
  `id` 必须与被调用的 `ToolExecutionRequest.id()` 一致，用于关联；
- `CustomMessage`：承载 Provider 特有、框架无法预定义结构的消息，只暴露不可变的 `attributes()`。
  Provider 若不支持应显式拒绝，而不是静默降级为普通 user message。

<br/>

### 3.7、HttpTransport：传输与协议解耦

```java
public interface HttpTransport {

    HttpResponse execute(HttpRequest request) throws IOException;

    default void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
        handler.onError(new UnsupportedOperationException(
                "Streaming HTTP is not supported by " + getClass().getName()));
    }
}
```

Provider 只依赖 `HttpTransport`，因此可以：

- 默认使用 `JdkHttpTransport`（基于 `HttpURLConnection`）；
- 测试时注入 Fake Transport（这是不依赖真实 API Key 的关键）；
- 后续适配 OkHttp、Apache HttpClient 或企业内部网关；
- 完全不修改 `ChatModel` API。

底层流处理接口只认识 HTTP 行，**不解析 SSE**：

```java
public interface StreamingHttpResponseHandler {
    void onOpen(int statusCode, Map<String, List<String>> headers);
    void onLine(String line);
    void onComplete();
    void onError(Throwable error);
}
```

```text
HTTP line / SSE event
        │
        ▼
Provider Stream Parser     <- 厂商差异在这里消化
        │
        ▼
partial text / thinking / ChatResponse
```

> **重点**：SSE 是供应商协议的一部分，必须由 OpenAI / Anthropic Provider 自己解析。因此未来 Anthropic
> SSE 即使事件格式与 OpenAI 完全不同，也不需要修改 Core。

<br/>

### 3.8、ModelException 与 internal.json.Json

```java
public class ModelException extends RuntimeException {
    private final Integer statusCode;
    private final String responseBody;
}
```

它既可以表达 HTTP 4xx / 5xx，也可以表达网络 IOException、JSON 解析失败、Provider 响应结构不符合预期。
HTTP 非 2xx 时保留 `message` / `statusCode` / `responseBody`，便于后续统一做 retry、rate-limit、日志和可观测性。

JSON 能力放在 `internal` 包中，意味着它**不是 AgentForge 对外公共 API**：

```text
Java Map/List -> JSON String
JSON String    -> Map/List
```

> **注意**：未来即使内部替换 JSON 实现，也不应影响 `ChatModel`、`ChatRequest`、`ChatResponse` 等公共契约。

---

## 四、多协议扩展：不同协议模型如何基于标准协议扩展

这是本文的核心。标准协议定好之后，OpenAI、Anthropic 以及未来任意协议，如何基于它扩展？

<br/>

### 4.1、扩展铁律

1. **只新增 Provider 模块**，不修改 `agentforge-model-api` 的既有契约；
2. **只实现 `ChatModel` / `StreamingChatModel`**，不重新定义请求/响应类型；
3. **厂商 wire 细节必须留在 Provider 模块内部**（包私有 helper）；
4. 需要新增通用能力时，优先给接口加**默认方法**；
5. 厂商私有字段走 `customParameters` / `metadata` / `attributes`，不污染 Core。

<br/>

### 4.2、Provider 模块骨架

```text
agentforge-model-xxx
├── pom.xml                       # 依赖 agentforge-model-api（+ core）
└── src/main/java/.../xxx
    ├── XxxChatModel              implements ChatModel
    ├── XxxStreamingChatModel     implements StreamingChatModel
    └── XxxProtocol / XxxMessages # 包私有 wire helper（Blocking/Streaming 共享）
```

现有模块的 pom 依赖：

```text
agentforge-model-openai     -> agentforge-model-api + agentforge-model-core
agentforge-model-anthropic  -> agentforge-model-api + agentforge-model-core
```

<br/>

### 4.3、实战代码：五步实现一个 Provider

无论什么协议，实现过程固定为五步：

```text
1. merge 默认参数和请求参数
2. ChatRequest -> Provider Request JSON
3. HttpTransport 执行请求
4. Provider Response JSON -> ChatResponse
5. Provider Error -> ModelException
```

以 `OpenAiChatModel` 为骨架：

```java
public final class OpenAiChatModel implements ChatModel {

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // 1. merge
        DefaultChatRequestParameters parameters =
                DefaultChatRequestParameters.merge(defaultParameters, chatRequest.parameters());
        requireModelName(parameters.modelName());

        // 2. ChatRequest -> Provider Request JSON
        Map<String, Object> payload = buildPayload(chatRequest, parameters);
        HttpRequest request = HttpRequest.builder()
                .url(baseUrl + "/chat/completions")
                .header("Content-Type", "application/json")
                .body(Json.stringify(payload))
                .build();

        try {
            // 3. execute
            HttpResponse response = httpTransport.execute(request);
            if (!response.isSuccessful()) {
                throw new ModelException("OpenAI request failed with HTTP "
                        + response.statusCode(), response.statusCode(), response.body());
            }
            // 4. Provider Response JSON -> ChatResponse
            return parseResponse(response.body());
        } catch (IOException e) {
            // 5. Provider Error -> ModelException
            throw new ModelException("OpenAI request failed", e);
        }
    }
}
```

Provider Builder 负责持有：`baseUrl` / `apiKey` / `anthropicVersion` / `modelName` / 采样参数 /
`customHeaders` / `httpTransport` / 超时，并在构造时把这些参数收进 model-level 的
`DefaultChatRequestParameters`。

<br/>

### 4.4、请求侧映射对比

**问题引导**：OpenAI 和 Anthropic 差异到底有多大？看这张对照表就清楚了。

| Core | OpenAI (Chat Completions) | Anthropic (Messages) |
|---|---|---|
| `SystemMessage` | `messages[].role=system` | 顶层 `system` 字段（多个 system 用空行拼接） |
| `UserMessage`（单文本） | `messages[].role=user, content=String` | `messages[].role=user, content=String` |
| `UserMessage`（多 Content） | `content=[{type:text,text}]` | `content=[{type:text,text}]` |
| `AiMessage`（含工具调用） | `role=assistant` + `tool_calls[]` | `role=assistant` + `content=[{type:tool_use,...}]` |
| `ToolExecutionResultMessage` | `role=tool` + `tool_call_id` | `role=user` + `content=[{type:tool_result,...}]` |
| `customParameters` | 直接铺到 payload 顶层 | 直接铺到 payload 顶层 |
| `tools` | `tools[].function.parameters` | `tools[].input_schema` |
| `toolChoice` | `"auto"/"none"/"required"/{type:function,...}` | `{type:auto/none/any/tool,...}` |
| `temperature/maxTokens/topP/stop` | `temperature/max_tokens/top_p/stop` | `temperature/max_tokens/top_p/stop_sequences` |

协议差异点，正是扩展设计要隔离的地方：

- **system 位置不同**：OpenAI 放在消息数组里，Anthropic 提为顶层字段；
- **tool result 角色不同**：OpenAI 是 `role=tool`，Anthropic 是 `role=user` 下的 `tool_result` 块；
- **tool_use 入参不同**：OpenAI `arguments` 是 JSON 字符串，Anthropic `input` 是对象，需要序列化对齐。

<br/>

### 4.5、响应侧映射对比

```text
OpenAI assistant message                 Anthropic assistant message
  tool_calls[] {id,type,                   content[] {type:"tool_use",
    function{name,arguments}}                id,name,input{...}}
        │                                        │
        ▼                                        ▼
   ToolExecutionRequest                     ToolExecutionRequest
        └──────────────┬─────────────────────────┘
                       ▼
            AiMessage.toolExecutionRequests()
```

要点：

- OpenAI `finish_reason=tool_calls`（旧版 `function_call`）与 Anthropic `stop_reason=tool_use`
  统一映射为 `FinishReason.TOOL_EXECUTION`；
- 纯工具调用时 `content` 可能为 `null`，此时 `AiMessage.text()` 返回 `null`；
- Anthropic `tool_use.input` 是对象，序列化成 JSON 字符串存入 `arguments`，与 OpenAI 对齐；
- 原始 `id` / `model` / `created` / `type` 进 `ChatResponse.metadata`。

<br/>

### 4.6、流式聚合

```text
HttpTransport.executeStreaming(request, state)
        │  onOpen / onLine / onComplete / onError
        ▼
Provider Stream Parser（实现 StreamingHttpResponseHandler）
        │
        ├── 文本增量 -> handler.onPartialResponse(...)
        ├── 思考增量 -> handler.onPartialThinking(...)
        └── 工具增量 -> 内部按 index 聚合
        │
        ▼ onCompleteResponse(聚合后的 ChatResponse)
```

**文本与思考实时回调，工具调用只在最终响应暴露**，与 LangChain4j 保持一致。

OpenAI `delta.tool_calls[]` 按 `index` 分桶：

```text
chunk 1: {index:0, id:"call_1", function:{name:"getWeather", arguments:""}}
chunk 2: {index:0, function:{arguments:"{\"city\":"}}
chunk 3: {index:0, function:{arguments:"\"hangzhou\"}"}}
        │
        ▼  merge by index
ToolExecutionRequest(id=call_1, name=getWeather, arguments={"city":"hangzhou"})
```

- 多数 OpenAI 兼容端点带 `index`；当 `index` 缺省时，以「出现新的 `id`」作为新调用起点做兜底分桶；
- 同一 `index` 的多个 `arguments` 片段按到达顺序拼接，原始 JSON 文本不重新格式化。

Anthropic 以 `content_block_start`（`tool_use`）开块：

```text
content_block_start (index=1, tool_use, id=toolu_1, name=get_weather)
content_block_delta (index=1, input_json_delta: '{"city":')
content_block_delta (index=1, input_json_delta: '"hangzhou"}')
        │
        ▼  accumulate by content block index
ToolExecutionRequest(id=toolu_1, name=get_weather, arguments={"city":"hangzhou"})
```

<br/>

### 4.7、共享 wire helper

每个 Provider 建议抽出一个**包私有**的 wire helper，供 Blocking / Streaming 复用：

```text
OpenAiMessages（package-private, final）
├── serialize(messages)          ChatMessage -> OpenAI messages[]
├── serializeTools(tools)        ToolSpecification -> tools[]
├── toolChoice(parameters)       ToolChoice -> tool_choice
├── parseToolCalls(toolCalls)    tool_calls[] -> ToolExecutionRequest
└── extractContent(content)      String | content[] -> text

AnthropicProtocol（package-private, final）
├── collectSystemMessages(messages)  system 顶层字段
├── serializeMessages(messages)      messages[]
├── serializeTools(tools)            input_schema
├── extractToolUses(contentBlocks)   tool_use -> ToolExecutionRequest
└── extractText(contentBlocks)       text block -> text
```

> **重点**：它是 Provider 的实现细节，**不是公共 API**，因此不会污染 `agentforge-model-api`。

<br/>

### 4.8、模型注册与工厂

**问题引导**：上层要接模型时，难道还要 new 具体的 `OpenAiChatModel` 吗？

不用。`agentforge-model-registry` 在 Provider 之上提供开箱即用的装配能力：

```text
LlmBasicConfig（provider / url / modelName / apiKey / props）
        │
        ▼
LlmFactory.buildChatModel(config) / buildStreamChatModel(config)
        │  LlmEnum.of(providerCode) -> IModel
        ▼
OpenAiModel / AnthropicModel  实现 IModel
        │
        ▼
OpenAiChatModel / AnthropicChatModel  (底层仍是标准 ChatModel)
```

```java
public interface IModel {
    ChatModel buildChatModel(LlmBasicConfig llmBasicConfig);
    StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig);
}
```

`LlmEnum` 登记「providerCode -> IModel 实现类」，`LlmFactory` 反射实例化并委托构建，例如
`OPENAI(1, "OpenAI", OpenAiModel.class)`、`ANTHROPIC(2, "Anthropic", AnthropicModel.class)`。

> **注意**：`IModel` 注释已预留：后续 `EmbeddingModel` 等**其他模型类别**也在这里继续扩展。这意味着
> 「标准协议 + Provider 扩展」的模式会沿着模型类别继续复制。

<br/>

### 4.9、新增 Provider 检查清单

```text
[ ] 新建 agentforge-model-xxx 模块，依赖 api（+ core）
[ ] XxxChatModel implements ChatModel
[ ] XxxStreamingChatModel implements StreamingChatModel
[ ] XxxProtocol / XxxMessages 包私有 wire helper（Blocking/Streaming 共享）
[ ] 五步实现：merge -> request -> transport -> response -> exception
[ ] 映射 FinishReason / TokenUsage / metadata / tool 调用
[ ] 把 model 参数收进 DefaultChatRequestParameters 默认值
[ ] 补充单测：纯文本 / 工具调用 / 流式聚合 / 错误 / 参数合并
[ ] 在 agentforge-model-registry 增加 IModel 实现并登记 LlmEnum
```

---

## 五、实战：一次完整的多轮工具调用

### 5.1、为什么还需要一层 Tool layer？

**问题引导**：Provider 已经能解析 `tool_calls` 了，为什么 Model API 里还有一套 `ToolService`？

因为两者解决的是不同问题：

- Provider 层解决「**模型协议里如何表达工具调用**」（wire 映射）；
- Tool layer 解决「**上层 Agent 如何把普通 Java 方法变成可被 LLM 调用的工具，并自动驱动多轮执行**」。

它对应包 `cloud.changlu.agentforge.model.tool`，拆分为顶层 + 三个子包：

```text
cloud.changlu.agentforge.model.tool
├── Tool / P / ReturnBehavior / ToolExecutor          # 核心契约
├── spec
│   ├── ToolSpecification                             # name/description/parameters/strict
│   ├── ToolParameters                               # JSON-Schema 风格（纯 Map，无第三方依赖）
│   └── ToolSpecifications（core）                    # @Tool 方法 -> ToolSpecification
├── execution
│   ├── ToolExecution / ToolExecutionResult（api）    # 一次执行（请求+结果+耗时）/ 值对象
│   ├── ToolService / DefaultToolExecutor（core）     # 注册 + 推理/执行循环 / 反射执行器
│   └── ToolExecutionRequestUtil（core）              # arguments JSON -> Map
└── error
    ├── ToolArgumentsException / ToolExecutionException
    └── ToolArgumentsErrorHandler / ToolExecutionErrorHandler
        ToolErrorContext / ToolErrorHandlerResult
```

<br/>

### 5.2、快速构建工具

用 `@Tool` 标注方法，交给 `ToolService` 注册：

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

`ToolSpecifications` 把方法名 / `@P` 参数名 / 类型自动转成 `ToolSpecification` + `ToolParameters`，
与 Provider 一侧的 `tools()` / `toolChoice()` 天然对接。

<br/>

### 5.3、ToolExecutor 与执行循环

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(ToolExecutionRequest request, Object memoryId);
    default ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) { ... }
}
```

- `DefaultToolExecutor`（core）是默认反射实现：把 `arguments()` JSON 绑定到 `@Tool` 方法参数并调用；
  - `String` -> 原样返回；
  - `void` -> 字面量 `"Success"`；
  - 其它 -> `Json.stringify(...)`。
- 也可用 Lambda 提供自定义 `ToolExecutor`。

`ToolService.chat(...)` 的循环：

```text
1. 携带已注册 tools 调用 ChatModel
2. 若 aiMessage.hasToolExecutionRequests()：
     a. 逐个执行工具（找不到 executor -> 默认抛异常）
     b. 把每个 ToolExecutionResult 转成 ToolExecutionResultMessage（id 关联）
     c. 追加到消息列表
     d. 若 ReturnBehavior 要求立即返回 -> 停止
     e. 否则用新消息继续下一轮
3. 若无工具调用 -> 返回最终 ChatResponse + 全部 ToolExecution
```

- 默认 `maxToolCallingRoundTrips = 100`，防止死循环；
- `ReturnBehavior`：`TO_LLM`（默认）、`IMMEDIATE`、`IMMEDIATE_IF_LAST`；
- 任意工具出错都会强制再跑一轮，让 LLM 看到错误并纠正重试。

<br/>

### 5.4、错误处理

| 异常 | 触发点 | 默认处理 |
|---|---|---|
| `ToolArgumentsException` | 参数 JSON 无法解析 / 类型不符 / 缺必填参数 | 抛异常（`RETHROW`） |
| `ToolExecutionException` | 工具方法执行失败 | 以 `ToolErrorHandlerResult.text(...)` 回给 LLM |

可通过 `argumentsErrorHandler(...)` / `executionErrorHandler(...)` 定制：要么返回
`ToolErrorHandlerResult.text(msg)`（回给 LLM），要么直接抛异常（终止调用）。

<br/>

### 5.5、与 Function Calling 协议层的关系

```text
ToolService.chat()                     <- 上层 Agent 入口（复用本章 Tool layer）
        │
ChatModel / ChatRequest / ChatResponse <- 第三/四章标准协议
        │
OpenAI / Anthropic Adapter             <- 第四章多协议扩展
        │
AiMessage.toolExecutionRequests()      <- 模型给出的工具调用
        │
ToolService 执行 -> ToolExecutionResultMessage 回填
```

---

## 六、实战用例：真实 curl 与返回结果

本章把最常见的调用形态——**非流式 / 流式** 与 **纯文本 / Function Calling / 思考过程**——用真实 curl 与
返回结果完整走一遍，方便对照第三章的 `ChatRequest` / `ChatResponse` 映射与第四章的协议转换。其中
OpenAI 侧额外覆盖「工具调用」与「推理模型思考过程」两类返回。

> 说明：下方返回值为 **真实协议结构**（`id`、时间戳、token 数会随实际调用变化）；仓库中提供了可直接运行的
> `openai/verify-openai-chat.sh` 与 `anthropic/verify-anthropic-chat.sh` 用于真实环境验证。

<br/>

### 6.1、OpenAI

本节覆盖 4 类 OpenAI 返回：非流式纯文本、非流式 Function Calling、流式思考过程、流式 Function Calling。

#### 6.1.1、非流式（纯文本）

curl：

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "用一句话解释什么是 CompletableFuture。"}
    ],
    "temperature": 0.7,
    "max_tokens": 256
  }'
```

返回（节选）：

```json
{
  "id": "chatcmpl-9xYzAbC123",
  "object": "chat.completion",
  "created": 1726200000,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "CompletableFuture 是 Java 8 引入的异步编程工具，用来表示一个尚未完成、但未来会给出结果的计算。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 28, "completion_tokens": 38, "total_tokens": 66 }
}
```

AgentForge 侧映射：

```text
choices[0].message.content -> AiMessage.text()
choices[0].finish_reason   -> FinishReason.STOP
usage.prompt_tokens        -> TokenUsage.inputTokens  = 28
usage.completion_tokens    -> TokenUsage.outputTokens = 38
usage.total_tokens         -> TokenUsage.totalTokens  = 66
id / model / created       -> ChatResponse.metadata
```

对应调用：

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .temperature(0.7)
        .maxTokens(256)
        .build();

ChatResponse response = model.chat(ChatRequest.builder()
        .message(SystemMessage.from("You are a helpful assistant."))
        .message(UserMessage.from("用一句话解释什么是 CompletableFuture。"))
        .build());

System.out.println(response.aiMessage().text());
System.out.println(response.tokenUsage().totalTokens());   // 66
System.out.println(response.finishReason());                // STOP
```

<br/>

#### 6.1.2、非流式（Function Calling）

curl（携带 `tools` 与 `tool_choice`）：

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "杭州今天天气怎么样？"}
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_weather",
          "description": "查询指定城市的当前天气",
          "parameters": {
            "type": "object",
            "properties": { "city": { "type": "string", "description": "城市名" } },
            "required": ["city"]
          }
        }
      }
    ],
    "tool_choice": "auto"
  }'
```

返回（模型决定调用工具，`content` 为 `null`）：

```json
{
  "id": "chatcmpl-9xYzAbC456",
  "object": "chat.completion",
  "created": 1726200100,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\":\"杭州\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 62, "completion_tokens": 18, "total_tokens": 80 }
}
```

AgentForge 侧映射：

```text
message.tool_calls[].id                 -> ToolExecutionRequest.id        = "call_abc123"
message.tool_calls[].function.name      -> ToolExecutionRequest.name      = "get_weather"
message.tool_calls[].function.arguments -> ToolExecutionRequest.arguments = {"city":"杭州"}
message.content = null                  -> AiMessage.text()               = null
finish_reason=tool_calls                -> FinishReason.TOOL_EXECUTION
usage.*                                 -> TokenUsage
```

对应调用与「工具结果回填」：

```java
ToolSpecification weather = ToolSpecification.builder()
        .name("get_weather")
        .description("查询指定城市的当前天气")
        .parameters(ToolParameters.builder()
                .addProperty("city", "string", "城市名", true)
                .build())
        .build();

ChatResponse first = model.chat(ChatRequest.builder()
        .message(UserMessage.from("杭州今天天气怎么样？"))
        .parameters(DefaultChatRequestParameters.builder()
                .tool(weather)
                .toolChoice(ToolChoice.AUTO)
                .build())
        .build());

// 模型返回工具调用：AiMessage.text() == null，finishReason = TOOL_EXECUTION
ToolExecutionRequest call = first.aiMessage().toolExecutionRequests().get(0);
System.out.println(call.name());        // get_weather
System.out.println(call.arguments());   // {"city":"杭州"}

// 执行工具后，把结果以 ToolExecutionResultMessage（id 关联）回填，再发起第二轮
ChatResponse second = model.chat(ChatRequest.builder()
        .message(UserMessage.from("杭州今天天气怎么样？"))
        .message(first.aiMessage())
        .message(ToolExecutionResultMessage.from(call.id(), call.name(), "杭州今天 22℃，天气晴。"))
        .build());

System.out.println(second.aiMessage().text());   // "杭州今天 22℃，天气晴。"
System.out.println(second.finishReason());       // STOP
```

> **重点**：工具调用请求与工具执行结果通过 `id` 关联；`AiMessage` 同时容纳文本与 `toolExecutionRequests()`，
> 因此「文本 + 工具调用」的混合输出也能表达。

<br/>

#### 6.1.3、流式（纯文本 + 思考过程）

以 OpenAI-compatible 推理模型为例（如 DeepSeek 系列，思考增量放在 `reasoning_content`）：

curl（`-N` 关闭缓冲，持续输出 SSE）：

```bash
curl -N https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-reasoner",
    "stream": true,
    "stream_options": { "include_usage": true },
    "messages": [
      {"role": "user", "content": "用一句话解释什么是 CompletableFuture。"}
    ]
  }'
```

返回（`data:` 帧，先 `reasoning_content` 后 `content`）：

```text
data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"用户想要一句话解释","content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"reasoning_content":"CompletableFuture，我需要给出准确又简洁的定义。","content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":"CompletableFuture"},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":" 是 Java 8 引入的异步编程工具。"},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[],"usage":{"prompt_tokens":14,"completion_tokens":52,"total_tokens":66}}

data: [DONE]
```

AgentForge 侧回调序列：

```text
onPartialThinking("用户想要一句话解释")
onPartialThinking("CompletableFuture，我需要给出准确又简洁的定义。")
onPartialResponse("CompletableFuture")
onPartialResponse(" 是 Java 8 引入的异步编程工具。")
onCompleteResponse(ChatResponse{ text="CompletableFuture 是 Java 8 引入的异步编程工具。",
                                thinking="用户想要一句话解释CompletableFuture，我需要给出准确又简洁的定义。",
                                finishReason=STOP, tokenUsage=(14,52,66) })
```

对应调用：

```java
StreamingChatModel model = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("deepseek-reasoner")
        .build();

model.chat("用一句话解释什么是 CompletableFuture。", new StreamingChatResponseHandler() {

    @Override
    public void onPartialThinking(String partialThinking) {
        System.out.print("[思考] " + partialThinking);   // 思考增量，可选展示
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        System.out.print(partialResponse);               // 正式回答，逐字输出
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        System.out.println();
        System.out.println("thinking=" + completeResponse.aiMessage().thinking());
        System.out.println("done=" + completeResponse.finishReason());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

> 注意：`[DONE]` 帧只表示 SSE 结束；真正的聚合结果在最后一个带 `usage` 的 chunk 之后由
> `onCompleteResponse` 给出。`stream_options.include_usage=true` 是拿到 `usage` 的前提。

> **重点**：`reasoning_content`（部分实现用 `thinking`）会被识别为思考增量，实时走 `onPartialThinking`，
> 最终聚合进 `AiMessage.thinking()`；可见回答走 `onPartialResponse`，两条通道互不干扰。

<br/>

#### 6.1.4、流式（Function Calling 增量聚合）

curl（`stream: true` + `tools`）：

```bash
curl -N https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "stream": true,
    "stream_options": { "include_usage": true },
    "messages": [
      {"role": "user", "content": "杭州今天天气怎么样？"}
    ],
    "tools": [
      {
        "type": "function",
        "function": {
          "name": "get_weather",
          "description": "查询指定城市的当前天气",
          "parameters": {
            "type": "object",
            "properties": { "city": { "type": "string", "description": "城市名" } },
            "required": ["city"]
          }
        }
      }
    ],
    "tool_choice": "auto"
  }'
```

返回（`delta.tool_calls[]` 按 `index` 分片到达）：

```text
data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc123","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"杭州\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[],"usage":{"prompt_tokens":62,"completion_tokens":18,"total_tokens":80}}

data: [DONE]
```

AgentForge 侧聚合结果：

```text
按 index=0 逐片拼接 arguments：
  id        = "call_abc123"
  name      = "get_weather"
  arguments = "{\"city\":" + "\"杭州\"}" = {"city":"杭州"}
        │
        ▼ onCompleteResponse
ChatResponse{ text=null,
              toolExecutionRequests=[ToolExecutionRequest(id=call_abc123, name=get_weather, arguments={"city":"杭州"})],
              finishReason=TOOL_EXECUTION }
```

> **注意**：流式下工具调用不会通过 `onPartialResponse` 暴露；它只在 `onCompleteResponse` 的
> `AiMessage.toolExecutionRequests()` 上一次性给出。多数 OpenAI 兼容端点带 `index`，缺省时以新出现的
> `id` 作为新调用起点兜底分桶。

**补充：思考 + 工具调用同流**。OpenAI-compatible 推理模型在带 `tools` 时，可能先流式输出
`reasoning_content`，再输出 `tool_calls`。两条通道先后触发，最终仍聚合为同一个 `ChatResponse`：

```text
data: {...,"delta":{"reasoning_content":"用户问的是杭州天气，需要调用 get_weather。","content":null}...}
data: {...,"delta":{"reasoning_content":"参数 city=杭州。","content":null}...}
data: {...,"delta":{"tool_calls":[{"index":0,"id":"call_abc123","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"杭州\"}"}}]}...}
data: {...,"delta":{},"finish_reason":"tool_calls"}
data: [DONE]
        │
        ▼ onCompleteResponse
ChatResponse{ thinking="用户问的是杭州天气，需要调用 get_weather。参数 city=杭州。",
              toolExecutionRequests=[ToolExecutionRequest(id=call_abc123, name=get_weather, arguments={"city":"杭州"})],
              finishReason=TOOL_EXECUTION }
```

<br/>

### 6.2、Anthropic

#### 6.2.1、非流式

curl：

```bash
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-latest",
    "max_tokens": 256,
    "system": "You are a helpful assistant.",
    "messages": [
      {"role": "user", "content": "用一句话解释什么是 CompletableFuture。"}
    ]
  }'
```

返回（节选）：

```json
{
  "id": "msg_01AbCdEfGh",
  "type": "message",
  "role": "assistant",
  "model": "claude-3-5-sonnet-latest",
  "content": [
    {
      "type": "text",
      "text": "CompletableFuture 是 Java 8 引入的异步编程工具，用来表示一个尚未完成、但未来会给出结果的计算。"
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": { "input_tokens": 20, "output_tokens": 42 }
}
```

AgentForge 侧映射：

```text
content[].type == "text"  -> 拼接成 AiMessage.text()
stop_reason=end_turn      -> FinishReason.STOP
usage.input_tokens        -> TokenUsage.inputTokens  = 20
usage.output_tokens       -> TokenUsage.outputTokens = 42
input + output            -> TokenUsage.totalTokens  = 62
id / model / type         -> ChatResponse.metadata
```

对应调用：

```java
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-3-5-sonnet-latest")
        .maxTokens(256)
        .build();

ChatResponse response = model.chat(ChatRequest.builder()
        .message(SystemMessage.from("You are a helpful assistant."))
        .message(UserMessage.from("用一句话解释什么是 CompletableFuture。"))
        .build());

System.out.println(response.aiMessage().text());
System.out.println(response.tokenUsage().totalTokens());   // 62
```

> **重点**：`system` 在 OpenAI 里是 `messages[].role=system`，在 Anthropic 里是**顶层 `system` 字段**，
> 但 AgentForge 上层始终使用同一个 `SystemMessage`。

<br/>

#### 6.2.2、流式

curl：

```bash
curl -N https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-latest",
    "max_tokens": 256,
    "stream": true,
    "messages": [
      {"role": "user", "content": "用一句话解释什么是 CompletableFuture。"}
    ]
  }'
```

返回（`event:` + `data:` 帧）：

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_01AbCdEfGh","type":"message","role":"assistant","model":"claude-3-5-sonnet-latest","content":[],"stop_reason":null,"usage":{"input_tokens":20,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"CompletableFuture"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" 是 Java 8 引入的异步编程工具。"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":24}}

event: message_stop
data: {"type":"message_stop"}
```

AgentForge 侧回调序列：

```text
onPartialResponse("CompletableFuture")
onPartialResponse(" 是 Java 8 引入的异步编程工具。")
onCompleteResponse(ChatResponse{ text="CompletableFuture 是 Java 8 引入的异步编程工具。",
                                finishReason=STOP, tokenUsage=(20,24) })
```

对应调用与 6.1.2 相同，只是把模型换成 `AnthropicStreamingChatModel`：

```java
StreamingChatModel model = AnthropicStreamingChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-3-5-sonnet-latest")
        .maxTokens(256)
        .build();

model.chat("用一句话解释什么是 CompletableFuture。", handler);
```

> **弊端说明**：Anthropic 流式的 `usage` 分两处给出——`message_start` 给 `input_tokens`，
> `message_delta` 给累计的 `output_tokens`，Provider 需要把两处合并后再写入 `TokenUsage`。

<br/>

### 6.3、一键真实验证脚本

仓库中已提供两份可直接运行的脚本：

```text
openai/verify-openai-chat.sh
anthropic/verify-anthropic-chat.sh
```

以 OpenAI 为例：

```bash
export OPENAI_API_KEY=sk-...
bash openai/verify-openai-chat.sh
```

脚本会依次执行「非流式请求 -> jq 解析 `tool_calls` -> 流式请求观察 `data:` 帧」；Anthropic 脚本同理。
真实返回同样遵循本章的映射规则。

---

## 七、验证测试

### 7.1、测试矩阵

| 模块 | 测试类 | 覆盖点 |
|---|---|---|
| `agentforge-model-api` | `AiMessageToolCallTest` | 单/多工具调用、文本+工具调用、thinking/attributes、值相等与不可变、`UserMessage` name + 多 `Content`、`ToolExecutionRequest` 字段往返 |
| `agentforge-model-api` | `ChatModelTest` / `StreamingChatModelTest` | 便利方法收敛、流式生命周期 |
| `agentforge-model-core` | `DefaultChatRequestParametersTest` | 默认值 / merge 覆盖 / customParameters Map 合并 |
| `agentforge-model-core` | `ToolServiceTest` / `DefaultToolExecutorTest` | 推理-执行循环、参数绑定、错误处理 |
| `agentforge-model-openai` | `OpenAiFunctionCallTest` | 单/多 `tool_calls`、混合文本、`tools`/`tool_choice`（含 SPECIFIC）序列化、tool result 回流 |
| `agentforge-model-openai` | `OpenAiStreamingFunctionCallTest` | 单工具调用 delta 合并、多工具交错、文本+工具同流、无 `index` 兜底 |
| `agentforge-model-anthropic` | `AnthropicToolUseTest` | `tool_use` 解析、多 `tool_use`、`tool_result` 回流、`input_schema` 序列化、多 `Content` 用户消息 |
| `agentforge-model-anthropic` | `AnthropicStreamingToolUseTest` | `input_json_delta` 聚合、`text_delta` 与多 `tool_use` 混合流式、metadata / token usage |

<br/>

### 7.2、如何做到不依赖真实 API Key

**问题引导**：140+ 单测如何在没有真实 API Key 的情况下稳定跑通？

答案就是 `HttpTransport` SPI。测试时注入一个 Fake Transport，直接返回预置的 JSON / SSE 行，即可覆盖：

- 请求 payload 序列化是否正确；
- 响应解析、finish reason、token usage 映射是否正确；
- 流式聚合、工具调用合并是否正确；
- 错误路径（HTTP 4xx/5xx）是否转成 `ModelException`。

> **重点**：传输层的可替换性，不只是为了支持 OkHttp，更是为了让核心协议逻辑能够被离线、确定性地单测。

---

## 八、边界、兼容与演进

### 8.1、已落地能力

- `ChatModel` / `StreamingChatModel` 统一入口；
- `ChatRequest` / `ChatRequestParameters` / `ChatResponse` 统一出入参；
- `AiMessage.toolExecutionRequests()` + `ToolExecutionRequest`；
- `ToolSpecification` / `ToolParameters`（JSON-Schema 风格，纯 `Map`）/ `ToolChoice`；
- OpenAI / Anthropic 的 tool 请求发送链路与 tool-result wire mapping；
- OpenAI 流式 `tool_calls` delta 按 `index` 聚合；
- `AnthropicStreamingChatModel`（含 `tool_use` `input_json_delta` 聚合）；
- `AiMessage.thinking()` / `attributes` 与 `StreamingChatResponseHandler.onPartialThinking(...)`；
- `UserMessage` 的 `name` + `List<Content>` 多模态建模（当前提供 `TextContent`）；
- `agentforge-model-registry` 工厂化装配。

<br/>

### 8.2、待补齐能力

- `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` 等多模态 `Content` 实现与 Provider wire mapping；
- 流式过程暴露 `onPartialToolCall(...)` 增量回调（当前仅最终 `ChatResponse` 暴露完整工具调用）；
- Structured Output / JSON Schema 强类型参数绑定与运行时校验；
- `thinking` 的 Provider 级强类型协议（当前只保留字段位）；
- Prompt Cache；
- retry / backoff / rate-limit policy；
- metrics / tracing / request-id 标准化；
- 其他模型类别：`EmbeddingModel` / `ImageModel` / `AudioModel`。

<br/>

### 8.3、customParameters 的边界

`customParameters` 用于快速支持 Provider 新字段：

```java
DefaultChatRequestParameters.builder()
        .customParameter("reasoning_effort", "high")
        .build();
```

> **弊端说明**：`customParameters` 若被滥用，会让 Core 类型系统逐步失效，上层重新被迫感知供应商字段。
> 因此它只应是「临时通道」，而不是「长期方案」。

判断标准：

```text
仅单一 Provider 使用             -> customParameters
多个 Provider 都出现且语义稳定   -> Core 强类型参数
Agent/Tool 核心流程必须理解       -> Core 强类型模型
```

<br/>

### 8.4、兼容性原则

1. 优先新增接口默认方法或新类型，避免破坏已有 Provider；
2. Core 不暴露供应商 SDK 类型；
3. Provider 特有协议优先留在 Provider module；
4. request/response 对象尽量不可变；
5. 公共 API 保持 Java 8 可编译，默认推荐运行在 JDK 17；
6. Streaming 与 Blocking 最终必须收敛到同一 `ChatResponse` 语义。

---

## 九、总结

回到开头那个问题：**换模型时，Agent 主循环能不能一行不改？**

AgentForge 的答案是：可以。做法是把「变」与「不变」彻底分开——

- **不变**：`ChatModel` / `ChatRequest` / `ChatMessage` / `ChatResponse` 语义、流式生命周期、异常边界；
- **变**：每家厂商的 wire 协议，全部下沉到各自的 Provider 模块，并借助 `HttpTransport`、包私有 wire helper
  与 `LlmFactory` 完成隔离与装配。

由此形成一条可复制的扩展路径：**定义标准协议 → 新增 Provider 模块 → 五步协议转换 → 注册进 LlmFactory**。
这套边界保证 AgentForge 从 LLM 层继续向 Tool Calling、Agent Runtime、Memory 和 Workflow 演进时，上层
不会被某一家模型协议锁死，也让「新增一个协议模型」退化为一件低风险、可复制的工作。

---

## 参考资料

[1]. [LangChain4j - Build LLM-powered applications in Java](https://github.com/langchain4j/langchain4j)

[2]. [OpenAI Chat Completions API Reference](https://platform.openai.com/docs/api-reference/chat)

[3]. [Anthropic Messages API Reference](https://docs.anthropic.com/en/api/messages)

[4]. [OpenAI Streaming Responses (SSE)](https://platform.openai.com/docs/api-reference/chat/streaming)

[5]. [Anthropic Streaming Messages](https://docs.anthropic.com/en/api/messages-streaming)

整理者:长路 创建时间:2026.9.13 更新时间:2026.10.6
