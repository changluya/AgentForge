---
title: "AgentForge模型协议层 ChatModel 原理02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环"
date: 2026-10-02
tags: [AgentForge, 模型协议层, ChatModel, Function Calling]
---

# AgentForge模型协议层 ChatModel 原理02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环

> 适用版本：release_1.x  
> 适用模块：`agentforge-model-api`（`cloud.changlu.agentforge.model.tool`）+ `agentforge-model-core`  
> 包前缀：`cloud.changlu.agentforge.model.tool`  
> 维护者：长路

{/* truncate */}


系列定位：本文是 ChatModel 类别原理课第 02 篇。第 01 篇《ChatModel 核心协议层设计：统一 API 契约封装与
多协议扩展实现》讲了「模型怎么说话」——统一出入参与多协议扩展；本篇讲「模型怎么动手」——Function Calling
从**工具声明**、**协议转换**到**执行回调**的完整闭环。

> 全文聚焦 `agentforge-model-api` 的 `tool` 包（顶层契约 + `spec` / `execution` / `error` 子包）以及
> `agentforge-model-core` 的默认实现（`ToolSpecifications` / `DefaultToolExecutor` / `ToolService`）。
> Provider 侧「`tool_calls` / `tool_use` 如何映射进 `AiMessage`」请见第 01 篇第四章。

<br/>

## 文档导航

| 文档 | 说明 |
|---|---|
| 当前文章（Function Calling / Tool） | `@Tool` 声明、`ToolSpecification`、反射执行、`ToolService` 执行循环与错误处理 |
| [ChatModel原理01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现.md](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现) | 第 01 篇：统一 API 契约、标准 ChatModel 协议、多协议扩展 |
| [openai/OpenAI协议02、AgentForge OpenAI接入核心实践.md](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践) | OpenAI Provider 的 `tool_calls` wire 映射与流式聚合 |
| [anthropic/Anthropic协议02、AgentForge Anthropic接入核心实践.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践) | Anthropic Provider 的 `tool_use` / `tool_result` wire 映射 |

<br/>

## 目录

