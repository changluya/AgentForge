---
title: "Anthropic协议02、AgentForge 适配Anthropic接入核心实践"
date: 2026-10-06
tags: [AgentForge, 模型协议层, Anthropic]
---

# AgentForge Anthropic 接入核心实践

> 更新日期：2026-10-05  
> 适用模块：`agentforge-model-anthropic`  
> 当前实现：`AnthropicChatModel`（Blocking）、`AnthropicStreamingChatModel`（SSE）、`AnthropicProtocol`（共享 wire mapping）  
> Wire API：Anthropic Messages API  
> 维护者：changlu

{/* truncate */}


一句话结论：Anthropic Provider 把 AgentForge 统一的 `ChatRequest` / `ChatResponse` 与 **Messages API** 双向映射，并处理了 Anthropic 特有的"system 顶层字段 + content block"两大差异；协议本身见《Anthropic协议01、Anthropic底层协议快速理解》。

---

## 一、背景与问题引入

## 1.1、场景驱动：一套 Core，适配 Claude 的 block 协议

> AgentForge 上层只依赖 `ChatMessage` / `ChatRequest` / `ChatResponse`。接入 Claude 时，不能让"system 顶层字段""content block""tool_use/tool_result"这些 wire 细节泄漏到 Agent Runtime。

因此 Anthropic Provider 的职责是**协议翻译层**，并让 Blocking 与 Streaming 共用同一套映射逻辑。

<br/>

## 1.2、问题引导：block 协议如何落到统一消息？

> **问题**：`SystemMessage` 怎么落到顶层 `system`？`AiMessage` 的 text 与工具调用怎么落到 `content[]`？`ToolExecutionResultMessage` 怎么落到 `tool_result`？流式 `input_json_delta` 怎么聚合成完整工具参数？

本文逐项给出映射与实现位置。

<br/>

## 1.3、Builder 参数

| Builder 参数 | 默认值 | 说明 |
|---|---|---|
| `baseUrl` | `https://api.anthropic.com` | API 根地址 |
| `apiKey` | `null` | 必填，写入 `x-api-key` |
| `anthropicVersion` | `2023-06-01` | 写入 `anthropic-version` Header |
| `modelName` | `null` | 请求前必须有值 |
| `temperature` | `null` | 非空映射 `temperature`（兼容性见 5.4） |
| `maxTokens` | `1024` | 映射必填的 `max_tokens` |
| `topP` | `null` | 非空映射 `top_p`（兼容性见 5.4） |
| `stopSequences` | `null` | 映射 `stop_sequences` |
| `customParameter` | 空 | Provider 顶层扩展字段 |
| `customHeader` | 空 | 自定义 Header |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | 连接超时 |
| `readTimeoutMillis` | `60000` | 读取超时 |

> **重点**：`max_tokens` 是 Anthropic 的必填字段，AgentForge 默认 1024，保证最简单调用可直接执行。

---

## 二、核心概念

```java
ChatRequest { List<ChatMessage> messages; ChatRequestParameters parameters; }

ChatResponse { AiMessage aiMessage; TokenUsage tokenUsage; FinishReason finishReason; Map<String,Object> metadata; }
```

Provider 负责把 Core 的 `ChatRequest` 翻译为 Anthropic wire，再把响应归一化为 `ChatResponse`。

---

## 三、实现思路与映射

## 3.1、HTTP Headers

