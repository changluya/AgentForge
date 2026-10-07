---
title: "AgentForge Model Protocol Layer ChatModel Principles 02, Function Calling Tool Call Transformation and Execution Extension Implementation: Tool Contract + ToolService Execution Loop"
date: 2026-10-02
tags: [AgentForge, Model Protocol Layer, ChatModel, Function Calling]
---

# AgentForge Model Protocol Layer ChatModel Principles 02, Function Calling Tool Call Transformation and Execution Extension Implementation: Tool Contract + ToolService Execution Loop

> Applicable version: release_1.x  
> Applicable modules: `agentforge-model-api` (`cloud.changlu.agentforge.model.tool`) + `agentforge-model-core`  
> Package prefix: `cloud.changlu.agentforge.model.tool`  
> Maintainer: Changlu

{/* truncate */}


Series positioning: This is article 02 in the ChatModel category principles course. Article 01, "ChatModel Core Protocol Layer Design: Unified API Contract Encapsulation and Multi-Protocol Extension Implementation," covered "how the model talks" — unified input/output and multi-protocol extension; this article covers "how the model takes action" — the complete closed loop of Function Calling from **tool declaration** and **protocol transformation** to **execution callback**.

> The full text focuses on the `tool` package of `agentforge-model-api` (top-level contracts + the `spec` / `execution` / `error` subpackages) as well as
> the default implementations in `agentforge-model-core` (`ToolSpecifications` / `DefaultToolExecutor` / `ToolService`).
> For how the Provider side maps "`tool_calls` / `tool_use` into `AiMessage`," please see Chapter 4 of Article 01.

<br/>

## Document Navigation

| Document | Description |
|---|---|
| Current article (Function Calling / Tool) | `@Tool` declaration, `ToolSpecification`, reflective execution, `ToolService` execution loop and error handling |
| [ChatModel Principles 01, ChatModel Core Protocol Layer Design: Unified API Contract Encapsulation and Multi-Protocol Extension Implementation.md](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现) | Article 01: unified API contract, standard ChatModel protocol, multi-protocol extension |
| [openai/OpenAI Protocol 02, AgentForge OpenAI Integration Core Practice.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践) | OpenAI Provider's `tool_calls` wire mapping and streaming aggregation |
| [anthropic/Anthropic Protocol 02, AgentForge Anthropic Integration Core Practice.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践) | Anthropic Provider's `tool_use` / `tool_result` wire mapping |

<br/>

## Table of Contents