- [一、背景：模型说「我要调用工具」之后，谁来执行？](#一背景模型说我要调用工具之后谁来执行)
- [二、核心概念：tool 包由哪些契约组成](#二核心概念tool-包由哪些契约组成)
- [三、实现思路：从方法声明到工具规范](#三实现思路从方法声明到工具规范)
- [四、ToolService：推理-执行循环](#四toolservice推理-执行循环)
- [五、实战用例：真实 curl 与返回结果](#五实战用例真实-curl-与返回结果)
- [六、验证测试](#六验证测试)
- [七、边界、兼容与演进](#七边界兼容与演进)
- [八、总结](#八总结)
- [参考资料](#参考资料)

---

## 一、背景：模型说「我要调用工具」之后，谁来执行？

### 1.1、什么是 Function Calling（核心概念）

用一句话说：**Function Calling（也叫 Tool Calling）让模型在生成回答时，可以「提出」调用某个外部函数，
由应用程序去执行，再把结果交还模型继续生成。**

OpenAI 官方[《Function calling》指南](https://developers.openai.com/api/docs/guides/function-calling)对它的
定义是：Function Calling 提供了一种强大而灵活的方式，让模型对接外部系统、访问训练数据之外的数据与动作。

<br/>

#### 1.1.1、三个核心术语

| 术语 | 英文 | 含义 |
|---|---|---|
| 工具 / 函数 | Function / Tool | 我们告诉模型「你可以调用」的一段能力，通常带 JSON-Schema 参数定义 |
| 工具调用 | Function Call / Tool Call | 模型判断需要调用某个工具时返回的结构化响应（含工具名与参数） |
| 工具结果 | Function Call Output / Tool Call Output | 应用执行工具后产出的结果，可为 JSON / 文本 / 图片，并引用对应的调用 ID |

> **注意**：模型本身**不执行**任何工具，它只负责「提出调用」。真正的执行永远发生在应用侧（client tool）
> 或厂商侧（server tool，见 1.1.3）。

<br/>

#### 1.1.2、五步调用流程

参考 OpenAI [《Function calling》指南](https://developers.openai.com/api/docs/guides/function-calling)，Tool
Calling 是应用与模型之间的一段多步对话：

```text
1. 携带「模型可调用的工具列表」发起第一次请求
2. 从模型收到一次 tool call（工具名 + 参数）
3. 在应用侧执行代码（使用 tool call 的输入）
4. 把执行结果（tool output）连同历史消息再发给模型
5. 收到模型的最终回答（或又一次 tool call）
```

这段流程可以循环多次：只要模型还在请求工具，就继续「执行 → 回填 → 再调用」，直到模型给出不再需要工具的
最终回答。这正是 `ToolService` 要封装的循环。

<br/>

#### 1.1.3、Client Tool vs Server Tool

不同厂商对「谁来执行工具」的划分略有不同，Anthropic 在
[《Tool use with Claude》](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)中明确
区分了两类：

| 类型 | 执行方 | 典型例子 | 协议表现 |
|---|---|---|---|
| Client Tool | 你的应用 | 自定义 `get_weather` | 模型返回 `stop_reason: "tool_use"` + `tool_use` 块，应用执行后回 `tool_result` |
| Server Tool | 厂商基础设施 | Web Search、Code Execution | 厂商直接执行，结果随同一次响应返回 |

> **重点**：AgentForge 的 `tool` 包聚焦 **Client Tool**——即「模型提出、应用执行」这条最常见的链路；
> Server Tool 对上层而言等同于「模型直接给了结果」，不经过 `ToolService`。

<br/>

#### 1.1.4、模型为什么会「调用工具」

模型并不是真的在运行代码，而是在**预测一段结构化输出**。当请求里提供了工具（名称 + 描述 + JSON-Schema
参数）后，模型会根据用户意图决定：

- 要不要调用工具（`tool_choice` 可控制 `auto` / `required` / 指定工具）；
- 调用哪一个工具（工具名与描述是关键线索）；
- 用什么参数（按 Schema 生成 JSON）。

一篇[工具学习综述（LLM With Tools: A Survey）](https://arxiv.org/abs/2409.18807)把这类能力概括为
「将用户指令映射为可执行计划并执行」，其核心挑战正是：**工具调用时机、工具选择的准确性、以及稳健的推理
过程**。这也是 AgentForge 要把「工具声明」规范化为 Schema、把「执行」做成可替换引擎的根本原因。

<br/>

### 1.2、从一个具体场景说起

在写一个「查天气」的 Agent 时，我们希望用户问「杭州今天天气怎么样？」，模型不要瞎编，而是输出一个
工具调用，让我们去查真实数据，再让模型基于结果回答。第一轮模型返回的内容大致是：

```text
assistant: tool_calls = [{ id: "call_abc123", name: "get_weather", arguments: "{\"city\":\"杭州\"}" }]
finish_reason = tool_calls
```

问题来了：**接下来这几步由谁来做？**

1. 把 `arguments` 的 JSON 解析成参数，反射调用 `getWeather("杭州")`；
2. 把返回值转成文本，包成一条 `tool` 结果消息；
3. 把「用户问题 + assistant 工具调用 + tool 结果」一起发回模型；
4. 反复执行，直到模型不再请求工具。

如果每个 Agent 业务都手写这四步，最终会得到一堆重复、易错、难测试的循环代码。

<br/>

### 1.3、如果不封这一层会怎样？

我们在项目里对比过「手写循环」和「给一个执行引擎」两种做法：

| 方案 | 优点 | 弊端说明 |
|---|---|---|
| 业务里手写 4 步循环 | 直观、无额外抽象 | 解析/反射/回填重复；错误处理各写各的；单测很难 |
| 只提供 `@Tool` 注解 | 声明简单 | 仍要业务自己扫描、建 Specification、绑定参数 |
| **Tool 契约 + ToolService（当前选型）** | 声明、执行、循环、错误处理全部收敛 | 需要一个稳定的工具契约与执行引擎 |

`agentforge-model-api` 的 `tool` 包（顶层契约 + `spec` / `execution` / `error` 三个子包）就是为了把这四步标准化：

> **重点**：Provider 层解决「模型协议里如何表达工具调用」（wire 映射）；`tool` 包解决「上层 Agent 如何把
> 普通 Java 方法变成可被 LLM 调用的工具，并自动驱动多轮执行」。

<br/>

### 1.4、这一层要解决什么

- **声明简单**：一个 `@Tool` 注解 + 一个 `@P` 注解，让普通 Java 方法变成 LLM 可调用工具；
- **规范可移植**：把方法签名转成 JSON-Schema 风格的 `ToolSpecification`，与 OpenAI / Anthropic 的
  `tools[]` 天然对接；
- **执行可替换**：默认反射执行，也允许自定义 `ToolExecutor`（Lambda 即可）；
- **循环可控**：内置推理-执行循环、最大轮次、即时返回、幻觉工具与错误处理策略；
- **厂商无关**：无论底层是 OpenAI 还是 Anthropic，上层只面向 `ToolExecutionRequest` / `ToolExecutor` 编程。

<br/>

### 1.5、各厂商对 Function Calling 的支持现状

**问题引导**：是不是所有大模型厂商都支持「标准」的 Function Calling？

先给结论：**没有跨厂商的唯一标准，但形成了两类事实标准；而且「协议支持」不等于「模型支持」。**

<br/>

#### 1.5.1、事实标准：OpenAI 兼容阵营

OpenAI Chat Completions 的 `tools[]` / `tool_choice` / `tool_calls[]` / `role=tool` 已成为行业事实标准，
大量厂商直接「兼容」这一格式：

- 大模型厂商：DeepSeek、Qwen / DashScope、Moonshot / Kimi、MiniMax 等；
- 推理托管：Groq、Together、Mistral 等；
- 本地 / 网关：[Ollama](https://docs.ollama.com/capabilities/tool-calling)、
  [vLLM](https://docs.vllm.ai/en/stable/features/tool_calling/)、Xinference、LiteLLM 等。

> **重点**：这也是 AgentForge `OpenAiChatModel` 可复用的原因——只要实现方遵循 Chat Completions 协议，
> 换一个 `baseUrl` 即可接入，无需新增 Provider（见第 01 篇第四章）。

<br/>

#### 1.5.2、各自原生协议对照

并非所有厂商都走 OpenAI 格式，Anthropic 与 Google 都有自己的原生协议，语义等价但 wire 不同：

| 厂商 | 工具声明 | 工具调用返回 | 结果回填 |
|---|---|---|---|
| [OpenAI（Chat Completions）](https://developers.openai.com/api/docs/guides/function-calling) | `tools[].function.parameters` | `tool_calls[]` | `role=tool` + `tool_call_id` |
| [Anthropic（Messages）](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview) | `tools[].input_schema` | `stop_reason=tool_use` + `tool_use` 块 | `tool_result`（`role=user` 下） |
| [Google Gemini](https://ai.google.dev/gemini-api/docs/function-calling) | `functionDeclarations`（OpenAPI schema 子集） | `functionCall` | `functionResponse` |

> **注意**：OpenAI 自身也在演进——较新的 Responses API 对工具的建模（内置工具、tool search 等）与
> Chat Completions 并不完全一致；接入时需明确针对哪一套 API。

<br/>

#### 1.5.3、协议支持 ≠ 模型支持

即便协议格式兼容，**真正决定能不能用工具的是模型本身**，差异体现在多个维度：

| 维度 | 差异举例 |
|---|---|
| 是否支持工具调用 | 部分旧模型、部分小参数模型不具备该能力 |
| 版本差异 | DeepSeek-R1 早期不支持工具调用，而 [DeepSeek-V3](https://api-docs.deepseek.com/guides/tool_calls/) 支持 |
| `tool_choice` 模式 | `required` / 指定函数在个别实现会被拒绝（DeepSeek 有相关 issue） |
| 并行工具调用 | 是否允许一次返回多个 `tool_calls` 因模型 / 端点而异 |
| 流式工具增量 | 是否支持 `delta.tool_calls[]` 分片、还是只有最终响应才有 |
| strict schema | `strict` / 严格语法约束的支持程度不一 |
| 推理 + 工具 | 思考过程（`reasoning_content`）与工具调用能否同流 |

> **弊端说明**：只验证「wire 兼容」是不够的。同一个 OpenAI 兼容端点，换模型后可能 `tool_choice=required`
> 直接报错，或不再返回并行工具调用。接入时应针对具体「模型 + 端点」做能力探测，而不是假设完全等价。

<br/>

#### 1.5.4、对 AgentForge 的意义

AgentForge 的做法是「内部统一契约 + Provider 适配 + 显式降级」：

- 内部只暴露 `ToolSpecification` / `ToolExecutionRequest`，不泄露厂商字段；
- 每个 Provider 只做自己协议的映射（OpenAI `tool_calls`、Anthropic `tool_use`）；
- 厂商不支持的通用字段，按第 01 篇 2.4 的三种方式处理：映射 / 忽略或显式校验失败 / 走 `customParameters`。

因此「某个厂商或模型不完全支持」不会污染上层——最多是某个 Provider 显式拒绝，而不是让 Agent 主循环崩掉。

<br/>

### 1.6、延伸阅读与资料参考

- [OpenAI《Function calling》官方指南](https://developers.openai.com/api/docs/guides/function-calling)：术语定义
  （Function / Tool、Tool Call、Tool Output）与五步流程，本文 1.1 节的主要参考；
- [OpenAI 2023 年 6 月《Function calling and other API updates》](https://openai.com/index/function-calling-and-other-api-updates/)：
  Function Calling 能力首次公开发布；
- [Anthropic《Tool use with Claude》](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)：
  Client Tool / Server Tool 的划分，以及 `tool_use` / `tool_result` 的完整往返；
- [arXiv《LLM With Tools: A Survey》(2409.18807)](https://arxiv.org/abs/2409.18807)：工具学习的方法与挑战
  （调用时机、工具选择、动态规划）；
- [Berkeley Function Calling Leaderboard (BFCL)](https://gorilla.cs.berkeley.edu/leaderboard.html)：衡量模型
  工具调用准确性的公开榜单；
- [LangChain4j](https://github.com/langchain4j/langchain4j)：AgentForge `tool` 包在设计风格上的重要参考。

完整链接见文末「参考资料」。

---

## 二、核心概念：tool 包由哪些契约组成

### 2.1、包结构与 api / core 划分

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

> **注意**：`Tool` / `P` / `ReturnBehavior` / `ToolExecutor` / `spec` / `execution` 的值对象 / `error`
> 都在 **api**（契约）；`ToolService` / `DefaultToolExecutor` / `ToolExecutionRequestUtil` /
> `ToolSpecifications` 在 **core**（默认实现）。

<br/>

### 2.2、四类核心契约

```text
tool 包契约
├── 声明契约：@Tool / @P / ToolSpecification / ToolParameters
├── 执行契约：ToolExecutor（execute / executeWithResult）
├── 结果契约：ToolExecution / ToolExecutionResult / ReturnBehavior
└── 错误契约：ToolArgumentsException / ToolExecutionException + ErrorHandler + ToolErrorHandlerResult
```

---

## 三、实现思路：从方法声明到工具规范

**问题引导**：一个普通的 Java 方法，怎样一步步变成模型能看懂、框架能执行的「工具」？

整体设计分两步：

1. **先对齐标准 Function Calling**：无论 OpenAI、Anthropic 还是 Gemini，模型侧的交互范式都是同一套——
   「工具声明 → 工具调用 → 工具结果」。框架首先要能产出模型要求的**工具声明（JSON-Schema）**、接住模型返回的
   **工具调用**，并在执行后回填**工具结果**。
2. **再做统一抽象封装**：把「声明」统一为 `ToolSpecification` / `ToolParameters`，把「一次调用」统一为
   `ToolExecutionRequest`，把「结果」统一为 `ToolExecutionResult(Message)`，把「执行」统一为 `ToolExecutor`。
   完成这一步后，本地方法、HTTP、MCP 都只是这一层的**声明来源 / 执行实现**；而最终离模型最近的，就是这份
   provider-neutral 的 **Function Tools 声明**。

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

**真实落地**：上面这套抽象在代码里能一一对应到具体类：

| 核心思路 | 真实落地（代码） |
|---|---|
| 工具声明 | `@Tool` / `@P` → `ToolSpecifications` → `ToolSpecification` / `ToolParameters` |
| 一次调用 | `ToolExecutionRequest(id, name, arguments)` |
| 执行 | `ToolExecutor` / `DefaultToolExecutor`（本地反射） |
| 结果回填 | `ToolExecutionResult` → `ToolExecutionResultMessage` |
| 驱动循环 | `ToolService.chat(...)` |

**最小核心闭环（真实 curl）**：把上面的抽象放进一次真实往返——标准 Function Calling 本质就是「两步请求」：

① 带工具声明请求模型：

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

模型返回工具调用（`content` 为 `null`）：

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

② 本地执行后，用 `role=tool` 回填结果再请求：

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

模型给出最终回答：

```json
{
  "choices": [
    { "index": 0, "finish_reason": "stop",
      "message": { "role": "assistant", "content": "杭州今天 22℃，天气晴，适合出行。" } }
  ],
  "usage": { "prompt_tokens": 96, "completion_tokens": 12, "total_tokens": 108 }
}
```

在 AgentForge 里，②的往返被完整封装：`tool_calls` → `ToolExecutionRequest` → `ToolExecutor` 执行 →
`ToolExecutionResultMessage` 回填，由 `ToolService.chat()` 自动驱动，应用无需手写这段循环。

**真实使用场景**：这一层落地后，最常见的用法包括——

- 查天气、汇率、股价等**实时信息**：模型决定「调什么工具、传什么参数」，本地方法真正去查询；
- 订单 / 账户类**业务操作**：查询订单、发起退款，`@Tool` 方法直接调用已有的 Service；
- **计算 / 数据处理**：计算器、单位换算、SQL 查询，把自然语言转成结构化调用；
- **接入外部系统**：用 `HttpToolExecutor` / `McpToolExecutor` 复用同一套 `ToolService` 循环。

完整的真实请求 / 返回示例见第五章「实战用例」。

综上，本章分两部分：**3.1 标准封装**（与厂商、执行位置无关的协议层契约，也是可扩展性的来源）与
**3.2 默认落地**（AgentForge 默认提供的一套本地 Java 工具实现）。

<br/>

### 3.1、标准封装：Function Calling 协议层抽象与可扩展设计

> 只讲**与厂商、与执行位置都无关的稳定契约**：工具声明如何表达、执行如何扩展、调用与结果如何统一。
> 本地、HTTP、MCP、自定义执行器都建立在这一层之上。

#### 3.1.1、ToolSpecification / ToolParameters：工具声明的统一规范

`ToolSpecification` 是一次工具声明的不可变模型：

```java
public final class ToolSpecification {
    private final String name;
    private final String description;
    private final ToolParameters parameters;   // JSON-Schema 风格
    private final Boolean strict;              // 是否要求 Provider 严格校验
    private final Map<String, Object> metadata;
}
```

`ToolParameters` 刻意用**纯 `Map`** 承载 JSON-Schema，保持 api 零第三方依赖：

```java
ToolParameters parameters = ToolParameters.builder()
        .addProperty("city", "string", "城市名", true)   // name / type / desc / required
        .build();
```

> **重点**：OpenAI 把它落到 `tools[].function.parameters`，Anthropic 落到 `tools[].input_schema`；
> 同一份 `ToolParameters` 不需要为厂商各写一遍。

<br/>

#### 3.1.2、ToolExecutor：可替换的执行扩展点

`ToolExecutor` 是「一次工具调用如何执行」的契约，也是整个 Tool 层可扩展的关键：

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

因为它是函数式接口，执行逻辑可以来自任何地方：

| 实现 | 说明 | 适用场景 |
|---|---|---|
| `DefaultToolExecutor`（默认，本地反射） | 由 `@Tool` 方法自动生成，绑定参数后反射调用 | 绝大多数普通 Java 方法 |
| 自定义 `ToolExecutor`（Lambda） | 直接实现执行逻辑，绕过反射 | 需要动态路由 / 非注解工具 |
| `HttpToolExecutor`（Agent 层） | 把参数映射成 HTTP 请求 | 远程 HTTP 接口 |
| `McpToolExecutor`（Agent 层） | 转发到 MCP Server | 远程 MCP 工具 |

> **重点**：上层只依赖 `ToolExecutor` 契约，因此新增一种工具来源（HTTP / MCP / 自定义）无需改动声明层与
> 协议层。默认的本地实现见 3.2.3。

<br/>

#### 3.1.3、ToolExecutionRequest / ToolExecutionResult：统一调用与结果模型

模型返回的调用统一为 `ToolExecutionRequest`，执行产出统一为 `ToolExecutionResult`：

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

一次执行连同耗时、原始对象被记录为 `ToolExecution`：

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

> **重点**：`result`（原始对象）与 `text`（回给 LLM）分离，避免把 Java 对象直接塞进协议消息。

<br/>

### 3.2、默认落地：本地 Java 方法的工具构建封装

> 讲 AgentForge **默认提供**的一套本地工具实现：用 `@Tool` / `@P` 标注方法，自动生成声明，并用反射执行。

#### 3.2.1、@Tool 与 @P：把 Java 方法声明成工具

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

用法：

```java
public class WeatherTools {

    @Tool(value = "查询指定城市的当前天气")
    public String getWeather(@P(name = "city", description = "城市名") String city) {
        return "杭州今天 22℃，天气晴。";
    }
}
```

返回值约定（由 `DefaultToolExecutor` 执行）：

- `String` -> 原样返回；
- `void` / `null` -> 字面量 `"Success"`；
- 其它类型 -> `Json.stringify(...)` 成 JSON 字符串。

> **注意**：`@P.value()` 与 `@P.description()` 是别名，二者取其一即可；`@P.name()` 用于覆盖参数名，
> 建议始终显式写上，避免依赖 `-parameters` 编译参数。

<br/>

#### 3.2.2、ToolSpecifications：从 @Tool 方法自动生成 JSON-Schema

`ToolSpecifications`（core）负责把方法签名翻译成规范：

```java
public static ToolSpecification toolSpecificationFrom(Method method);
public static String toolNameFrom(Method method);
public static List<ToolSpecification> toolSpecificationsFrom(Object objectWithTools);
public static List<ToolSpecification> toolSpecificationsFrom(Class<?> classWithTools);
public static void validateSpecifications(List<ToolSpecification> toolSpecifications);
```

类型映射（Java -> JSON-Schema）：

| Java 类型 | JSON 类型 |
|---|---|
| `String` | `string` |
| `int/Integer/long/Long/short/Short/byte/Byte` | `integer` |
| `double/Double/float/Float` | `number` |
| `boolean/Boolean` | `boolean` |
| 枚举 | `string` |
| 数组 / `List` / `Set` | `array` |
| `Map` | `object` |

扫描时会遍历类层级（父类方法也会纳入），并用「方法签名」去重。

<br/>

#### 3.2.3、DefaultToolExecutor：反射执行与参数绑定

`DefaultToolExecutor` 是默认的本地实现，核心是「把 `arguments` JSON 绑定到方法参数」，关键步骤：

```java
Object[] arguments = prepareArguments(request);   // arguments JSON -> Map -> 按参数名取值
method.setAccessible(true);
return method.invoke(object, arguments);
```

参数绑定会做**轻量类型转换**：`String` / 数字（含 `BigDecimal` 兼容）/ `boolean` / 枚举 / `Map`；
若必填参数缺失，抛 `ToolArgumentsException`；若方法内部抛异常，包装成 `ToolExecutionException`。

```java
private static String text(Object result) {
    if (result == null) return "Success";
    if (result instanceof String) return (String) result;
    return Json.stringify(result);          // 非字符串统一序列化为 JSON
}
```

> **注意**：`DefaultToolExecutor` 会把 `InvocationTargetException.getCause()` 解包——如果方法抛的是
> `ToolArgumentsException`，则原样向上抛；否则统一包成 `ToolExecutionException`，避免异常伪装。

<br/>

#### 3.2.4、ToolExecutionRequestUtil：arguments JSON -> Map

模型给的 `arguments` 是**原始 JSON 字符串**，执行前必须解析：

```java
static Map<String, Object> argumentsAsMap(ToolExecutionRequest request) {
    // 空 / "{}" -> 空 Map
    // 解析失败 -> ToolArgumentsException
    // 解析结果不是 JSON 对象 -> ToolArgumentsException
}
```

> **弊端说明**：模型偶尔会产出非法 JSON 或类型不符的 `arguments`。这类错误被明确归类为
> `ToolArgumentsException`，交由专门的 handler 处理，而不是让它变成难以定位的运行时异常。

<br/>

#### 3.2.5、ReturnBehavior：结果返回行为

```java
public enum ReturnBehavior {
    TO_LLM,               // 默认：结果回给 LLM，继续下一轮
    IMMEDIATE,            // 执行后立即返回给调用方，终止循环
    IMMEDIATE_IF_LAST     // 仅当它是本轮最后一个工具调用时立即返回
}
```

即时返回判定规则（任意工具出错都会强制再跑一轮）：

```text
[]                                       -> 继续（无工具调用）
[TO_LLM, ...] 含任一 TO_LLM               -> 继续
[IMMEDIATE] | [IMMEDIATE, IMMEDIATE]     -> 立即返回
[IMMEDIATE_IF_LAST]                      -> 立即返回
[.., IMMEDIATE_IF_LAST]（最后一个）       -> 立即返回
```

---

## 四、ToolService：推理-执行循环

`ToolService`（core）是把上面所有契约串起来的执行引擎。

<br/>

### 4.1、注册工具

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

重复工具名会抛 `IllegalArgumentException`，保证注册表唯一。

<br/>

### 4.2、chat() 主循环

**问题引导**：模型可能连续多轮要求调用工具，循环怎样才能既自动又安全？

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

- 默认 `maxToolCallingRoundTrips = 100`，超过抛 `IllegalStateException`，防止模型陷入死循环；
- 结果通过 `ToolChatResult` 返回：`finalResponse()` / `toolExecutions()` / `intermediateResponses()`。

<br/>

### 4.3、参数合并：自动把工具注入请求

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

> **重点**：调用方不需要每次手动把 `tools()` 塞进 `ChatRequestParameters`；`ToolService` 会把已注册工具
> 自动合并进去，同时保留用户对其他参数的覆盖。

<br/>

### 4.4、错误处理

| 异常 | 触发点 | 默认处理 |
|---|---|---|
| `ToolArgumentsException` | 参数 JSON 无法解析 / 类型不符 / 缺必填参数 | 抛异常（`RETHROW`） |
| `ToolExecutionException` | 工具方法执行失败 | 以 `ToolErrorHandlerResult.text(...)` 回给 LLM |

两种错误都可以定制：

```java
toolService.argumentsErrorHandler((error, context) -> ToolErrorHandlerResult.text(error.getMessage()));
toolService.executionErrorHandler((error, context) -> ToolErrorHandlerResult.text("工具执行失败，请重试"));
```

handler 有两种返回方式：

- 返回 `ToolErrorHandlerResult.text(msg)` -> 错误信息作为工具结果回给 LLM，模型看到后可**自我纠正并重试**；
- 直接抛异常 -> 终止整个工具循环，向上传播给调用方。

此外还有「幻觉工具」策略：模型请求了未注册的工具时，默认 `THROW_ON_HALLUCINATED` 直接抛异常，
可替换为任意 `Function<ToolExecutionRequest, ToolExecutionResultMessage>`。

> **注意**：默认把执行失败回给 LLM（`DEFAULT_TOOL_EXECUTION_ERROR_HANDLER`），是为了让模型有机会纠正
> 参数或换工具；但参数非法默认直接抛，避免把明显错误当成正常结果继续。

<br/>

### 4.5、与 Provider Function Calling 的关系

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

两层解耦：Provider 负责「把协议解析成 `ToolExecutionRequest`」，`ToolService` 负责「执行并回填」。

---

## 五、实战用例：真实 curl 与返回结果

本节用 OpenAI Function Calling 走一遍完整链路：定义工具 → 第一轮拿到 `tool_calls` → 本地执行 →
第二轮回填 `tool_result` → 拿到最终回答，最后演示用 `ToolService` 一次性完成。

> 说明：下方返回值为 **真实协议结构**（`id`、时间戳、token 数会随实际调用变化）。

<br/>

### 5.1、定义并注册工具

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

`ToolSpecifications` 生成的工具声明，以 OpenAI 的 `tools[]` 为例序列化后等价于：

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

### 5.2、第一轮：模型返回 tool_calls

curl：

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

返回（`content` 为 `null`，模型请求调用工具）：

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

AgentForge 侧拿到的是 `AiMessage`：

```text
AiMessage.text() == null
AiMessage.hasToolExecutionRequests() == true
AiMessage.toolExecutionRequests() == [ToolExecutionRequest(id=call_abc123, name=get_weather, arguments={"city":"杭州"})]
ChatResponse.finishReason() == TOOL_EXECUTION
```

<br/>

### 5.3、本地执行工具

`ToolService` 内部会做三件事：按工具名找 `ToolExecutor` → 解析 `arguments` → 反射执行。

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

### 5.4、第二轮：回填 tool result

curl（把 assistant 的 `tool_calls` 与 `role=tool` 的结果一起发回）：

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

返回（模型基于工具结果给出最终回答）：

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

> **重点**：`role=tool` 的 `tool_call_id` 必须与 assistant 的 `tool_calls[].id` 一致（这里是
> `call_abc123`）；AgentForge 用 `ToolExecutionResultMessage.id` 承载这个关联。

<br/>

### 5.5、一次性用 ToolService 完成多轮

上面两轮的手工编排，交给 `ToolService.chat()` 一行搞定：

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());

List<ChatMessage> messages = new ArrayList<ChatMessage>();
messages.add(UserMessage.from("杭州今天天气怎么样？"));

ToolChatResult result = toolService.chat(model, /* parameters = */ null, messages);

System.out.println(result.finalResponse().aiMessage().text());   // 杭州今天 22℃，天气晴，适合出行。
System.out.println(result.toolExecutions().size());              // 1（本次调用了几次工具）
```

`result.toolExecutions()` 里能拿到每一次执行的 `request` / `result` / 起止时间，便于日志与可观测性。

<br/>

### 5.6、Anthropic tool_use 对照

同一套 `ToolService` 代码无需改动，底层换成 Anthropic 时，wire 形态不同但语义一致：

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

Anthropic 的第二轮回填消息形如：

```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_1", "content": "杭州今天 22℃，天气晴。" }
  ]
}
```

真实 curl 与流式 `input_json_delta` 聚合详见
[Anthropic协议02、AgentForge Anthropic接入核心实践.md](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践)。

<br/>

### 5.7、一键真实验证脚本

仓库中的验证脚本本身就覆盖了工具调用：

- openai/verify-openai-chat.sh
- anthropic/verify-anthropic-chat.sh

以 OpenAI 为例：

```bash
export OPENAI_API_KEY=sk-...
bash openai/verify-openai-chat.sh
```

脚本会依次执行「非流式请求 -> jq 解析 `tool_calls` -> 流式请求观察 `data:` 帧」。

---

## 六、验证测试

### 6.1、测试矩阵

| 模块 | 测试类 | 覆盖点 |
|---|---|---|
| `agentforge-model-core` | `DefaultToolExecutorTest` | 参数绑定与类型转换、`String`/`void`/对象返回值转文本、缺参抛 `ToolArgumentsException`、方法异常包成 `ToolExecutionException` |
| `agentforge-model-core` | `ToolServiceTest` | 工具注册去重、推理-执行循环、`ReturnBehavior` 即时返回、幻觉工具策略、两类错误 handler、`@Tool` 方法 -> `ToolSpecification` / 类型映射 |
| `agentforge-model-openai` | `OpenAiFunctionCallTest` | `tools`/`tool_choice` 序列化、`tool_calls` 解析、tool result 回流 |
| `agentforge-model-anthropic` | `AnthropicToolUseTest` | `tool_use` 解析、`tool_result` 回流、`input_schema` 序列化 |

<br/>

### 6.2、关键断言

- `argumentsAsMap`：空串 / `"{}"` -> 空 Map；非法 JSON -> `ToolArgumentsException`；
- `DefaultToolExecutor`：`null` 返回值 -> `"Success"`；对象返回值 -> JSON 字符串；
- `ToolService`：工具名重复注册 -> `IllegalArgumentException`；
- 循环：模型不再请求工具时返回最终响应；超过 `maxToolCallingRoundTrips` -> `IllegalStateException`；
- 错误：默认执行失败回给 LLM；参数错误默认抛出。

> **重点**：所有测试通过 Fake `HttpTransport` 与「假模型」完成，不依赖真实 API Key；工具执行本身是纯本地逻辑，
> 可以完全离线断言。

---

## 七、边界、兼容与演进

### 7.1、已落地能力

- `@Tool` / `@P` 声明式工具定义；
- `ToolSpecifications` 自动生成 `ToolSpecification` / `ToolParameters`；
- `DefaultToolExecutor` 反射执行 + 轻量类型转换；
- 自定义 `ToolExecutor`（Lambda）；
- `ToolService` 推理-执行循环、参数自动注入、最大轮次保护；
- `ReturnBehavior`（`TO_LLM` / `IMMEDIATE` / `IMMEDIATE_IF_LAST`）；
- 两类错误 handler、`ToolErrorHandlerResult` 回错自愈、幻觉工具策略；
- `ToolExecution` / `ToolExecutionResult` 执行结果值对象；
- 与 OpenAI / Anthropic 工具协议对接（wire 映射见第 01 篇第四章）。

<br/>

### 7.2、待补齐能力

- 更丰富的参数类型绑定（嵌套 POJO、泛型集合的强类型反序列化）；
- Structured Output / JSON-Schema 运行时校验；
- 工具执行的并发 / 异步（当前循环为顺序执行）；
- 工具级超时与取消（Cancellation）；
- 工具调用审计 / 指标 / trace 标准化；
- 流式场景下 `onPartialToolCall(...)` 增量回调（当前仅最终响应暴露完整工具调用）。

<br/>

### 7.3、与 LangChain4j 的取舍

- 借鉴其 `@Tool` / `@P` / `ToolSpecification` / `ToolExecutor` / `ToolService` 的简单风格；
- 但**不引入 AI-Service、反射链、补偿/异步机制**，只保留自包含的最小执行引擎；
- `ToolParameters` 用纯 `Map`，不引入 JSON 库依赖，保持 api 轻量。

<br/>

### 7.4、兼容性原则

1. 工具契约优先新增默认方法或新类型，避免破坏已有实现；
2. 厂商 wire 细节留在 Provider，`tool` 包保持 Provider-neutral；
3. 执行结果 `result`（原始对象）与 `text`（回给 LLM）分离；
4. 公共 API 保持 Java 8 可编译，默认推荐运行在 JDK 17；
5. 错误处理显式可配，默认「执行失败回给 LLM、参数非法抛异常」。

---

## 八、总结

回到开头那个问题：**模型说「我要调用工具」之后，谁来执行？**

AgentForge 的答案是：把「声明 → 规范 → 执行 → 循环 → 错误」拆成一组稳定契约，再用 `ToolService` 串起来——

- **声明**：`@Tool` / `@P` 把 Java 方法变成工具；
- **规范**：`ToolSpecifications` 生成与厂商无关的 `ToolSpecification` / `ToolParameters`；
- **执行**：`ToolExecutor` + `DefaultToolExecutor` 完成参数绑定与反射调用；
- **循环**：`ToolService.chat()` 自动驱动多轮工具调用，并用 `ReturnBehavior` 与最大轮次保证可控；
- **错误**：区分参数错误与执行错误，可回给 LLM 自愈，也可直接终止。

有了这一层，上层 Agent 只需要面向 `ToolExecutionRequest` / `ToolExecutor` 编程，就能在 OpenAI、Anthropic
乃至未来任意协议之上复用同一套工具能力。

---

## 参考资料

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

整理者:长路 创建时间:2026.10.6 更新时间:2026.10.6