```http
Content-Type: application/json
Accept: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

之后追加 `customHeaders`。

> **注意**：不是 `Authorization: Bearer`，且必须显式发送 `anthropic-version`。

<br/>

## 3.2、SystemMessage 映射

Anthropic 的 system prompt 使用**顶层 `system` 字段**。AgentForge 遍历所有 `SystemMessage`：

```text
SystemMessage A + SystemMessage B → "A\n\nB" → {"system":"A\n\nB"}
```

拼接后的 system **不再进入 `messages` 数组**。

<br/>

## 3.3、User / AI 消息映射

| AgentForge | Anthropic role | content |
|---|---|---|
| `UserMessage`（单文本） | `user` | `message.text()`（字符串） |
| `UserMessage`（多 `Content`） | `user` | `content[]`，逐条 `TextContent` → `{"type":"text","text":...}` |
| `AiMessage`（纯文本） | `assistant` | `message.text()`（字符串） |
| `AiMessage`（含工具调用） | `assistant` | `content[]`：可选 text 块 + `tool_use` 块 |
| `ToolExecutionResultMessage` | `user` | `content[]`：`tool_result` 块 |
| `SystemMessage` | 不进入 messages | 聚合到顶层 `system` |

<br/>

## 3.4、工具调用映射

`AiMessage.hasToolExecutionRequests()` 为真时，映射为 `assistant` 的 `content[]`：

```json
{ "role": "assistant", "content": [
  { "type": "text", "text": "I will check." },
  { "type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": { "city": "hangzhou" } }
]}
```

`ToolExecutionResultMessage` 映射为 `user` 的 `content[]`：

```json
{ "role": "user", "content": [
  { "type": "tool_result", "tool_use_id": "toolu_1", "content": "{\"temperature\":22}" }
]}
```

| Core 字段 | Anthropic 字段 |
|---|---|
| `ToolExecutionRequest.id` | `tool_use.id`（回填对应 `tool_result.tool_use_id`） |
| `ToolExecutionRequest.name` | `tool_use.name` |
| `ToolExecutionRequest.arguments`（JSON 字符串） | `tool_use.input`（**解析为对象**后下发） |
| `AiMessage.text` | 可选的前置 `text` 块 |
| `ToolExecutionResultMessage.isError == true` | `tool_result.is_error = true` |

> **注意**：`CustomMessage` 没有 `text()`，当前仍会抛 `UnsupportedOperationException`。

<br/>

## 3.5、参数映射

| AgentForge 参数 | Anthropic 字段 | 当前行为 |
|---|---|---|
| `modelName` | `model` | 必填；为空本地失败 |
| `maxTokens` | `max_tokens` | 必填；默认 1024 |
| `temperature` | `temperature` | 非空发送；新模型兼容性需注意 |
| `topP` | `top_p` | 非空发送；新模型兼容性需注意 |
| `stopSequences` | `stop_sequences` | 非空发送 |
| `tools` | `tools[]` | `{"name","description","input_schema"}`；无参数时下发空 object schema |
| `toolChoice` | `tool_choice` | `AUTO→{"type":"auto"}`、`NONE→{"type":"none"}`、`REQUIRED→{"type":"any"}`、`SPECIFIC→{"type":"tool","name":X}` |
| `SystemMessage` | `system` | 多条以双换行拼接 |
| `customParameters` | 顶层透传 | 标准字段最终覆盖同名 custom 值 |

<br/>

## 3.6、响应提取规则

遍历 `content` 数组：

- 只提取 `type == "text"` 的块，按顺序拼接为 `AiMessage.text()`；
- 提取 `type == "tool_use"` 的块，映射为 `ToolExecutionRequest`：

| Anthropic `tool_use` | AgentForge |
|---|---|
| `id` | `id` |
| `name` | `name` |
| `input`（对象） | `arguments`（`Json.stringify(input)`） |

```text
content = [text("I will check."), tool_use(...)]
        ↓
AiMessage.text()                     = "I will check."
AiMessage.hasToolExecutionRequests() = true
AiMessage.toolExecutionRequests()    = [ToolExecutionRequest(toolu_1, get_weather, {"city":"hangzhou"})]
```

> **注意**：只有 `tool_use` 没有 text 块时，`AiMessage.text()` 为 `null`；`thinking` / `image` / `document` / server tool block 当前不进入 `AiMessage`。

<br/>

## 3.7、ChatResponse 映射

| Anthropic 字段 | AgentForge 字段 |
|---|---|
| `content[*].text` | `ChatResponse.aiMessage().text()` |
| `content[*].tool_use` | `ChatResponse.aiMessage().toolExecutionRequests()` |
| `usage.input_tokens` | `TokenUsage.inputTokens()` |
| `usage.output_tokens` | `TokenUsage.outputTokens()` |
| `input + output` | `TokenUsage.totalTokens()` |
| `stop_reason` | `ChatResponse.finishReason()` |
| `id` / `model` / `type` | `metadata["id"] / ["model"] / ["type"]` |

## 3.8、stop_reason 映射

| Anthropic | AgentForge `FinishReason` |
|---|---|
| `end_turn` | `STOP` |
| `stop_sequence` | `STOP` |
| `max_tokens` | `LENGTH` |
| `tool_use` | `TOOL_EXECUTION` |
| 其他非空 | `OTHER` |
| `null` | `null` |

<br/>

## 3.9、流式实现

`AnthropicStreamingChatModel` 使用同一 Endpoint，强制 `stream=true`，`Accept: text/event-stream`；请求体映射与 Blocking 完全一致，统一由 `AnthropicProtocol` 生成。

`JdkHttpTransport` 只按行回调，不解析 SSE 语义；`AnthropicStreamState` 自行按 `event:` / `data:` 累积一帧，遇空行（或单行 `data:` 帧）时 dispatch：

| 事件 | AgentForge 行为 |
|---|---|
| `message_start` | 记录 `metadata["id"]/["model"]`，读 `usage.input_tokens` |
| `content_block_start` | `tool_use` 时按 `index` 建累加器，记 `id` / `name` / 初始 `input` |
| `content_block_delta` | `text_delta` 立即 `onPartialResponse(...)`；`input_json_delta` 追加到同 `index` 工具参数 |
| `content_block_stop` | 不处理，等整块结束 |
| `message_delta` | 读 `delta.stop_reason` 与 `usage.output_tokens` |
| `message_stop` | 由 EOF / 完成回调触发最终响应 |
| `error` | 包装为 `ModelException` 并 `onError(...)` |

工具调用聚合规则：

- 文本块与工具块可交错出现，互不影响；
- `arguments` 按到达顺序拼接原始 JSON 文本，不重新格式化；
- 若只有 `content_block_start` 没有 `input_json_delta`（`input` 直接给对象），使用 start 帧的 `input`；
- 无任何 `partial_json` 时，`arguments` 归一化为 `{}`；
- 与 LangChain4j 一致：流式中**不**暴露半成品工具调用，只在最终 `ChatResponse` 给出完整结果。

> **重点**：`temperature` / `top_p` 已被 Anthropic 官方标记为对较新模型 deprecated/受限；推荐默认不显式设置，按目标模型能力决定是否传入。Provider 后续可加入 model capability 校验，提前在客户端阻止无效参数。

---

## 四、实战代码

## 4.1、非流式

```java
AnthropicChatModel model =
        AnthropicChatModel.builder()
                .baseUrl("https://api.anthropic.com")
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .modelName("your-claude-model")
                .maxTokens(1024)
                .build();

