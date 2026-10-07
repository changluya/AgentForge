---
title: "AgentForge Model Protocol Layer ChatModel Principles 01, ChatModel Core Protocol Layer Design: Unified API Contract Encapsulation and Multi-Protocol Extension Implementation"
date: 2026-10-01
tags: [AgentForge, Model Protocol Layer, ChatModel]
---

# AgentForge Model Protocol Layer ChatModel Principles 01, ChatModel Core Protocol Layer Design: Unified API Contract Encapsulation and Multi-Protocol Extension Implementation

> Applicable version: release_1.x  
> Applicable module: `agentforge-model` (core contract: `agentforge-model-api`)  
> Package prefix: `cloud.changlu.agentforge.model`  
> Maintainer: Changlu

{/* truncate */}


Series positioning: This article belongs to "AgentForge Core Module Design Principles Article Series / 01, model Model Protocol Layer / chatmodel", and is the 01st article of the ChatModel category principles course. The entire text only covers two things:

1. How `agentforge-model-api` uses a set of **Provider-neutral interfaces** to encapsulate the unified input/output of ChatModel;
2. How **different protocol models such as OpenAI and Anthropic are extended and implemented on top of this standard ChatModel protocol**.

The Provider's underlying wire protocol (field names, Header, SSE, finish reason) is placed separately in the
`openai/` and `anthropic/` directories, to avoid mixing Core design with vendor protocols.

<br/>

## Documentation Navigation

| Document | Description |
|---|---|
| Current article (ChatModel Principles 01) | `agentforge-model-api` interface encapsulation, standard ChatModel protocol, multi-protocol extension design |
| [ChatModel Principles 02, Function Calling Tool Invocation Conversion and Execution Extension Implementation: Tool Contract + ToolService Execution Loop.md](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环) | Article 02: `@Tool` declaration, tool specification, reflective execution and `ToolService` execution loop |
| [openai/OpenAI Protocol 01, A Quick Understanding of the OpenAI Underlying Protocol.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议01、OpenAI底层协议快速理解) | OpenAI Chat Completions underlying protocol: request / response / streaming / tool call wire |
| [openai/OpenAI Protocol 02, Core Practices for AgentForge OpenAI Integration.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践) | AgentForge OpenAI Provider mapping, Builder, streaming aggregation, errors and evolution |
| [anthropic/Anthropic Protocol 01, A Quick Understanding of the Anthropic Underlying Protocol.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议01、Anthropic底层协议快速理解) | Anthropic Messages underlying protocol: top-level system field / content block / SSE / tool_use |
| [anthropic/Anthropic Protocol 02, Core Practices for AgentForge Anthropic Integration.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践) | AgentForge Anthropic Provider mapping, streaming aggregation, compatibility risks and evolution |

<br/>

## Table of Contents