- [1. Background: After the Model Says "I Want to Call a Tool," Who Executes It?](#1-background-after-the-model-says-i-want-to-call-a-tool-who-executes-it)
- [2. Core Concepts: What Contracts Make Up the tool Package](#2-core-concepts-what-contracts-make-up-the-tool-package)
- [3. Implementation Approach: From Method Declaration to Tool Specification](#3-implementation-approach-from-method-declaration-to-tool-specification)
- [4. ToolService: The Reasoning-Execution Loop](#4-toolservice-the-reasoning-execution-loop)
- [5. Practical Example: Real curl and Returned Results](#5-practical-example-real-curl-and-returned-results)
- [6. Verification Tests](#6-verification-tests)
- [7. Boundaries, Compatibility and Evolution](#7-boundaries-compatibility-and-evolution)
- [8. Summary](#8-summary)
- [References](#references)

---

## 1. Background: After the Model Says "I Want to Call a Tool," Who Executes It?

### 1.1 What Is Function Calling (Core Concept)

In one sentence: **Function Calling (also called Tool Calling) lets the model, while generating a response, "propose" calling some external function, which the application executes, and then hand the result back to the model to continue generating.**

OpenAI's official ["Function calling" guide](https://developers.openai.com/api/docs/guides/function-calling) defines it as: Function Calling provides a powerful and flexible way for models to connect to external systems and access data and actions beyond the training data.

<br/>

#### 1.1.1 Three Core Terms

| Term | English | Meaning |
|---|---|---|
| Tool / Function | Function / Tool | A piece of capability we tell the model "you can call," usually with a JSON-Schema parameter definition |
| Tool Call | Function Call / Tool Call | The structured response the model returns when it decides a tool needs to be called (containing tool name and arguments) |
| Tool Result | Function Call Output / Tool Call Output | The result produced after the application executes the tool, which may be JSON / text / image and references the corresponding call ID |

> **Note**: The model itself does **not execute** any tool; it is only responsible for "proposing the call." The actual execution always happens on the application side (client tool) or the vendor side (server tool, see 1.1.3).

<br/>

#### 1.1.2 Five-Step Call Flow

Referring to OpenAI's ["Function calling" guide](https://developers.openai.com/api/docs/guides/function-calling), Tool Calling is a multi-step conversation between the application and the model:

```text
1. 携带「模型可调用的工具列表」发起第一次请求
2. 从模型收到一次 tool call（工具名 + 参数）
3. 在应用侧执行代码（使用 tool call 的输入）
4. 把执行结果（tool output）连同历史消息再发给模型
5. 收到模型的最终回答（或又一次 tool call）
```

This flow can loop multiple times: as long as the model is still requesting tools, it continues to "execute → fill back → call again" until the model gives a final answer that no longer needs tools. This is exactly the loop that `ToolService` needs to encapsulate.

<br/>

#### 1.1.3 Client Tool vs Server Tool

Different vendors divide "who executes the tool" slightly differently. Anthropic, in
["Tool use with Claude"](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview), explicitly
distinguishes two categories:

| Type | Executor | Typical Example | Protocol Representation |
|---|---|---|---|
| Client Tool | Your application | Custom `get_weather` | The model returns `stop_reason: "tool_use"` + a `tool_use` block, and the application responds with `tool_result` after execution |
| Server Tool | Vendor infrastructure | Web Search, Code Execution | The vendor executes directly, and the result is returned with the same response |

> **Key point**: AgentForge's `tool` package focuses on **Client Tool** — the most common chain of "the model proposes, the application executes";
> for upper layers, a Server Tool is equivalent to "the model gave the result directly" and does not go through `ToolService`.

<br/>

#### 1.1.4 Why the Model "Calls Tools"

The model is not really running code; it is **predicting a structured output**. Once tools (name + description + JSON-Schema parameters) are provided in the request, the model decides based on user intent:

- Whether to call a tool (`tool_choice` can control `auto` / `required` / a specific tool);
- Which tool to call (tool name and description are key clues);
- What arguments to use (generate JSON according to the Schema).

A [survey on tool learning (LLM With Tools: A Survey)](https://arxiv.org/abs/2409.18807) summarizes this kind of capability as
"mapping user instructions into executable plans and executing them," whose core challenges are precisely: **the timing of tool calls, the accuracy of tool selection, and a robust reasoning process**. This is also the fundamental reason AgentForge standardizes "tool declaration" into a Schema and turns "execution" into a replaceable engine.

<br/>

### 1.2 Starting from a Concrete Scenario

When writing a "check the weather" Agent, we want the user to ask "What's the weather in Hangzhou today?" and the model not to make things up, but to output a tool call so we can look up real data, and then have the model answer based on the result. The content returned by the model in the first round is roughly:

```text
assistant: tool_calls = [{ id: "call_abc123", name: "get_weather", arguments: "{\"city\":\"杭州\"}" }]
finish_reason = tool_calls
```

The question arises: **Who does the next few steps?**

1. Parse the JSON in `arguments` into parameters and reflectively call `getWeather("杭州")`;
2. Convert the return value to text and wrap it as a `tool` result message;
3. Send "user question + assistant tool call + tool result" back to the model together;
4. Execute repeatedly until the model no longer requests tools.

If every Agent business hand-writes these four steps, the end result is a pile of repetitive, error-prone, hard-to-test loop code.

<br/>

### 1.3 What Happens If We Don't Encapsulate This Layer?

In the project we compared two approaches, "hand-writing the loop" and "providing an execution engine":

| Approach | Advantages | Drawbacks |
|---|---|---|
| Hand-writing the 4-step loop in business code | Intuitive, no extra abstraction | Repeated parsing/reflection/filling; error handling written separately each time; very hard to unit test |
| Providing only the `@Tool` annotation | Simple declaration | Business still has to scan, build Specifications, and bind arguments itself |
| **Tool contract + ToolService (current choice)** | Declaration, execution, loop, and error handling are all consolidated | Requires a stable tool contract and execution engine |

The `tool` package of `agentforge-model-api` (top-level contracts + the three subpackages `spec` / `execution` / `error`) exists precisely to standardize these four steps:

> **Key point**: The Provider layer solves "how tool calls are expressed in the model protocol" (wire mapping); the `tool` package solves "how the upper-layer Agent turns an ordinary Java method into a tool that an LLM can call, and automatically drives multi-round execution."

<br/>

### 1.4 What This Layer Needs to Solve

- **Simple declaration**: One `@Tool` annotation + one `@P` annotation turns an ordinary Java method into an LLM-callable tool;
- **Portable specification**: Turns a method signature into a JSON-Schema-style `ToolSpecification` that naturally connects to OpenAI's / Anthropic's `tools[]`;
- **Replaceable execution**: Reflective execution by default, but also allows a custom `ToolExecutor` (a Lambda is enough);
- **Controllable loop**: Built-in reasoning-execution loop, maximum rounds, immediate return, hallucinated-tool and error-handling strategies;
- **Vendor-neutral**: Whether the underlying provider is OpenAI or Anthropic, the upper layer only programs against `ToolExecutionRequest` / `ToolExecutor`.

<br/>

### 1.5 Current Support for Function Calling Across Vendors

**Guiding question**: Do all large-model vendors support "standard" Function Calling?

Here is the conclusion first: **There is no unique cross-vendor standard, but two de facto standards have formed; moreover, "protocol support" is not the same as "model support."**

<br/>

#### 1.5.1 De Facto Standard: The OpenAI-Compatible Camp

OpenAI Chat Completions' `tools[]` / `tool_choice` / `tool_calls[]` / `role=tool` has become the industry de facto standard, and a large number of vendors directly "compatibly" implement this format:

- Large-model vendors: DeepSeek, Qwen / DashScope, Moonshot / Kimi, MiniMax, etc.;
- Inference hosting: Groq, Together, Mistral, etc.;
- Local / gateway: [Ollama](https://docs.ollama.com/capabilities/tool-calling),
  [vLLM](https://docs.vllm.ai/en/stable/features/tool_calling/), Xinference, LiteLLM, etc.

> **Key point**: This is also why AgentForge's `OpenAiChatModel` can be reused — as long as the implementer follows the Chat Completions protocol, you can integrate by switching the `baseUrl`, with no need to add a new Provider (see Chapter 4 of Article 01).

<br/>

#### 1.5.2 Comparison of Each Vendor's Native Protocol

Not all vendors follow the OpenAI format. Anthropic and Google have their own native protocols, semantically equivalent but different on the wire:

| Vendor | Tool Declaration | Tool Call Return | Result Fill-back |
|---|---|---|---|
| [OpenAI (Chat Completions)](https://developers.openai.com/api/docs/guides/function-calling) | `tools[].function.parameters` | `tool_calls[]` | `role=tool` + `tool_call_id` |
| [Anthropic (Messages)](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview) | `tools[].input_schema` | `stop_reason=tool_use` + `tool_use` block | `tool_result` (under `role=user`) |
| [Google Gemini](https://ai.google.dev/gemini-api/docs/function-calling) | `functionDeclarations` (subset of OpenAPI schema) | `functionCall` | `functionResponse` |

> **Note**: OpenAI itself is also evolving — the newer Responses API models tools (built-in tools, tool search, etc.) in a way that is not entirely consistent with Chat Completions; when integrating, you need to be clear about which API you are targeting.

<br/>

#### 1.5.3 Protocol Support ≠ Model Support

Even if the protocol format is compatible, **what really determines whether tools can be used is the model itself**, and differences show up across multiple dimensions:

| Dimension | Example of Difference |
|---|---|
| Whether tool calls are supported | Some older models and some small-parameter models lack this capability |
| Version differences | Early DeepSeek-R1 did not support tool calls, whereas [DeepSeek-V3](https://api-docs.deepseek.com/guides/tool_calls/) does |
| `tool_choice` mode | `required` / specifying a function is rejected in some implementations (DeepSeek has related issues) |
| Parallel tool calls | Whether multiple `tool_calls` can be returned at once varies by model / endpoint |
| Streaming tool increments | Whether `delta.tool_calls[]` fragments are supported, or only in the final response |
| strict schema | The degree of support for `strict` / strict grammar constraints varies |
| Reasoning + tools | Whether the thinking process (`reasoning_content`) and tool calls can coexist in the same stream |

> **Drawback note**: Verifying only "wire compatibility" is not enough. With the same OpenAI-compatible endpoint, after switching models, `tool_choice=required` may error out directly, or parallel tool calls may no longer be returned. When integrating, capability probing should be done for the specific "model + endpoint" rather than assuming full equivalence.

<br/>

#### 1.5.4 Significance for AgentForge

AgentForge's approach is "internal unified contract + Provider adaptation + explicit degradation":

- Internally, only `ToolSpecification` / `ToolExecutionRequest` are exposed, without leaking vendor fields;
- Each Provider only maps its own protocol (OpenAI `tool_calls`, Anthropic `tool_use`);
- Generic fields not supported by a vendor are handled in the three ways from section 2.4 of Article 01: map / ignore or explicitly fail validation / go through `customParameters`.

Therefore "a certain vendor or model does not fully support something" will not pollute the upper layer — at most it is an explicit rejection by a certain Provider, rather than crashing the Agent's main loop.

<br/>

### 1.6 Further Reading and Reference Materials

- [OpenAI "Function calling" official guide](https://developers.openai.com/api/docs/guides/function-calling): term definitions
  (Function / Tool, Tool Call, Tool Output) and the five-step flow, the main reference for section 1.1 of this article;
- [OpenAI's June 2023 "Function calling and other API updates"](https://openai.com/index/function-calling-and-other-api-updates/):
  the first public release of the Function Calling capability;
- [Anthropic "Tool use with Claude"](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview):
  the division of Client Tool / Server Tool, and the complete round trip of `tool_use` / `tool_result`;
- [arXiv "LLM With Tools: A Survey" (2409.18807)](https://arxiv.org/abs/2409.18807): methods and challenges of tool learning
  (call timing, tool selection, dynamic planning);
- [Berkeley Function Calling Leaderboard (BFCL)](https://gorilla.cs.berkeley.edu/leaderboard.html): a public leaderboard
  measuring model tool-calling accuracy;
- [LangChain4j](https://github.com/langchain4j/langchain4j): an important reference for AgentForge's `tool` package in terms of design style.

See the "References" section at the end for complete links.

---

## 2. Core Concepts: What Contracts Make Up the tool Package

### 2.1 Package Structure and api / core Division

```text
cloud.changlu.agentforge.model.tool
├── Tool / P / ReturnBehavior / ToolExecutor            # api：核心契约
├── spec
│   ├── ToolSpecification                               # api：name/description/parameters/strict/metadata
│   └── ToolParameters                                  # api：JSON-Schema 风格参数（纯 Map，无第三方依赖）
├── execution
│   ├── ToolExecution                                   # api：一次执行（请求 + 结果 + 耗时）
│   ├── ToolExecutionResult                             # api：执行结果值对象（text + 原始对象 + isError）
│   ├── ToolService                                     # core：工具注册 + 推理/执行循环
│   ├── DefaultToolExecutor                             # core：@Tool 方法反射执行器
│   └── ToolExecutionRequestUtil                        # core：arguments JSON -> Map
└── error
    ├── ToolArgumentsException / ToolExecutionException # api：两类异常
    ├── ToolArgumentsErrorHandler / ToolExecutionErrorHandler  # api：错误处理器
    ├── ToolErrorContext                                # api：错误上下文
    └── ToolErrorHandlerResult                          # api：可回给 LLM 的错误结果
```

> **Note**: `Tool` / `P` / `ReturnBehavior` / `ToolExecutor` / the value objects of `spec` / `execution` / `error`
> are all in **api** (contracts); `ToolService` / `DefaultToolExecutor` / `ToolExecutionRequestUtil` /
> `ToolSpecifications` are in **core** (default implementations).

<br/>

### 2.2 Four Categories of Core Contracts

```text
tool 包契约
├── 声明契约：@Tool / @P / ToolSpecification / ToolParameters
├── 执行契约：ToolExecutor（execute / executeWithResult）
├── 结果契约：ToolExecution / ToolExecutionResult / ReturnBehavior
└── 错误契约：ToolArgumentsException / ToolExecutionException + ErrorHandler + ToolErrorHandlerResult
```

---

## 3. Implementation Approach: From Method Declaration to Tool Specification

**Guiding question**: How does an ordinary Java method, step by step, become a "tool" that the model can understand and the framework can execute?

The overall design is divided into two steps:

1. **First align with standard Function Calling**: Whether OpenAI, Anthropic, or Gemini, the interaction paradigm on the model side is the same set —
   "tool declaration → tool call → tool result." The framework must first be able to produce the **tool declaration (JSON-Schema)** required by the model, catch the
   **tool call** returned by the model, and fill back the **tool result** after execution.
2. **Then build a unified abstract encapsulation**: Unify "declaration" as `ToolSpecification` / `ToolParameters`, unify "one call" as
   `ToolExecutionRequest`, unify "result" as `ToolExecutionResult(Message)`, and unify "execution" as `ToolExecutor`.
   After this step, local methods, HTTP, and MCP are all just **declaration sources / execution implementations** of this layer; and what ends up closest to the model is this
   provider-neutral **Function Tools declaration**.

```text
本地 @Tool 方法 / HTTP / MCP   （都只是“声明来源”）
        │  ToolSpecifications 扫描 / 各 Factory 构建
        ▼
ToolSpecification + ToolParameters        ← 统一抽象层（provider-neutral）
        │  Provider 映射（第 01 篇第四章）
        ▼
Provider tools[]（OpenAI function.parameters / Anthropic input_schema）
        ↑ 离模型最近的 Function Tools 层
```

**Real implementation**: The abstraction above maps one-to-one to concrete classes in the code:

| Core Idea | Real Implementation (Code) |
|---|---|
| Tool declaration | `@Tool` / `@P` → `ToolSpecifications` → `ToolSpecification` / `ToolParameters` |
| One call | `ToolExecutionRequest(id, name, arguments)` |
| Execution | `ToolExecutor` / `DefaultToolExecutor` (local reflection) |
| Result fill-back | `ToolExecutionResult` → `ToolExecutionResultMessage` |
| Driving the loop | `ToolService.chat(...)` |

**Minimal core loop (real curl)**: Put the abstraction above into a real round trip — standard Function Calling is essentially a "two-request" process:

① Request the model with a tool declaration:

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [ {"role":"user","content":"杭州今天天气怎么样？"} ],
    "tools": [
      { "type":"function",
        "function": {
          "name":"get_weather",
          "description":"查询指定城市的当前天气",
          "parameters": { "type":"object",
            "properties": { "city": { "type":"string","description":"城市名" } },
            "required": ["city"] }
        } }
    ],
    "tool_choice": "auto"
  }'
```

The model returns a tool call (`content` is `null`):

```json
{
  "choices": [
    { "index": 0, "finish_reason": "tool_calls",
      "message": { "role": "assistant", "content": null,
        "tool_calls": [ { "id": "call_abc123", "type": "function",
          "function": { "name": "get_weather", "arguments": "{\"city\":\"杭州\"}" } } ] } }
  ],
  "usage": { "prompt_tokens": 62, "completion_tokens": 18, "total_tokens": 80 }
}
```

② After local execution, fill back the result with `role=tool` and request again:

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role":"user","content":"杭州今天天气怎么样？"},
      {"role":"assistant","content":null,"tool_calls":[
        {"id":"call_abc123","type":"function",
         "function":{"name":"get_weather","arguments":"{\"city\":\"杭州\"}"}}]},
      {"role":"tool","tool_call_id":"call_abc123","content":"杭州今天 22℃，天气晴。"}
    ],
    "tools": [
      { "type":"function",
        "function": { "name":"get_weather", "description":"查询指定城市的当前天气",
          "parameters": { "type":"object",
            "properties": { "city": { "type":"string","description":"城市名" } },
            "required": ["city"] } } }
    ],
    "tool_choice": "auto"
  }'
```

The model gives the final answer:

```json
{
  "choices": [
    { "index": 0, "finish_reason": "stop",
      "message": { "role": "assistant", "content": "杭州今天 22℃，天气晴，适合出行。" } }
  ],
  "usage": { "prompt_tokens": 96, "completion_tokens": 12, "total_tokens": 108 }
}
```

In AgentForge, the round trip in ② is fully encapsulated: `tool_calls` → `ToolExecutionRequest` → `ToolExecutor` execution →
`ToolExecutionResultMessage` fill-back, automatically driven by `ToolService.chat()`, with no need for the application to hand-write this loop.

**Real usage scenarios**: After this layer is implemented, the most common usages include —

- **Real-time information** such as weather, exchange rates, stock prices: the model decides "which tool to call and what arguments to pass," and the local method actually performs the lookup;
- **Business operations** such as orders / accounts: querying orders, initiating refunds, where the `@Tool` method directly calls the existing Service;
- **Computation / data processing**: calculators, unit conversion, SQL queries, turning natural language into structured calls;
- **Connecting to external systems**: using `HttpToolExecutor` / `McpToolExecutor` to reuse the same `ToolService` loop.

See Chapter 5, "Practical Example," for complete real request / return examples.

In summary, this chapter is divided into two parts: **3.1 Standard Encapsulation** (the protocol-layer contracts independent of vendor and execution location, which is also the source of extensibility) and
**3.2 Default Implementation** (a set of local Java tool implementations provided by AgentForge by default).

<br/>

### 3.1 Standard Encapsulation: Function Calling Protocol-Layer Abstraction and Extensible Design

> Only covers the **stable contracts independent of both vendor and execution location**: how tool declarations are expressed, how execution is extended, and how calls and results are unified.
> Local, HTTP, MCP, and custom executors are all built on top of this layer.

#### 3.1.1 ToolSpecification / ToolParameters: Unified Specification for Tool Declarations

`ToolSpecification` is the immutable model of a tool declaration:

```java
public final class ToolSpecification {
    private final String name;
    private final String description;
    private final ToolParameters parameters;   // JSON-Schema 风格
    private final Boolean strict;              // 是否要求 Provider 严格校验
    private final Map<String, Object> metadata;
}
```

`ToolParameters` deliberately uses a **pure `Map`** to carry JSON-Schema, keeping the api free of third-party dependencies:

```java
ToolParameters parameters = ToolParameters.builder()
        .addProperty("city", "string", "城市名", true)   // name / type / desc / required
        .build();
```

> **Key point**: OpenAI maps it to `tools[].function.parameters`, and Anthropic maps it to `tools[].input_schema`;
> the same `ToolParameters` does not need to be written once per vendor.

<br/>

#### 3.1.2 ToolExecutor: A Replaceable Execution Extension Point

`ToolExecutor` is the contract for "how one tool call is executed," and it is also the key to the extensibility of the entire Tool layer:

```java
@FunctionalInterface
public interface ToolExecutor {

    String execute(ToolExecutionRequest request, Object memoryId);

    default ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        String text = execute(request, memoryId);
        return ToolExecutionResult.builder().text(text).build();
    }
}
```

Because it is a functional interface, the execution logic can come from anywhere:

| Implementation | Description | Applicable Scenario |
|---|---|---|
| `DefaultToolExecutor` (default, local reflection) | Automatically generated from `@Tool` methods, reflectively invoked after binding arguments | The vast majority of ordinary Java methods |
| Custom `ToolExecutor` (Lambda) | Directly implements the execution logic, bypassing reflection | Cases requiring dynamic routing / non-annotated tools |
| `HttpToolExecutor` (Agent layer) | Maps arguments into HTTP requests | Remote HTTP interfaces |
| `McpToolExecutor` (Agent layer) | Forwards to an MCP Server | Remote MCP tools |

> **Key point**: The upper layer only depends on the `ToolExecutor` contract, so adding a new kind of tool source (HTTP / MCP / custom) requires no changes to the declaration layer or the
> protocol layer. See 3.2.3 for the default local implementation.

<br/>

#### 3.1.3 ToolExecutionRequest / ToolExecutionResult: Unified Call and Result Model

The call returned by the model is unified as `ToolExecutionRequest`, and the execution output is unified as `ToolExecutionResult`:

```java
public final class ToolExecutionRequest {
    private final String id;
    private final String name;
    private final String arguments;   // 原始 JSON 字符串
}
```

```java
public final class ToolExecutionResult {
    private final boolean isError;
    private final Object result;   // 原始对象，不发给 LLM
    private final String text;     // 发给 LLM 的文本

    public static ToolExecutionResult success(String text);
    public static ToolExecutionResult failure(String text, Throwable cause);
}
```

One execution, together with its duration and raw object, is recorded as a `ToolExecution`:

```java
public final class ToolExecution {
    private final ToolExecutionRequest request;
    private final ToolExecutionResult result;
    private final LocalDateTime startTime;
    private final LocalDateTime finishTime;
    private final Object memoryId;

    public String resultText();
    public boolean hasFailed();
    public Duration duration();
}
```

> **Key point**: `result` (the raw object) and `text` (returned to the LLM) are separated, avoiding stuffing Java objects directly into protocol messages.

<br/>

### 3.2 Default Implementation: Tool Construction Encapsulation for Local Java Methods

> Covers a set of local tool implementations **provided by AgentForge by default**: annotate methods with `@Tool` / `@P`, automatically generate declarations, and execute via reflection.

#### 3.2.1 @Tool and @P: Declaring Java Methods as Tools

```java
public @interface Tool {
    String name() default "";              // 工具名，缺省用方法名
    String[] value() default "";           // 描述，多个字符串按换行拼接
    ReturnBehavior returnBehavior() default ReturnBehavior.TO_LLM;
}
```

```java
public @interface P {
    String name() default "";              // 参数名（javac 未开 -parameters 时尤其有用）
    String value() default "";             // 参数描述（别名）
    String description() default "";       // 参数描述（别名）
}
```

Usage:

```java
public class WeatherTools {

    @Tool(value = "查询指定城市的当前天气")
    public String getWeather(@P(name = "city", description = "城市名") String city) {
        return "杭州今天 22℃，天气晴。";
    }
}
```

Return value conventions (executed by `DefaultToolExecutor`):

- `String` -> returned as-is;
- `void` / `null` -> the literal `"Success"`;
- Other types -> `Json.stringify(...)` into a JSON string.

> **Note**: `@P.value()` and `@P.description()` are aliases, and either one suffices; `@P.name()` is used to override the parameter name,
> and it is recommended to always write it explicitly to avoid depending on the `-parameters` compile flag.

<br/>

#### 3.2.2 ToolSpecifications: Automatically Generating JSON-Schema from @Tool Methods

`ToolSpecifications` (core) is responsible for translating method signatures into specifications:

```java
public static ToolSpecification toolSpecificationFrom(Method method);
public static String toolNameFrom(Method method);
public static List<ToolSpecification> toolSpecificationsFrom(Object objectWithTools);
public static List<ToolSpecification> toolSpecificationsFrom(Class<?> classWithTools);
public static void validateSpecifications(List<ToolSpecification> toolSpecifications);
```

Type mapping (Java -> JSON-Schema):

| Java Type | JSON Type |
|---|---|
| `String` | `string` |
| `int/Integer/long/Long/short/Short/byte/Byte` | `integer` |
| `double/Double/float/Float` | `number` |
| `boolean/Boolean` | `boolean` |
| enum | `string` |
| array / `List` / `Set` | `array` |
| `Map` | `object` |

During scanning, the class hierarchy is traversed (superclass methods are also included), and deduplication is done by "method signature."

<br/>

#### 3.2.3 DefaultToolExecutor: Reflective Execution and Argument Binding

`DefaultToolExecutor` is the default local implementation, whose core is "binding the `arguments` JSON to method parameters." The key steps:

```java
Object[] arguments = prepareArguments(request);   // arguments JSON -> Map -> 按参数名取值
method.setAccessible(true);
return method.invoke(object, arguments);
```

Argument binding performs **lightweight type conversion**: `String` / numbers (including `BigDecimal` compatibility) / `boolean` / enum / `Map`;
if a required argument is missing, a `ToolArgumentsException` is thrown; if the method itself throws an exception, it is wrapped as a `ToolExecutionException`.

```java
private static String text(Object result) {
    if (result == null) return "Success";
    if (result instanceof String) return (String) result;
    return Json.stringify(result);          // 非字符串统一序列化为 JSON
}
```

> **Note**: `DefaultToolExecutor` unwraps `InvocationTargetException.getCause()` — if the method throws a
> `ToolArgumentsException`, it is rethrown as-is; otherwise it is uniformly wrapped as a `ToolExecutionException`, avoiding exception disguise.

<br/>

#### 3.2.4 ToolExecutionRequestUtil: arguments JSON -> Map

The `arguments` given by the model is a **raw JSON string** and must be parsed before execution:

```java
static Map<String, Object> argumentsAsMap(ToolExecutionRequest request) {
    // 空 / "{}" -> 空 Map
    // 解析失败 -> ToolArgumentsException
    // 解析结果不是 JSON 对象 -> ToolArgumentsException
}
```

> **Drawback note**: The model occasionally produces invalid JSON or `arguments` whose types do not match. Such errors are explicitly classified as
> `ToolArgumentsException` and handed to a dedicated handler, rather than letting them become hard-to-locate runtime exceptions.

<br/>

#### 3.2.5 ReturnBehavior: Result Return Behavior

```java
public enum ReturnBehavior {
    TO_LLM,               // 默认：结果回给 LLM，继续下一轮
    IMMEDIATE,            // 执行后立即返回给调用方，终止循环
    IMMEDIATE_IF_LAST     // 仅当它是本轮最后一个工具调用时立即返回
}
```

Immediate-return determination rules (any tool error forces another round):

```text
[]                                       -> 继续（无工具调用）
[TO_LLM, ...] 含任一 TO_LLM               -> 继续
[IMMEDIATE] | [IMMEDIATE, IMMEDIATE]     -> 立即返回
[IMMEDIATE_IF_LAST]                      -> 立即返回
[.., IMMEDIATE_IF_LAST]（最后一个）       -> 立即返回
```

---

## 4. ToolService: The Reasoning-Execution Loop

`ToolService` (core) is the execution engine that strings all the contracts above together.

<br/>

### 4.1 Registering Tools

```java
ToolService toolService = new ToolService();

// 1) 扫描对象内的全部 @Tool 方法
toolService.tools(Arrays.asList(new WeatherTools()));

// 2) 注册单个工具对象
toolService.tools(new WeatherTools());

// 3) 指定方法名
toolService.tool(new WeatherTools(), "getWeather");

// 4) 直接注册 specification + executor
toolService.tool(specification, request -> "...");

// 5) 批量注册 Map<ToolSpecification, ToolExecutor>
toolService.tools(map);
```

Duplicate tool names throw an `IllegalArgumentException`, ensuring the registry is unique.

<br/>

### 4.2 chat() Main Loop

**Guiding question**: The model may request tool calls across multiple consecutive rounds; how can the loop be both automatic and safe?

```java
public ToolChatResult chat(
        ChatModel model, ChatRequestParameters parameters, List<ChatMessage> messages);
```

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

- The default `maxToolCallingRoundTrips = 100`; exceeding it throws an `IllegalStateException`, preventing the model from falling into an infinite loop;
- Results are returned via `ToolChatResult`: `finalResponse()` / `toolExecutions()` / `intermediateResponses()`.

<br/>

### 4.3 Parameter Merging: Automatically Injecting Tools into the Request

```java
private ChatRequestParameters requestParameters(ChatRequestParameters userParameters) {
    DefaultChatRequestParameters toolParameters =
            DefaultChatRequestParameters.builder()
                    .tools(new ArrayList<ToolSpecification>(toolSpecifications))
                    .build();
    // 用户参数覆盖非工具字段；已注册工具始终包含
    return DefaultChatRequestParameters.merge(toolParameters, userParameters);
}
```

> **Key point**: The caller does not need to manually stuff `tools()` into `ChatRequestParameters` every time; `ToolService` automatically merges registered tools in, while preserving the user's overrides of other parameters.

<br/>

### 4.4 Error Handling

| Exception | Trigger Point | Default Handling |
|---|---|---|
| `ToolArgumentsException` | Argument JSON cannot be parsed / type mismatch / missing required argument | Throw exception (`RETHROW`) |
| `ToolExecutionException` | Tool method execution fails | Return to the LLM as `ToolErrorHandlerResult.text(...)` |

Both kinds of errors can be customized:

```java
toolService.argumentsErrorHandler((error, context) -> ToolErrorHandlerResult.text(error.getMessage()));
toolService.executionErrorHandler((error, context) -> ToolErrorHandlerResult.text("工具执行失败，请重试"));
```

A handler has two ways to return:

- Return `ToolErrorHandlerResult.text(msg)` -> the error message is returned to the LLM as the tool result, and the model can **self-correct and retry** after seeing it;
- Throw an exception directly -> terminate the entire tool loop and propagate it up to the caller.

In addition, there is a "hallucinated tool" strategy: when the model requests an unregistered tool, by default `THROW_ON_HALLUCINATED` directly throws an exception,
which can be replaced by any `Function<ToolExecutionRequest, ToolExecutionResultMessage>`.

> **Note**: The default of returning execution failures to the LLM (`DEFAULT_TOOL_EXECUTION_ERROR_HANDLER`) is to give the model a chance to correct
> its arguments or switch tools; but invalid arguments are thrown by default directly, avoiding treating obvious errors as normal results and continuing.

<br/>

### 4.5 Relationship with Provider Function Calling

```text
ToolService.chat()                     <- 上层 Agent 入口（本篇）
        │
ChatModel / ChatRequest / ChatResponse <- 第 01 篇标准协议
        │
OpenAI / Anthropic Adapter             <- wire 映射（tool_calls / tool_use）
        │
AiMessage.toolExecutionRequests()      <- 模型给出的工具调用
        │
ToolService 执行 -> ToolExecutionResultMessage 回填
```

Two layers are decoupled: the Provider is responsible for "parsing the protocol into a `ToolExecutionRequest`," and `ToolService` is responsible for "executing and filling back."

---

## 5. Practical Example: Real curl and Returned Results

This section walks through the complete chain using OpenAI Function Calling: define a tool → get `tool_calls` in the first round → execute locally →
fill back `tool_result` in the second round → get the final answer, and finally demonstrates doing it all at once with `ToolService`.

> Note: The return values below are the **real protocol structure** (`id`, timestamps, and token counts will vary with actual calls).

<br/>

### 5.1 Define and Register a Tool

```java
public class WeatherTools {

    @Tool(value = "查询指定城市的当前天气")
    public String getWeather(@P(name = "city", description = "城市名") String city) {
        return "杭州今天 22℃，天气晴。";
    }
}

ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());
```

The tool declaration generated by `ToolSpecifications`, taking OpenAI's `tools[]` as an example, after serialization is equivalent to:

```json
{
  "type": "function",
  "function": {
    "name": "getWeather",
    "description": "查询指定城市的当前天气",
    "parameters": {
      "type": "object",
      "properties": { "city": { "type": "string", "description": "城市名" } },
      "required": ["city"]
    }
  }
}
```

<br/>

### 5.2 First Round: The Model Returns tool_calls

curl:

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

Return (`content` is `null`, the model requests to call a tool):

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
            "function": { "name": "get_weather", "arguments": "{\"city\":\"杭州\"}" }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 62, "completion_tokens": 18, "total_tokens": 80 }
}
```

What the AgentForge side receives is an `AiMessage`:

```text
AiMessage.text() == null
AiMessage.hasToolExecutionRequests() == true
AiMessage.toolExecutionRequests() == [ToolExecutionRequest(id=call_abc123, name=get_weather, arguments={"city":"杭州"})]
ChatResponse.finishReason() == TOOL_EXECUTION
```

<br/>

### 5.3 Execute the Tool Locally

Internally, `ToolService` does three things: find the `ToolExecutor` by tool name → parse `arguments` → execute reflectively.

```text
request.name      = "get_weather"
request.arguments = "{\"city\":\"杭州\"}"
        │  ToolExecutionRequestUtil.argumentsAsMap()
        ▼
Map{ city = "杭州" }
        │  DefaultToolExecutor.prepareArguments() + coerce()
        ▼
getWeather("杭州")
        ▼
ToolExecutionResult{ text = "杭州今天 22℃，天气晴。", isError = false }
        ▼
ToolExecutionResultMessage(id="call_abc123", toolName="get_weather", text="杭州今天 22℃，天气晴。")
```

<br/>

### 5.4 Second Round: Fill Back the tool result

curl (sending the assistant's `tool_calls` together with the `role=tool` result):

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      {"role": "user", "content": "杭州今天天气怎么样？"},
      {"role": "assistant", "content": null, "tool_calls": [
        {"id": "call_abc123", "type": "function",
         "function": {"name": "get_weather", "arguments": "{\"city\":\"杭州\"}"}}
      ]},
      {"role": "tool", "tool_call_id": "call_abc123", "content": "杭州今天 22℃，天气晴。"}
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

Return (the model gives the final answer based on the tool result):

```json
{
  "id": "chatcmpl-9xYzAbC789",
  "object": "chat.completion",
  "created": 1726200105,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "杭州今天 22℃，天气晴，适合出行。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 96, "completion_tokens": 12, "total_tokens": 108 }
}
```

> **Key point**: The `tool_call_id` of `role=tool` must match the `tool_calls[].id` of the assistant (here
> `call_abc123`); AgentForge uses `ToolExecutionResultMessage.id` to carry this association.

<br/>

### 5.5 Completing Multiple Rounds at Once with ToolService

The manual orchestration of the above two rounds is done in one line with `ToolService.chat()`:

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

List<ChatMessage> messages = new ArrayList<ChatMessage>();
messages.add(UserMessage.from("杭州今天天气怎么样？"));

ToolChatResult result = toolService.chat(model, /* parameters = */ null, messages);

System.out.println(result.finalResponse().aiMessage().text());   // 杭州今天 22℃，天气晴，适合出行。
System.out.println(result.toolExecutions().size());              // 1（本次调用了几次工具）
```

From `result.toolExecutions()` you can obtain the `request` / `result` / start and end times of each execution, which is convenient for logging and observability.

<br/>

### 5.6 Anthropic tool_use Comparison

The same `ToolService` code needs no changes; when switching the underlying provider to Anthropic, the wire form differs but the semantics are consistent:

```text
OpenAI                                      Anthropic
  assistant.tool_calls[] {id,name,arguments}  assistant.content[] {type:tool_use, id,name,input}
  role=tool, tool_call_id=...                 role=user, content=[{type:tool_result, tool_use_id=...}]
        │                                            │
        └──────────────► ToolExecutionRequest ◄──────┘
                              │
                     ToolService 执行
                              │
                     ToolExecutionResultMessage
```

Anthropic's second-round fill-back message looks like:

```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_1", "content": "杭州今天 22℃，天气晴。" }
  ]
}
```

For the real curl and streaming `input_json_delta` aggregation, see
[Anthropic Protocol 02, AgentForge Anthropic Integration Core Practice.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践).

<br/>

### 5.7 One-Click Real Verification Script

The verification scripts in the repository already cover tool calling:

- openai/verify-openai-chat.sh
- anthropic/verify-anthropic-chat.sh

Taking OpenAI as an example:

```bash
export OPENAI_API_KEY=sk-...
bash openai/verify-openai-chat.sh
```

The script executes, in order, "non-streaming request -> jq parses `tool_calls` -> streaming request observing `data:` frames."

---

## 6. Verification Tests

### 6.1 Test Matrix

| Module | Test Class | Coverage |
|---|---|---|
| `agentforge-model-core` | `DefaultToolExecutorTest` | Argument binding and type conversion, `String`/`void`/object return value to text, missing argument throws `ToolArgumentsException`, method exception wrapped as `ToolExecutionException` |
| `agentforge-model-core` | `ToolServiceTest` | Tool registration deduplication, reasoning-execution loop, `ReturnBehavior` immediate return, hallucinated-tool strategy, two kinds of error handlers, `@Tool` method -> `ToolSpecification` / type mapping |
| `agentforge-model-openai` | `OpenAiFunctionCallTest` | `tools`/`tool_choice` serialization, `tool_calls` parsing, tool result flow-back |
| `agentforge-model-anthropic` | `AnthropicToolUseTest` | `tool_use` parsing, `tool_result` flow-back, `input_schema` serialization |

<br/>

### 6.2 Key Assertions

- `argumentsAsMap`: empty string / `"{}"` -> empty Map; invalid JSON -> `ToolArgumentsException`;
- `DefaultToolExecutor`: `null` return value -> `"Success"`; object return value -> JSON string;
- `ToolService`: duplicate tool name registration -> `IllegalArgumentException`;
- Loop: returns the final response when the model no longer requests tools; exceeding `maxToolCallingRoundTrips` -> `IllegalStateException`;
- Errors: execution failures are returned to the LLM by default; argument errors are thrown by default.

> **Key point**: All tests are completed using a Fake `HttpTransport` and a "fake model," without depending on a real API Key; tool execution itself is pure local logic,
> and can be fully asserted offline.

---

## 7. Boundaries, Compatibility and Evolution

### 7.1 Capabilities Already Implemented

- `@Tool` / `@P` declarative tool definitions;
- `ToolSpecifications` automatically generates `ToolSpecification` / `ToolParameters`;
- `DefaultToolExecutor` reflective execution + lightweight type conversion;
- Custom `ToolExecutor` (Lambda);
- `ToolService` reasoning-execution loop, automatic parameter injection, maximum-rounds protection;
- `ReturnBehavior` (`TO_LLM` / `IMMEDIATE` / `IMMEDIATE_IF_LAST`);
- Two kinds of error handlers, `ToolErrorHandlerResult` error-return self-healing, hallucinated-tool strategy;
- `ToolExecution` / `ToolExecutionResult` execution result value objects;
- Integration with OpenAI / Anthropic tool protocols (for wire mapping, see Chapter 4 of Article 01).

<br/>

### 7.2 Capabilities Still To Be Completed

- Richer argument type binding (nested POJOs, strongly-typed deserialization of generic collections);
- Structured Output / JSON-Schema runtime validation;
- Concurrent / asynchronous tool execution (the current loop is sequential);
- Tool-level timeout and cancellation (Cancellation);
- Standardization of tool-call auditing / metrics / trace;
- `onPartialToolCall(...)` incremental callback in streaming scenarios (currently only the final response exposes the complete tool call).

<br/>

### 7.3 Trade-offs Relative to LangChain4j

- Borrows its simple style of `@Tool` / `@P` / `ToolSpecification` / `ToolExecutor` / `ToolService`;
- But **does not introduce AI-Service, reflection chains, or compensation/async mechanisms**, keeping only a self-contained minimal execution engine;
- `ToolParameters` uses a pure `Map`, without introducing a JSON library dependency, keeping the api lightweight.

<br/>

### 7.4 Compatibility Principles

1. Tool contracts prioritize adding default methods or new types, avoiding breaking existing implementations;
2. Vendor wire details stay in the Provider, and the `tool` package remains Provider-neutral;
3. Execution result `result` (the raw object) and `text` (returned to the LLM) are separated;
4. The public API remains compilable on Java 8, with a default recommendation to run on JDK 17;
5. Error handling is explicitly configurable, with the default "execution failure returned to the LLM, invalid arguments throw an exception."

---

## 8. Summary

Back to the question at the beginning: **After the model says "I want to call a tool," who executes it?**

AgentForge's answer is: split "declaration → specification → execution → loop → error" into a set of stable contracts, then string them together with `ToolService` —

- **Declaration**: `@Tool` / `@P` turn Java methods into tools;
- **Specification**: `ToolSpecifications` generates vendor-neutral `ToolSpecification` / `ToolParameters`;
- **Execution**: `ToolExecutor` + `DefaultToolExecutor` complete argument binding and reflective invocation;
- **Loop**: `ToolService.chat()` automatically drives multi-round tool calls, using `ReturnBehavior` and maximum rounds to keep it controllable;
- **Error**: distinguishes argument errors from execution errors, which can be returned to the LLM for self-healing or terminated directly.

With this layer, the upper-layer Agent only needs to program against `ToolExecutionRequest` / `ToolExecutor` to reuse the same set of tool capabilities on top of OpenAI, Anthropic, and even any future protocol.

---

## References

[1]. [OpenAI - Function calling guide](https://developers.openai.com/api/docs/guides/function-calling)

[2]. [OpenAI - Function calling and other API updates](https://openai.com/index/function-calling-and-other-api-updates/)

[3]. [Anthropic - Tool use with Claude](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)

[4]. [Anthropic - Claude can now use tools](https://claude.com/blog/tool-use-ga)

[5]. [arXiv - LLM With Tools: A Survey (2409.18807)](https://arxiv.org/abs/2409.18807)

[6]. [Berkeley Function Calling Leaderboard (BFCL)](https://gorilla.cs.berkeley.edu/leaderboard.html)

[7]. [Google - Gemini API Function calling](https://ai.google.dev/gemini-api/docs/function-calling)

[8]. [DeepSeek - Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)

[9]. [vLLM - Tool Calling](https://docs.vllm.ai/en/stable/features/tool_calling/)

[10]. [Ollama - Tool calling](https://docs.ollama.com/capabilities/tool-calling)

[11]. [LangChain4j - Tools (Function Calling)](https://github.com/langchain4j/langchain4j)

[12]. [JSON Schema](https://json-schema.org/)

Compiled by: Changlu Created: 2026.10.6 Updated: 2026.10.6