ChatRequest request =
        ChatRequest.builder()
                .message(SystemMessage.from("You are a concise Java assistant."))
                .message(UserMessage.from("Explain volatile."))
                .build();

ChatResponse response = model.chat(request);
System.out.println(response.aiMessage().text());
```

**运行输出（模拟终端）**

```shell
$ curl -s https://api.anthropic.com/v1/messages -H "x-api-key: $ANTHROPIC_API_KEY" \
    -H "anthropic-version: 2023-06-01" \
    -d '{"model":"your-claude-model","max_tokens":1024,"system":"You are a concise Java assistant.","messages":[{"role":"user","content":"Explain volatile."}]}'

{"id":"msg_xxx","type":"message","role":"assistant",
 "content":[{"type":"text","text":"volatile provides visibility guarantees..."}],
 "stop_reason":"end_turn","usage":{"input_tokens":24,"output_tokens":40}}
```

<br/>

## 4.2、工具调用闭环（wire 示例）

```json
// assistant 发起
{"role":"assistant","content":[
  {"type":"text","text":"I will check."},
  {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"hangzhou"}}]}

// 工具结果回填
{"role":"user","content":[
  {"type":"tool_result","tool_use_id":"toolu_1","content":"{\"temperature\":22}"}]}
```

---

## 五、错误处理、限制与演进

## 5.1、本地参数校验

```text
modelName 必须非空
apiKey 必须非空
messages 中至少有一条非 system 的 user/assistant 类消息
```

只有 `SystemMessage` 时抛：

```text
IllegalArgumentException: Anthropic request requires at least one user/assistant message
```

<br/>

## 5.2、HTTP 错误

非 2xx → `new ModelException("Anthropic request failed with HTTP " + statusCode, statusCode, responseBody)`；IOException → `ModelException("Anthropic request failed", cause)`。上层可统一读取 `exception.statusCode()` / `exception.responseBody()`。

<br/>

## 5.3、当前未实现能力

已落地：Streaming/SSE、`tool_use` 强类型响应（含流式 `input_json_delta` 聚合）、`tool_result` 强类型请求、`tools/tool_choice` 请求映射、`UserMessage` 多 `Content` → `content[]`。

尚未完整支持：thinking / redacted thinking、image / document content blocks、Prompt Caching 强类型配置与 usage、Structured Output、server tools、extended usage 字段（cache token 等）、`stop_sequence` 写入 metadata、provider refusal / stop details 标准化。

<br/>

## 5.4、类结构与演进

Blocking 与 Streaming 共用同一套 wire mapping：

```text
AnthropicProtocol（package-private）
    ├── collectSystemMessages / serializeMessages
    ├── serializeTools / toolChoice
    ├── extractText / extractToolUses
        │
        ├── AnthropicChatModel          -> blocking Messages API
        └── AnthropicStreamingChatModel -> Messages API SSE
```

后续引入 Thinking / Multimodal 时，建议在 Core 扩展统一 Content 层级（`TextContent` 已存在）：`ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent`，使 Anthropic block protocol 与 OpenAI 多模态汇聚到同一上层模型。

---

## 六、总结

1. **两大差异**：`system` 顶层字段、`content` block 数组——都被 Provider 层吸收，Core 不变。
2. **工具调用**：`tool_use`（`input` 对象）→ `tool_result`（`user` 角色回填），强类型双向映射。
3. **流式**：`AnthropicStreamState` 自行解析带事件名的 SSE，`input_json_delta` 按 block `index` 聚合。
4. **必填约束**：`max_tokens` 默认 1024，保证直接可调用。
5. **风险提示**：新 Claude 模型对 `temperature` / `top_p` 收紧，默认不设置更安全。

---

## 参考资料

[1]. [Anthropic Messages API（英文）](https://platform.claude.com/docs/en/api/messages/create)

[2]. [Anthropic Messages API（中文）](https://platform.claude.com/docs/zh-CN/api/messages/create)

[3]. 相关内部文档：[Anthropic 底层协议快速理解](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议01、Anthropic底层协议快速理解)、[ChatModel 核心协议层设计](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