- [1. Background: Why Do We Need a ChatModel Protocol Layer?](#1-background-why-do-we-need-a-chatmodel-protocol-layer)
- [2. Core Concepts: What Does a Standard ChatModel Protocol Look Like?](#2-core-concepts-what-does-a-standard-chatmodel-protocol-look-like)
- [3. Implementation Approach: How agentforge-model-api Encapsulates Unified Input/Output](#3-implementation-approach-how-agentforge-model-api-encapsulates-unified-inputoutput)
- [4. Multi-Protocol Extension: How Different Protocol Models Extend Based on the Standard Protocol](#4-multi-protocol-extension-how-different-protocol-models-extend-based-on-the-standard-protocol)
- [5. Hands-On: A Complete Multi-Round Tool Call](#5-hands-on-a-complete-multi-round-tool-call)
- [6. Hands-On Use Cases: Real curl and Return Results](#6-hands-on-use-cases-real-curl-and-return-results)
- [7. Verification Testing](#7-verification-testing)
- [8. Boundaries, Compatibility, and Evolution](#8-boundaries-compatibility-and-evolution)
- [9. Summary](#9-summary)
- [References](#references)

---

## 1. Background: Why Do We Need a ChatModel Protocol Layer?

### 1.1 Starting from a Concrete Problem

While developing AgentForge's Agent layer, we ran into this concrete problem:

> In the business code of the upper-layer ReAct Agent, today it is connected to OpenAI, tomorrow it may need to
> connect to Anthropic, and the day after tomorrow it may need to connect to an enterprise internal gateway. How
> can we ensure that when the model is swapped, not a single line of code in the Agent main loop needs to change?

Without a unified abstraction, the most common way of writing this looks like:

```java
// Not recommended: directly new-ing the vendor SDK inside the Agent
OpenAiClient client = new OpenAiClient(apiKey);
ChatCompletionResult result = client.chatCompletion(messages, "gpt-4o-mini");
String text = result.getChoices().get(0).getMessage().getContent();
```

This piece of code binds three things tightly together: **the vendor SDK type**, **the vendor request/response
structure**, and **the vendor field naming**. Once you switch to Anthropic Messages (where system is a top-level
field, tools are called `tool_use`, and the return is an array of content blocks), the Agent layer has to be torn
down and rewritten.

<br/>

### 1.2 What Happens If You Bind Directly to Vendor SDKs?

Let us list the cost of "not abstracting" one by one:

- **Upper-layer pollution**: Agent / Memory / Tool Calling are filled with branches like `if (provider == OPENAI)`;
- **Hard to test**: every unit test needs a real API Key and a real network, so CI cannot run;
- **Expensive to extend**: adding one more model vendor = changing one place in the upper-layer business code, and the regression scope is uncontrollable;
- **Protocol differences leak out**: vendor-specific concepts such as `finish_reason`, `prompt_tokens`, `tool_calls` seep into the business code.

> **Key point**: The core value of the model protocol layer is not "supporting a few more models", but **converging vendor differences within a replaceable boundary**.

<br/>

### 1.3 The Four Goals This Layer Must Achieve

Therefore the positioning of `agentforge-model-api` is: **the lowest-level, most stable model access abstraction
layer of AgentForge**. It does not directly bind to OpenAI, Anthropic, or any third-party Java SDK; it only defines
unified request, response, message, streaming callback, HTTP transport, and exception boundaries.

Four design goals:

1. **Provider-neutral**: upper-layer modules such as Agent, Memory, and Tool Calling depend only on the contract layer (`agentforge-model-api`) and core, not on any vendor SDK;
2. **Protocol adaptation pushed down**: differences such as OpenAI / Anthropic field names, Header, SSE, and finish reason are all absorbed by the Provider modules;
3. **Java 8 API compatibility**: the default development environment may use JDK 17, but the public LLM API avoids Java 9+ language features and collection factories;
4. **Reserve extension points for subsequent Agent capabilities**: message types, `customParameters`, `metadata`, and `HttpTransport` all retain room for extension.

> **Note**: Java 8 here means **syntax and bytecode compatibility of the public API**, and does not mean the project can only be built on JDK 8; the internal implementation is still recommended to run on JDK 17.

<br/>

### 1.4 From Request to Response: The Protocol Layer Panorama

The standard ChatModel protocol runs through the whole thing; the entire call chain is as follows:

```text
Agent / Application
       │
       ▼
ChatModel / StreamingChatModel          <- Unified entry: one set of interfaces
       │
       ▼
ChatRequest + ChatMessage + ChatRequestParameters   <- Unified input
       │
       ▼
Provider Adapter(OpenAI / Anthropic / ...)          <- Only does protocol conversion
       │
       ▼
HttpTransport                           <- Unified transport SPI
       │
       ▼
Provider HTTP API  ->  Provider JSON / SSE          <- Vendor differences are isolated inside the Provider
       │
       ▼
ChatResponse + TokenUsage + FinishReason            <- Unified output
```

To summarize in one sentence: **From the top you always see `ChatRequest` in and `ChatResponse` out; vendor protocols only exist inside the Provider modules.**

---

## 2. Core Concepts: What Does a Standard ChatModel Protocol Look Like?

Before writing code, first answer a question: **What exactly does the so-called "standard ChatModel protocol" consist of?**

The answer can be broken down into four layers: module layering, package structure, core contracts, and the
behavioral constraints that Providers must follow.

<br/>

### 2.1 Module Layering and Dependency Direction

```text
agentforge-model
├── agentforge-model-api              # Contract layer: interfaces + req/vo, zero third-party dependencies
│   ├── chat.ChatModel / StreamingChatModel
│   ├── chat.message.*                # ChatMessage system + Content multimodal foundation
│   ├── chat.request.*                # ChatRequest / ChatRequestParameters / ToolChoice
│   ├── chat.response.*               # ChatResponse / StreamingChatResponseHandler / TokenUsage / FinishReason
│   ├── tool / tool.spec / tool.execution / tool.error
│   ├── http.*                        # HttpRequest / HttpResponse / HttpTransport / StreamingHttpResponseHandler
│   └── exception.ModelException
├── agentforge-model-core             # Vendor-independent default implementation and execution engine
│   ├── chat.request.DefaultChatRequestParameters
│   ├── http.JdkHttpTransport         # Based on HttpURLConnection
│   ├── internal.json.Json            # Internal JSON capability (non-public API)
│   └── tool.execution.ToolService / DefaultToolExecutor / ToolExecutionRequestUtil
│       tool.spec.ToolSpecifications
├── agentforge-model-openai           # OpenAI Chat Completions protocol implementation
│   ├── OpenAiChatModel / OpenAiStreamingChatModel
│   └── OpenAiMessages                # Package-internal shared wire helper (non-public API)
├── agentforge-model-anthropic        # Anthropic Messages protocol implementation
│   ├── AnthropicChatModel / AnthropicStreamingChatModel
│   └── AnthropicProtocol             # Blocking / Streaming shared wire mapping
└── agentforge-model-registry         # Out-of-the-box model factory
    ├── LlmFactory / LlmEnum / LlmBasicConfig
    └── models.IModel / BaseModel / OpenAiModel / AnthropicModel
```

The dependency direction is strictly unidirectional and acyclic:

```text
            agentforge-model-api   （contract, no dependencies）
                    ▲
        ┌───────────┼───────────────┐
        │           │               │
  model-core   model-openai   model-anthropic
        ▲           ▲               ▲
        └───────────┴───────────────┘
                    │
            agentforge-model-registry
```

| Module | Responsibility | Contains vendor SDK |
|---|---|---|
| `agentforge-model-api` | Stable contracts: interfaces, req/vo, messages, Tool contracts, HTTP SPI, exceptions | No |
| `agentforge-model-core` | Vendor-independent default implementation: parameter merging, JDK transport, JSON, Tool execution engine | No |
| `agentforge-model-openai` | OpenAI protocol mapping | No (hand-written wire, no SDK introduced) |
| `agentforge-model-anthropic` | Anthropic protocol mapping | No (hand-written wire, no SDK introduced) |
| `agentforge-model-registry` | Assembles Providers by configuration, provides a factory to the upper layer | No |

> **Key point**: Core only defines a stable, Provider-neutral LLM boundary; Provider modules are only responsible
> for protocol conversion, and absolutely must not leak vendor SDK types to the upper layer.

<br/>

### 2.2 Contract Layer Package Structure

The contract layer is the focus of this article, so let us expand it separately:

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

`agentforge-model-core` holds the default implementations and execution engine (**not in api**):

```text
cloud.changlu.agentforge.model
├── chat.request.DefaultChatRequestParameters   # Parameter default implementation + merge
├── http.JdkHttpTransport                       # HttpURLConnection transport implementation
├── internal.json.Json                          # Internal JSON (non-public API)
└── tool
    ├── spec.ToolSpecifications                 # @Tool method -> ToolSpecification
    └── execution.ToolService / DefaultToolExecutor / ToolExecutionRequestUtil
```

> **Note**: `api` is the "contract", `core` is the "default implementation". The upper layer depends on api to get
> capabilities, and usually brings in core at runtime. Never treat implementation classes such as
> `JdkHttpTransport`, `DefaultChatRequestParameters`, and `Json` as public APIs.

<br/>

### 2.3 What Core Contracts Make Up the Standard Protocol?

```text
Standard ChatModel protocol
├── Entry contract: ChatModel / StreamingChatModel
├── Input contract: ChatRequest + ChatRequestParameters (+ ToolChoice)
├── Message contract: ChatMessage system (System / User / Ai / ToolResult / Custom + Content)
├── Output contract: ChatResponse + TokenUsage + FinishReason
├── Streaming contract: StreamingChatResponseHandler (partial / thinking / complete / error)
├── Transport contract: HttpTransport + StreamingHttpResponseHandler
└── Exception contract: ModelException
```

---

## 3. Implementation Approach: How agentforge-model-api Encapsulates Unified Input/Output

This chapter breaks down the interface encapsulation and implementation of the contract layer layer by layer.
The core idea is: **input is uniformly converged into `ChatRequest`, output is uniformly converged into
`ChatResponse`, and synchronous and streaming share the same set of message and response models.**

<br/>

### 3.1 ChatModel: The Minimum Stable Boundary of a Synchronous Model

```java
@FunctionalInterface
public interface ChatModel {

    ChatResponse chat(ChatRequest chatRequest);

    default String chat(String userMessage) { ... }
    default ChatResponse chat(ChatMessage... messages) { ... }
    default ChatResponse chat(List<? extends ChatMessage> messages) { ... }
}
```

Design points:

- A Provider **only needs to implement one main entry** `ChatResponse chat(ChatRequest)`;
- The three convenience methods (single string, varargs messages, message list) are all `default`, and all eventually converge into `ChatRequest`, so the Provider does not need to implement multiple sets of entries repeatedly;
- `@FunctionalInterface` lets simple model/test implementations use a Lambda directly.

> **Key point**: Converging convenience methods into `ChatRequest` is the key to "a Provider only implementing one
> method". If every convenience method required the Provider to write its own, then once the number of Providers
> grows, things would get out of control.

<br/>

### 3.2 StreamingChatModel: Why Use Callbacks for Streaming?

```java
public interface StreamingChatModel {

    void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler);

    default void chat(String userMessage, StreamingChatResponseHandler handler) { ... }
    default void chat(StreamingChatResponseHandler handler, ChatMessage... messages) { ... }
    default void chat(List<? extends ChatMessage> messages, StreamingChatResponseHandler handler) { ... }
}
```

**Why not use `Flow`, Reactive Streams, or Reactor?** Let us compare several options:

| Option | Advantages | Explanation of drawbacks |
|---|---|---|
| Callback style (current choice) | Java 8 compatible, zero extra dependencies, intuitive implementation | Need to handle callback timing and exception convergence yourself |
| `java.util.concurrent.Flow` | JDK 9+ standard | Public API is forced to upgrade to JDK 9+, breaking Java 8 compatibility |
| Reactor / RxJava | Backpressure, powerful composition operators | The lowest-level protocol layer would strongly depend on a reactive framework, making dependencies heavy |
| `CompletableFuture<Stream>` | Simple | Streaming incremental semantics are expressed unnaturally, cancellation/error handling is cumbersome |

The final choice is the callback style, for the reason: **the protocol layer must be light; the complexity of a reactive framework should not be pushed down to the lowest level.**

The streaming lifecycle is standardized as:

```text
Start request
   │
   ├── onPartialThinking(partialThinking)  0..N times   (reasoning model thinking increment, optional)
   │
   ├── onPartialResponse(partialText)      0..N times   (visible text increment)
   │
   ├── onCompleteResponse(response)        exactly 1 time on success
   │
   └── onError(error)                      1 time on failure
```

```java
public interface StreamingChatResponseHandler {

    void onPartialResponse(String partialResponse);

    default void onPartialThinking(String partialThinking) {}

    void onCompleteResponse(ChatResponse completeResponse);

    void onError(Throwable error);
}
```

> **Note**: `onPartialThinking` provides a default empty implementation, specifically to carry reasoning increments
> such as DeepSeek-style `reasoning_content` and Anthropic `thinking` blocks. In this way, adding a new callback
> also **will not break existing implementations** (they compile with zero changes).

**Question prompt**: Will the streaming and non-streaming result models split into two sets?

No. `onCompleteResponse` returns the **already fully aggregated standard `ChatResponse`**, so streaming and
non-streaming ultimately reuse the unified response model; the Provider is responsible for aggregating tool call
increments, token usage, and finish reason into the `ChatResponse` before the stream ends.

<br/>

### 3.3 ChatRequest: Immutable Unified Input

```java
public final class ChatRequest {
    private final List<ChatMessage> messages;
    private final ChatRequestParameters parameters;
}
```

Constraints:

- `messages` must contain at least one message, otherwise `IllegalArgumentException` is thrown at construction;
- The Builder supports `message(single)` and `messages(collection)`;
- After construction, the message list is wrapped immutably (`Collections.unmodifiableList`);
- `parameters` may be empty, in which case all Provider Model Builder model-level default parameters are used.

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

### 3.4 ChatRequestParameters: Parameter Contract and Merge Rules

| Core field | Java type | Meaning |
|---|---|---|
| `modelName()` | `String` | Provider model ID |
| `temperature()` | `Double` | Sampling randomness; whether it is supported is decided by the Provider |
| `maxTokens()` | `Integer` | Maximum number of output Tokens |
| `topP()` | `Double` | nucleus sampling parameter |
| `stopSequences()` | `List<String>` | Stop sequences |
| `customParameters()` | `Map<String,Object>` | Provider-specific extension fields |
| `tools()` | `List<ToolSpecification>` | Tool declarations the model is allowed to call for this request |
| `toolChoice()` | `ToolChoice` | `AUTO` / `NONE` / `REQUIRED` / `SPECIFIC` |
| `toolChoiceName()` | `String` | The tool name specified when `SPECIFIC` |

The first six fields are abstract methods, and the last three are **interface default methods**:

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

> **Key point**: Prefer to add new capabilities as **interface default methods**. In this way, even if a third
> party has already implemented `ChatRequestParameters`, it can keep compiling with zero changes. This is the key
> technique for a contract to keep evolving without breaking compatibility.

Facing common parameters, a Provider has three legitimate ways to handle them:

```text
Core common parameters
   ├── Natively supported by Provider -> map to vendor fields
   ├── Not supported by Provider      -> ignore or explicitly fail validation
   └── Provider-specific capability   -> extend via customParameters
```

#### 3.4.1 Parameter Merge Rules

`DefaultChatRequestParameters` (core module) is responsible for merging model-level defaults with request-level overrides:

```text
Model Builder defaults
        │
        ▼
DefaultChatRequestParameters.merge(defaults, overrides)
        ▲
        │
Request overrides
```

The rule is: **a non-null request-level value overrides the model-level default value**, implemented as "first apply
defaults, then apply overrides", where each field is written only when non-null; `customParameters` uses Map
merging, where a request-level key with the same name overrides the model-level key with the same name.

```java
public static DefaultChatRequestParameters merge(
        ChatRequestParameters defaults, ChatRequestParameters overrides) {
    Builder builder = builder();
    apply(builder, defaults);
    apply(builder, overrides);
    return builder.build();
}
```

Example:

```java
// model-level
OpenAiChatModel.builder()
        .modelName("model-a")
        .temperature(0.7)
        .maxTokens(2048)
        .build();

// request-level overrides only temperature
DefaultChatRequestParameters.builder().temperature(0.1).build();

// final: modelName=model-a, maxTokens=2048, temperature=0.1
```

<br/>

### 3.5 ChatResponse / TokenUsage / FinishReason: Unified Output

```java
public final class ChatResponse {
    private final AiMessage aiMessage;
    private final TokenUsage tokenUsage;
    private final FinishReason finishReason;
    private final Map<String, Object> metadata;
}
```

| Field | Required | Description |
|---|---|---|
| `aiMessage` | Yes | The final model text / thinking / tool call message |
| `tokenUsage` | No | Standardized Token statistics |
| `finishReason` | No | Standardized finish reason |
| `metadata` | No | Retains Provider-original metadata that the common layer does not need to understand |

> **Key point**: The Provider's raw JSON is not leaked upward directly, but key Provider information can be retained
> in `metadata`. For example, OpenAI saves `id` / `model` / `created`, and Anthropic saves `id` / `model` / `type`.

TokenUsage unifies three fields:

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

FinishReason unified enum:

```java
public enum FinishReason { STOP, LENGTH, TOOL_EXECUTION, CONTENT_FILTER, OTHER }
```

```text
OpenAI stop/length/tool_calls/content_filter -> STOP/LENGTH/TOOL_EXECUTION/CONTENT_FILTER
Anthropic end_turn|stop_sequence/max_tokens/tool_use -> STOP/LENGTH/TOOL_EXECUTION
```

<br/>

### 3.6 ChatMessage: The Message System

```java
public interface ChatMessage {

    ChatMessageType type();

    default String text() {
        throw new UnsupportedOperationException(
                "Message type " + type() + " does not expose a text payload");
    }
}
```

Message types are uniformly registered by the `ChatMessageType` enum and bound to concrete implementation classes:

| Type | Implementation class | Current semantics |
|---|---|---|
| `SYSTEM` | `SystemMessage` | System instruction (inherits `AbstractTextMessage`) |
| `USER` | `UserMessage` | User input, can carry `name` and multimodal `List<Content>` |
| `AI` | `AiMessage` | Model output / historical assistant message, can carry `thinking` and `toolExecutionRequests` |
| `TOOL_EXECUTION_RESULT` | `ToolExecutionResultMessage` | Tool execution result |
| `CUSTOM` | `CustomMessage` | Provider-specific message |

#### 3.6.1 AiMessage

```java
public final class AiMessage implements ChatMessage {
    private final String text;
    private final String thinking;
    private final List<ToolExecutionRequest> toolExecutionRequests;
    private final Map<String, Object> attributes;
}
```

- `text()` may be `null` (during pure tool calling);
- `thinking()` reserves a field position for reasoning content;
- `hasToolExecutionRequests()` / `toolExecutionRequests()` expose tool calls;
- `attributes()` provides a Provider extension position, defensively copied into a read-only Map.

#### 3.6.2 UserMessage and the Content Multimodal Foundation

```java
public final class UserMessage implements ChatMessage {
    private final String name;
    private final List<Content> contents;
}
```

- `name` may be empty, and the Provider may ignore it;
- `contents` must have at least one entry and is immutable after construction;
- `Content` is the multimodal content base interface, and `type()` returns `ContentType`; currently only `TextContent` (`ContentType.TEXT`) is implemented, and `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` can be extended later **without changing the message semantics**.

Retained historical compatibility accessors:

| Method | Semantics |
|---|---|
| `text()` | Returns the text when there is a single `TextContent`; otherwise throws `UnsupportedOperationException` |
| `hasSingleText()` | Whether there is only one `TextContent` |
| `singleText()` | Returns it when there is only one `TextContent`, otherwise throws an exception |

```java
UserMessage.from("hello");
UserMessage.from("alice", "hello");
UserMessage.from(TextContent.from("describe this"), TextContent.from("..."));
UserMessage.from("alice", Arrays.<Content>asList(TextContent.from("hi")));
```

#### 3.6.3 Tool Messages and Custom Messages

- `ToolExecutionRequest`: a single tool call initiated by the model, with fields `id` / `name` / `arguments` (raw JSON string);
- `ToolExecutionResultMessage`: the tool execution result, with fields `id` / `toolName` / `text` / `isError` / `attributes`, where `id` must match the invoked `ToolExecutionRequest.id()`, used for correlation;
- `CustomMessage`: carries messages that are Provider-specific and whose structure the framework cannot predefine; it only exposes an immutable `attributes()`. If a Provider does not support it, it should explicitly reject it rather than silently degrading it to an ordinary user message.

<br/>

### 3.7 HttpTransport: Transport and Protocol Decoupling

```java
public interface HttpTransport {

    HttpResponse execute(HttpRequest request) throws IOException;

    default void executeStreaming(HttpRequest request, StreamingHttpResponseHandler handler) {
        handler.onError(new UnsupportedOperationException(
                "Streaming HTTP is not supported by " + getClass().getName()));
    }
}
```

A Provider only depends on `HttpTransport`, so it can:

- Use `JdkHttpTransport` by default (based on `HttpURLConnection`);
- Inject a Fake Transport during testing (this is the key to not depending on a real API Key);
- Later adapt to OkHttp, Apache HttpClient, or an enterprise internal gateway;
- Without modifying the `ChatModel` API at all.

The underlying stream processing interface only knows HTTP lines and **does not parse SSE**:

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
Provider Stream Parser     <- Vendor differences are absorbed here
        │
        ▼
partial text / thinking / ChatResponse
```

> **Key point**: SSE is part of the vendor protocol and must be parsed by the OpenAI / Anthropic Provider itself.
> Therefore, in the future, even if the Anthropic SSE event format is completely different from OpenAI's, there is
> no need to modify Core.

<br/>

### 3.8 ModelException and internal.json.Json

```java
public class ModelException extends RuntimeException {
    private final Integer statusCode;
    private final String responseBody;
}
```

It can express both HTTP 4xx / 5xx and network IOException, JSON parsing failures, and Provider response
structures that do not match expectations. On HTTP non-2xx, it retains `message` / `statusCode` / `responseBody`,
making it convenient to later uniformly do retry, rate-limit, logging, and observability.

The JSON capability is placed in the `internal` package, which means it is **not an AgentForge external public API**:

```text
Java Map/List -> JSON String
JSON String    -> Map/List
```

> **Note**: In the future, even if the internal JSON implementation is replaced, it should not affect public
> contracts such as `ChatModel`, `ChatRequest`, and `ChatResponse`.

---

## 4. Multi-Protocol Extension: How Different Protocol Models Extend Based on the Standard Protocol

This is the core of this article. Once the standard protocol is defined, how do OpenAI, Anthropic, and any future
protocol extend based on it?

<br/>

### 4.1 Extension Iron Rules

1. **Only add Provider modules**, do not modify the existing contracts of `agentforge-model-api`;
2. **Only implement `ChatModel` / `StreamingChatModel`**, do not redefine request/response types;
3. **Vendor wire details must stay inside the Provider module** (package-private helper);
4. When a new common capability is needed, prefer adding a **default method** to the interface;
5. Vendor-private fields go through `customParameters` / `metadata` / `attributes`, and do not pollute Core.

<br/>

### 4.2 Provider Module Skeleton

```text
agentforge-model-xxx
├── pom.xml                       # Depends on agentforge-model-api (+ core)
└── src/main/java/.../xxx
    ├── XxxChatModel              implements ChatModel
    ├── XxxStreamingChatModel     implements StreamingChatModel
    └── XxxProtocol / XxxMessages # Package-private wire helper (shared by Blocking/Streaming)
```

The pom dependencies of the existing modules:

```text
agentforge-model-openai     -> agentforge-model-api + agentforge-model-core
agentforge-model-anthropic  -> agentforge-model-api + agentforge-model-core
```

<br/>

### 4.3 Hands-On Code: Implementing a Provider in Five Steps

Regardless of the protocol, the implementation process is fixed at five steps:

```text
1. merge default parameters and request parameters
2. ChatRequest -> Provider Request JSON
3. HttpTransport executes the request
4. Provider Response JSON -> ChatResponse
5. Provider Error -> ModelException
```

Using `OpenAiChatModel` as the skeleton:

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

The Provider Builder is responsible for holding: `baseUrl` / `apiKey` / `anthropicVersion` / `modelName` /
sampling parameters / `customHeaders` / `httpTransport` / timeout, and at construction time collects these
parameters into the model-level `DefaultChatRequestParameters`.

<br/>

### 4.4 Request-Side Mapping Comparison

**Question prompt**: How big exactly is the difference between OpenAI and Anthropic? One look at this comparison table makes it clear.

| Core | OpenAI (Chat Completions) | Anthropic (Messages) |
|---|---|---|
| `SystemMessage` | `messages[].role=system` | Top-level `system` field (multiple system entries concatenated with blank lines) |
| `UserMessage` (single text) | `messages[].role=user, content=String` | `messages[].role=user, content=String` |
| `UserMessage` (multiple Content) | `content=[{type:text,text}]` | `content=[{type:text,text}]` |
| `AiMessage` (with tool call) | `role=assistant` + `tool_calls[]` | `role=assistant` + `content=[{type:tool_use,...}]` |
| `ToolExecutionResultMessage` | `role=tool` + `tool_call_id` | `role=user` + `content=[{type:tool_result,...}]` |
| `customParameters` | Spread directly to the top level of the payload | Spread directly to the top level of the payload |
| `tools` | `tools[].function.parameters` | `tools[].input_schema` |
| `toolChoice` | `"auto"/"none"/"required"/{type:function,...}` | `{type:auto/none/any/tool,...}` |
| `temperature/maxTokens/topP/stop` | `temperature/max_tokens/top_p/stop` | `temperature/max_tokens/top_p/stop_sequences` |

The protocol difference points are exactly where the extension design must isolate:

- **Different system position**: OpenAI places it in the messages array, while Anthropic promotes it to a top-level field;
- **Different tool result role**: OpenAI uses `role=tool`, while Anthropic uses a `tool_result` block under `role=user`;
- **Different tool_use input**: OpenAI `arguments` is a JSON string, while Anthropic `input` is an object, requiring serialization alignment.

<br/>

### 4.5 Response-Side Mapping Comparison

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

Key points:

- OpenAI `finish_reason=tool_calls` (older `function_call`) and Anthropic `stop_reason=tool_use` are uniformly mapped to `FinishReason.TOOL_EXECUTION`;
- During pure tool calling, `content` may be `null`, in which case `AiMessage.text()` returns `null`;
- Anthropic `tool_use.input` is an object, serialized into a JSON string and stored in `arguments`, aligned with OpenAI;
- The original `id` / `model` / `created` / `type` go into `ChatResponse.metadata`.

<br/>

### 4.6 Streaming Aggregation

```text
HttpTransport.executeStreaming(request, state)
        │  onOpen / onLine / onComplete / onError
        ▼
Provider Stream Parser (implements StreamingHttpResponseHandler)
        │
        ├── Text increment -> handler.onPartialResponse(...)
        ├── Thinking increment -> handler.onPartialThinking(...)
        └── Tool increment -> aggregated internally by index
        │
        ▼ onCompleteResponse(aggregated ChatResponse)
```

**Text and thinking are called back in real time, while tool calls are only exposed in the final response**, consistent with LangChain4j.

OpenAI `delta.tool_calls[]` is bucketed by `index`:

```text
chunk 1: {index:0, id:"call_1", function:{name:"getWeather", arguments:""}}
chunk 2: {index:0, function:{arguments:"{\"city\":"}}
chunk 3: {index:0, function:{arguments:"\"hangzhou\"}"}}
        │
        ▼  merge by index
ToolExecutionRequest(id=call_1, name=getWeather, arguments={"city":"hangzhou"})
```

- Most OpenAI-compatible endpoints carry `index`; when `index` is absent, "a new `id` appearing" is used as the start of a new call for fallback bucketing;
- Multiple `arguments` fragments of the same `index` are concatenated in arrival order, and the raw JSON text is not reformatted.

Anthropic opens a block with `content_block_start` (`tool_use`):

```text
content_block_start (index=1, tool_use, id=toolu_1, name=get_weather)
content_block_delta (index=1, input_json_delta: '{"city":')
content_block_delta (index=1, input_json_delta: '"hangzhou"}')
        │
        ▼  accumulate by content block index
ToolExecutionRequest(id=toolu_1, name=get_weather, arguments={"city":"hangzhou"})
```

<br/>

### 4.7 Shared Wire Helper

It is recommended that each Provider extract a **package-private** wire helper for Blocking / Streaming reuse:

```text
OpenAiMessages（package-private, final）
├── serialize(messages)          ChatMessage -> OpenAI messages[]
├── serializeTools(tools)        ToolSpecification -> tools[]
├── toolChoice(parameters)       ToolChoice -> tool_choice
├── parseToolCalls(toolCalls)    tool_calls[] -> ToolExecutionRequest
└── extractContent(content)      String | content[] -> text

AnthropicProtocol（package-private, final）
├── collectSystemMessages(messages)  top-level system field
├── serializeMessages(messages)      messages[]
├── serializeTools(tools)            input_schema
├── extractToolUses(contentBlocks)   tool_use -> ToolExecutionRequest
└── extractText(contentBlocks)       text block -> text
```

> **Key point**: It is an implementation detail of the Provider, **not a public API**, so it will not pollute `agentforge-model-api`.

<br/>

### 4.8 Model Registration and Factory

**Question prompt**: When the upper layer wants to connect a model, does it still have to `new` a concrete `OpenAiChatModel`?

No. `agentforge-model-registry` provides out-of-the-box assembly capabilities on top of the Providers:

```text
LlmBasicConfig（provider / url / modelName / apiKey / props）
        │
        ▼
LlmFactory.buildChatModel(config) / buildStreamChatModel(config)
        │  LlmEnum.of(providerCode) -> IModel
        ▼
OpenAiModel / AnthropicModel  implement IModel
        │
        ▼
OpenAiChatModel / AnthropicChatModel  (underlying still the standard ChatModel)
```

```java
public interface IModel {
    ChatModel buildChatModel(LlmBasicConfig llmBasicConfig);
    StreamingChatModel buildStreamChatModel(LlmBasicConfig llmBasicConfig);
}
```

`LlmEnum` registers "providerCode -> IModel implementation class", and `LlmFactory` reflectively instantiates and
delegates construction, for example `OPENAI(1, "OpenAI", OpenAiModel.class)`, `ANTHROPIC(2, "Anthropic", AnthropicModel.class)`.

> **Note**: The `IModel` comment has already reserved room: subsequent **other model categories** such as
> `EmbeddingModel` will also continue to be extended here. This means that the pattern of "standard protocol +
> Provider extension" will continue to be replicated along model categories.

<br/>

### 4.9 New Provider Checklist

```text
[ ] Create a new agentforge-model-xxx module, depending on api (+ core)
[ ] XxxChatModel implements ChatModel
[ ] XxxStreamingChatModel implements StreamingChatModel
[ ] XxxProtocol / XxxMessages package-private wire helper (shared by Blocking/Streaming)
[ ] Implement in five steps: merge -> request -> transport -> response -> exception
[ ] Map FinishReason / TokenUsage / metadata / tool calls
[ ] Collect model parameters into DefaultChatRequestParameters defaults
[ ] Add unit tests: plain text / tool call / streaming aggregation / errors / parameter merge
[ ] Add an IModel implementation in agentforge-model-registry and register it in LlmEnum
```

---

## 5. Hands-On: A Complete Multi-Round Tool Call

### 5.1 Why Is a Tool Layer Still Needed?

**Question prompt**: The Provider can already parse `tool_calls`, so why is there still a set of `ToolService` in the Model API?

Because the two solve different problems:

- The Provider layer solves "**how tool calls are expressed in the model protocol**" (wire mapping);
- The Tool layer solves "**how the upper-layer Agent turns an ordinary Java method into a tool callable by the LLM, and automatically drives multi-round execution**".

It corresponds to the package `cloud.changlu.agentforge.model.tool`, split into the top level + three subpackages:

```text
cloud.changlu.agentforge.model.tool
├── Tool / P / ReturnBehavior / ToolExecutor          # Core contracts
├── spec
│   ├── ToolSpecification                             # name/description/parameters/strict
│   ├── ToolParameters                               # JSON-Schema style (pure Map, no third-party dependencies)
│   └── ToolSpecifications（core）                    # @Tool method -> ToolSpecification
├── execution
│   ├── ToolExecution / ToolExecutionResult（api）    # A single execution (request+result+elapsed) / value object
│   ├── ToolService / DefaultToolExecutor（core）     # Registration + reasoning/execution loop / reflective executor
│   └── ToolExecutionRequestUtil（core）              # arguments JSON -> Map
└── error
    ├── ToolArgumentsException / ToolExecutionException
    └── ToolArgumentsErrorHandler / ToolExecutionErrorHandler
        ToolErrorContext / ToolErrorHandlerResult
```

<br/>

### 5.2 Quickly Building a Tool

Annotate a method with `@Tool` and hand it to `ToolService` for registration:

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
toolService.tools(Arrays.asList(new WeatherTools()));   // Scan all @Tool methods

ToolChatResult result = toolService.chat(model, parameters, messages);
System.out.println(result.finalResponse().aiMessage().text());
```

`ToolSpecifications` automatically converts the method name / `@P` parameter names / types into a
`ToolSpecification` + `ToolParameters`, naturally docking with the Provider-side `tools()` / `toolChoice()`.

<br/>

### 5.3 ToolExecutor and the Execution Loop

```java
@FunctionalInterface
public interface ToolExecutor {
    String execute(ToolExecutionRequest request, Object memoryId);
    default ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) { ... }
}
```

- `DefaultToolExecutor` (core) is the default reflective implementation: it binds the `arguments()` JSON to the `@Tool` method parameters and invokes it;
  - `String` -> returned as-is;
  - `void` -> the literal `"Success"`;
  - others -> `Json.stringify(...)`.
- You can also use a Lambda to provide a custom `ToolExecutor`.

The loop of `ToolService.chat(...)`:

```text
1. Call ChatModel with the registered tools
2. If aiMessage.hasToolExecutionRequests():
     a. Execute tools one by one (if no executor found -> throw an exception by default)
     b. Convert each ToolExecutionResult into a ToolExecutionResultMessage (correlated by id)
     c. Append to the message list
     d. If ReturnBehavior requires an immediate return -> stop
     e. Otherwise continue to the next round with the new messages
3. If there is no tool call -> return the final ChatResponse + all ToolExecution
```

- Default `maxToolCallingRoundTrips = 100`, preventing infinite loops;
- `ReturnBehavior`: `TO_LLM` (default), `IMMEDIATE`, `IMMEDIATE_IF_LAST`;
- Any tool error forces another round to be run, so that the LLM can see the error and correct and retry.

<br/>

### 5.4 Error Handling

| Exception | Trigger point | Default handling |
|---|---|---|
| `ToolArgumentsException` | Arguments JSON cannot be parsed / type mismatch / missing required parameter | Throw an exception (`RETHROW`) |
| `ToolExecutionException` | Tool method execution fails | Return `ToolErrorHandlerResult.text(...)` to the LLM |

You can customize via `argumentsErrorHandler(...)` / `executionErrorHandler(...)`: either return
`ToolErrorHandlerResult.text(msg)` (returned to the LLM), or directly throw an exception (terminating the call).

<br/>

### 5.5 Relationship with the Function Calling Protocol Layer

```text
ToolService.chat()                     <- Upper-layer Agent entry (reuses this chapter's Tool layer)
        │
ChatModel / ChatRequest / ChatResponse <- Chapter 3/4 standard protocol
        │
OpenAI / Anthropic Adapter             <- Chapter 4 multi-protocol extension
        │
AiMessage.toolExecutionRequests()      <- The tool calls given by the model
        │
ToolService executes -> ToolExecutionResultMessage filled back
```

---

## 6. Hands-On Use Cases: Real curl and Return Results

This chapter walks completely through the most common call forms — **non-streaming / streaming** and **plain
text / Function Calling / thinking process** — with real curl and return results, for easy cross-reference with
the `ChatRequest` / `ChatResponse` mapping in Chapter 3 and the protocol conversion in Chapter 4. On the OpenAI
side, it additionally covers two types of returns: "tool calling" and "reasoning model thinking process".

> Note: The return values below are **real protocol structures** (`id`, timestamps, and token counts will change
> with the actual call); the repository provides runnable `openai/verify-openai-chat.sh` and
> `anthropic/verify-anthropic-chat.sh` for real-environment verification.

<br/>

### 6.1 OpenAI

This section covers 4 types of OpenAI returns: non-streaming plain text, non-streaming Function Calling,
streaming thinking process, and streaming Function Calling.

#### 6.1.1 Non-Streaming (Plain Text)

curl:

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

Return (excerpt):

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

AgentForge-side mapping:

```text
choices[0].message.content -> AiMessage.text()
choices[0].finish_reason   -> FinishReason.STOP
usage.prompt_tokens        -> TokenUsage.inputTokens  = 28
usage.completion_tokens    -> TokenUsage.outputTokens = 38
usage.total_tokens         -> TokenUsage.totalTokens  = 66
id / model / created       -> ChatResponse.metadata
```

Corresponding call:

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

#### 6.1.2 Non-Streaming (Function Calling)

curl (carrying `tools` and `tool_choice`):

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

Return (the model decides to call the tool, `content` is `null`):

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

AgentForge-side mapping:

```text
message.tool_calls[].id                 -> ToolExecutionRequest.id        = "call_abc123"
message.tool_calls[].function.name      -> ToolExecutionRequest.name      = "get_weather"
message.tool_calls[].function.arguments -> ToolExecutionRequest.arguments = {"city":"杭州"}
message.content = null                  -> AiMessage.text()               = null
finish_reason=tool_calls                -> FinishReason.TOOL_EXECUTION
usage.*                                 -> TokenUsage
```

Corresponding call and "tool result fill-back":

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

// The model returns a tool call: AiMessage.text() == null, finishReason = TOOL_EXECUTION
ToolExecutionRequest call = first.aiMessage().toolExecutionRequests().get(0);
System.out.println(call.name());        // get_weather
System.out.println(call.arguments());   // {"city":"杭州"}

// After executing the tool, fill the result back as a ToolExecutionResultMessage (correlated by id), then start the second round
ChatResponse second = model.chat(ChatRequest.builder()
        .message(UserMessage.from("杭州今天天气怎么样？"))
        .message(first.aiMessage())
        .message(ToolExecutionResultMessage.from(call.id(), call.name(), "杭州今天 22℃，天气晴。"))
        .build());

System.out.println(second.aiMessage().text());   // "杭州今天 22℃，天气晴。"
System.out.println(second.finishReason());       // STOP
```

> **Key point**: The tool call request and the tool execution result are correlated via `id`; `AiMessage` holds both
> text and `toolExecutionRequests()`, so a mixed output of "text + tool call" can also be expressed.

<br/>

#### 6.1.3 Streaming (Plain Text + Thinking Process)

Taking an OpenAI-compatible reasoning model as an example (such as the DeepSeek series, where the thinking increment is placed in `reasoning_content`):

curl (`-N` disables buffering, continuously outputting SSE):

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

Return (`data:` frames, `reasoning_content` first and then `content`):

```text
data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"用户想要一句话解释","content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"reasoning_content":"CompletableFuture，我需要给出准确又简洁的定义。","content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":"CompletableFuture"},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{"content":" 是 Java 8 引入的异步编程工具。"},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-9xYzAbC789","object":"chat.completion.chunk","created":1726200200,"model":"deepseek-reasoner","choices":[],"usage":{"prompt_tokens":14,"completion_tokens":52,"total_tokens":66}}

data: [DONE]
```

AgentForge-side callback sequence:

```text
onPartialThinking("用户想要一句话解释")
onPartialThinking("CompletableFuture，我需要给出准确又简洁的定义。")
onPartialResponse("CompletableFuture")
onPartialResponse(" 是 Java 8 引入的异步编程工具。")
onCompleteResponse(ChatResponse{ text="CompletableFuture 是 Java 8 引入的异步编程工具。",
                                thinking="用户想要一句话解释CompletableFuture，我需要给出准确又简洁的定义。",
                                finishReason=STOP, tokenUsage=(14,52,66) })
```

Corresponding call:

```java
StreamingChatModel model = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("deepseek-reasoner")
        .build();

model.chat("用一句话解释什么是 CompletableFuture。", new StreamingChatResponseHandler() {

    @Override
    public void onPartialThinking(String partialThinking) {
        System.out.print("[思考] " + partialThinking);   // Thinking increment, optionally displayed
    }

    @Override
    public void onPartialResponse(String partialResponse) {
        System.out.print(partialResponse);               // Formal answer, output character by character
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

> Note: The `[DONE]` frame only indicates the end of SSE; the real aggregated result is given by
> `onCompleteResponse` after the last chunk that carries `usage`. `stream_options.include_usage=true` is the
> prerequisite for getting `usage`.

> **Key point**: `reasoning_content` (some implementations use `thinking`) is recognized as a thinking increment
> and goes through `onPartialThinking` in real time, and is finally aggregated into `AiMessage.thinking()`; the
> visible answer goes through `onPartialResponse`, and the two channels do not interfere with each other.

<br/>

#### 6.1.4 Streaming (Function Calling Increment Aggregation)

curl (`stream: true` + `tools`):

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

Return (`delta.tool_calls[]` arrives in fragments by `index`):

```text
data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc123","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"杭州\"}"}}]},"finish_reason":null}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: {"id":"chatcmpl-9xYzAbC101","object":"chat.completion.chunk","created":1726200300,"model":"gpt-4o-mini","choices":[],"usage":{"prompt_tokens":62,"completion_tokens":18,"total_tokens":80}}

data: [DONE]
```

AgentForge-side aggregation result:

```text
Concatenate arguments fragment by fragment by index=0:
  id        = "call_abc123"
  name      = "get_weather"
  arguments = "{\"city\":" + "\"杭州\"}" = {"city":"杭州"}
        │
        ▼ onCompleteResponse
ChatResponse{ text=null,
              toolExecutionRequests=[ToolExecutionRequest(id=call_abc123, name=get_weather, arguments={"city":"杭州"})],
              finishReason=TOOL_EXECUTION }
```

> **Note**: In streaming, tool calls are not exposed via `onPartialResponse`; they are given all at once on
> `AiMessage.toolExecutionRequests()` of `onCompleteResponse`. Most OpenAI-compatible endpoints carry `index`;
> when absent, a newly appearing `id` is used as the start of a new call for fallback bucketing.

**Supplement: thinking + tool call in the same stream**. When an OpenAI-compatible reasoning model carries `tools`,
it may first stream `reasoning_content` and then output `tool_calls`. The two channels fire in sequence and are
still ultimately aggregated into the same `ChatResponse`:

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

### 6.2 Anthropic

#### 6.2.1 Non-Streaming

curl:

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

Return (excerpt):

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

AgentForge-side mapping:

```text
content[].type == "text"  -> concatenated into AiMessage.text()
stop_reason=end_turn      -> FinishReason.STOP
usage.input_tokens        -> TokenUsage.inputTokens  = 20
usage.output_tokens       -> TokenUsage.outputTokens = 42
input + output            -> TokenUsage.totalTokens  = 62
id / model / type         -> ChatResponse.metadata
```

Corresponding call:

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

> **Key point**: `system` is `messages[].role=system` in OpenAI, and the **top-level `system` field** in Anthropic,
> but the AgentForge upper layer always uses the same `SystemMessage`.

<br/>

#### 6.2.2 Streaming

curl:

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

Return (`event:` + `data:` frames):

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

AgentForge-side callback sequence:

```text
onPartialResponse("CompletableFuture")
onPartialResponse(" 是 Java 8 引入的异步编程工具。")
onCompleteResponse(ChatResponse{ text="CompletableFuture 是 Java 8 引入的异步编程工具。",
                                finishReason=STOP, tokenUsage=(20,24) })
```

The corresponding call is the same as 6.1.2, only replacing the model with `AnthropicStreamingChatModel`:

```java
StreamingChatModel model = AnthropicStreamingChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-3-5-sonnet-latest")
        .maxTokens(256)
        .build();

model.chat("用一句话解释什么是 CompletableFuture。", handler);
```

> **Explanation of drawbacks**: Anthropic streaming gives `usage` in two places — `message_start` gives
> `input_tokens`, and `message_delta` gives the cumulative `output_tokens`; the Provider needs to merge the two
> before writing `TokenUsage`.

<br/>

### 6.3 One-Click Real Verification Scripts

The repository already provides two runnable scripts:

```text
openai/verify-openai-chat.sh
anthropic/verify-anthropic-chat.sh
```

Taking OpenAI as an example:

```bash
export OPENAI_API_KEY=sk-...
bash openai/verify-openai-chat.sh
```

The script executes in sequence "non-streaming request -> jq parse `tool_calls` -> streaming request observe `data:`
frames"; the Anthropic script is similar. The real returns also follow the mapping rules of this chapter.

---

## 7. Verification Testing

### 7.1 Test Matrix

| Module | Test class | Coverage points |
|---|---|---|
| `agentforge-model-api` | `AiMessageToolCallTest` | Single/multiple tool calls, text + tool call, thinking/attributes, value equality and immutability, `UserMessage` name + multiple `Content`, `ToolExecutionRequest` field round-trip |
| `agentforge-model-api` | `ChatModelTest` / `StreamingChatModelTest` | Convenience method convergence, streaming lifecycle |
| `agentforge-model-core` | `DefaultChatRequestParametersTest` | Default values / merge overrides / customParameters Map merge |
| `agentforge-model-core` | `ToolServiceTest` / `DefaultToolExecutorTest` | Reasoning-execution loop, parameter binding, error handling |
| `agentforge-model-openai` | `OpenAiFunctionCallTest` | Single/multiple `tool_calls`, mixed text, `tools`/`tool_choice` (including SPECIFIC) serialization, tool result flow-back |
| `agentforge-model-openai` | `OpenAiStreamingFunctionCallTest` | Single tool call delta merge, multiple tools interleaved, text + tool in the same stream, no-`index` fallback |
| `agentforge-model-anthropic` | `AnthropicToolUseTest` | `tool_use` parsing, multiple `tool_use`, `tool_result` flow-back, `input_schema` serialization, multi-`Content` user message |
| `agentforge-model-anthropic` | `AnthropicStreamingToolUseTest` | `input_json_delta` aggregation, mixed streaming of `text_delta` and multiple `tool_use`, metadata / token usage |

<br/>

### 7.2 How to Avoid Depending on a Real API Key

**Question prompt**: How do 140+ unit tests run stably without a real API Key?

The answer is the `HttpTransport` SPI. During testing, inject a Fake Transport that directly returns preset
JSON / SSE lines, which can cover:

- Whether the request payload serialization is correct;
- Whether the response parsing, finish reason, and token usage mapping are correct;
- Whether the streaming aggregation and tool call merging are correct;
- Whether the error path (HTTP 4xx/5xx) is converted into `ModelException`.

> **Key point**: The replaceability of the transport layer is not only for supporting OkHttp, but also for making the
> core protocol logic offline and deterministically unit-testable.

---

## 8. Boundaries, Compatibility, and Evolution

### 8.1 Capabilities Already Delivered

- `ChatModel` / `StreamingChatModel` unified entry;
- `ChatRequest` / `ChatRequestParameters` / `ChatResponse` unified input/output;
- `AiMessage.toolExecutionRequests()` + `ToolExecutionRequest`;
- `ToolSpecification` / `ToolParameters` (JSON-Schema style, pure `Map`) / `ToolChoice`;
- The tool request sending chain and tool-result wire mapping for OpenAI / Anthropic;
- OpenAI streaming `tool_calls` delta aggregated by `index`;
- `AnthropicStreamingChatModel` (including `tool_use` `input_json_delta` aggregation);
- `AiMessage.thinking()` / `attributes` and `StreamingChatResponseHandler.onPartialThinking(...)`;
- Multimodal modeling of `UserMessage` with `name` + `List<Content>` (currently provides `TextContent`);
- `agentforge-model-registry` factory-style assembly.

<br/>

### 8.2 Capabilities Still To Be Completed

- Multimodal `Content` implementations such as `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent` and Provider wire mapping;
- Exposing an `onPartialToolCall(...)` incremental callback during streaming (currently only the final `ChatResponse` exposes the complete tool call);
- Structured Output / JSON Schema strongly-typed parameter binding and runtime validation;
- Provider-level strongly-typed protocol for `thinking` (currently only the field position is reserved);
- Prompt Cache;
- retry / backoff / rate-limit policy;
- metrics / tracing / request-id standardization;
- Other model categories: `EmbeddingModel` / `ImageModel` / `AudioModel`.

<br/>

### 8.3 The Boundary of customParameters

`customParameters` is used to quickly support new Provider fields:

```java
DefaultChatRequestParameters.builder()
        .customParameter("reasoning_effort", "high")
        .build();
```

> **Explanation of drawbacks**: If `customParameters` is abused, the Core type system will gradually become
> ineffective, and the upper layer will again be forced to be aware of vendor fields. Therefore it should only be
> a "temporary channel", not a "long-term solution".

Judgment criteria:

```text
Used by only a single Provider             -> customParameters
Appears in multiple Providers and has stable semantics -> Core strongly-typed parameter
Must be understood by the core Agent/Tool flow        -> Core strongly-typed model
```

<br/>

### 8.4 Compatibility Principles

1. Prefer adding interface default methods or new types, avoiding breaking existing Providers;
2. Core does not expose vendor SDK types;
3. Provider-specific protocols should preferably stay in the Provider module;
4. request/response objects should be as immutable as possible;
5. The public API remains compilable on Java 8, and running on JDK 17 is recommended by default;
6. Streaming and Blocking must ultimately converge to the same `ChatResponse` semantics.

---

## 9. Summary

Returning to the question at the beginning: **When swapping models, can the Agent main loop remain unchanged with not a single line modified?**

AgentForge's answer is: yes. The approach is to thoroughly separate "what changes" from "what does not change" —

- **What does not change**: the semantics of `ChatModel` / `ChatRequest` / `ChatMessage` / `ChatResponse`, the streaming lifecycle, the exception boundary;
- **What changes**: each vendor's wire protocol, all pushed down into their respective Provider modules, and isolated and assembled with the help of `HttpTransport`, package-private wire helpers, and `LlmFactory`.

This forms a replicable extension path: **define the standard protocol → add a Provider module → five-step protocol conversion → register into LlmFactory**.
This set of boundaries ensures that as AgentForge continues to evolve from the LLM layer toward Tool Calling, Agent Runtime, Memory, and Workflow, the upper layer will not be locked in by any single model protocol, and it also reduces "adding one more protocol model" to a low-risk, replicable task.

---

## References

[1]. [LangChain4j - Build LLM-powered applications in Java](https://github.com/langchain4j/langchain4j)

[2]. [OpenAI Chat Completions API Reference](https://platform.openai.com/docs/api-reference/chat)

[3]. [Anthropic Messages API Reference](https://docs.anthropic.com/en/api/messages)

[4]. [OpenAI Streaming Responses (SSE)](https://platform.openai.com/docs/api-reference/chat/streaming)

[5]. [Anthropic Streaming Messages](https://docs.anthropic.com/en/api/messages-streaming)

Compiled by: Changlu Created: 2026.9.13 Updated: 2026.10.6
