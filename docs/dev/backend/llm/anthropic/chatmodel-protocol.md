# Anthropic ChatModel 标准入参、出参协议

> 更新日期：2026-09-13  
> 适用模块：`agentforge-llm-anthropic`  
> 当前实现：`AnthropicChatModel`  
> Wire API：Anthropic Messages API  
> 维护者：changlu

## 1. 协议定位与当前实现边界

AgentForge 当前 Anthropic Provider 实现标准 **Messages API**：

```http
POST {baseUrl}/v1/messages
```

默认：

```text
baseUrl = https://api.anthropic.com
anthropic-version = 2023-06-01
```

因此默认实际地址：

```text
https://api.anthropic.com/v1/messages
```

Anthropic Messages API 是无状态消息协议：客户端提交当前会话历史，模型生成下一条 assistant message。AgentForge 当前负责把 Core 的 `ChatRequest` 翻译成 Anthropic Messages wire protocol，再把响应归一化为 `ChatResponse`。

官方参考：

- https://platform.claude.com/docs/en/api/messages/create
- https://platform.claude.com/docs/zh-CN/api/messages/create

### 1.1 AgentForge 模型构建参数

`AnthropicChatModel.Builder` 当前支持：

| Builder 参数 | 默认值 | 说明 |
|---|---:|---|
| `baseUrl` | `https://api.anthropic.com` | Anthropic API 根地址 |
| `apiKey` | `null` | 必填，最终写入 `x-api-key` |
| `anthropicVersion` | `2023-06-01` | 写入 `anthropic-version` Header |
| `modelName` | `null` | 模型 ID，请求前必须有值 |
| `temperature` | `null` | 当前仍会映射到 `temperature` |
| `maxTokens` | `1024` | 映射到必填 `max_tokens` |
| `topP` | `null` | 当前仍会映射到 `top_p` |
| `stopSequences` | `null` | 映射到 `stop_sequences` |
| `customParameter` | 空 | Provider 顶层扩展字段 |
| `customHeader` | 空 | 自定义 Header |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | 连接超时 |
| `readTimeoutMillis` | `60000` | 读取超时 |

**重要兼容提示：** Anthropic 当前官方文档已经将 `temperature`、`top_p` 标注为面向新模型的 deprecated/受限参数。新模型可能只接受兼容值，或直接对非兼容值返回 HTTP 400。因此 AgentForge Core 保留这些通用字段不代表所有 Anthropic 模型都适合设置它们；调用方应根据目标 Claude 模型能力决定是否传入。

## 2. 标准入参协议

### 2.1 AgentForge 统一入参

上层仍统一使用：

```java
ChatRequest {
    List<ChatMessage> messages;
    ChatRequestParameters parameters;
}
```

Provider 负责完成 Anthropic 特有的 `system` 顶层字段与 `messages` role 转换。

### 2.2 HTTP Headers

当前实现固定发送：

```http
Content-Type: application/json
Accept: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

之后追加 `customHeaders`。

与 OpenAI 不同，Anthropic 当前不是：

```http
Authorization: Bearer ...
```

而是：

```http
x-api-key: ...
```

并要求显式发送 API version Header。

### 2.3 SystemMessage 映射

Anthropic Messages API 的关键差异是：**system prompt 使用顶层 `system` 字段，而不是正常会话中的 `system` role message。**

AgentForge 当前会遍历所有消息：

```text
SystemMessage A
SystemMessage B
```

拼接为：

```text
A\n\nB
```

最终：

```json
{
  "system": "A\n\nB"
}
```

SystemMessage 不会再进入 `messages` 数组。

### 2.4 User / AI 消息映射

| AgentForge | Anthropic role | content |
|---|---|---|
| `UserMessage` | `user` | `message.text()` |
| `AiMessage` | `assistant` | `message.text()` |
| `SystemMessage` | 不进入 messages | 聚合到顶层 `system` |

示例：

```java
ChatRequest.builder()
        .message(SystemMessage.from("You are a Java expert."))
        .message(UserMessage.from("What is CAS?"))
        .message(AiMessage.from("CAS is an atomic primitive."))
        .message(UserMessage.from("Give me an example."))
        .build();
```

转换后：

```json
{
  "system": "You are a Java expert.",
  "messages": [
    {
      "role": "user",
      "content": "What is CAS?"
    },
    {
      "role": "assistant",
      "content": "CAS is an atomic primitive."
    },
    {
      "role": "user",
      "content": "Give me an example."
    }
  ]
}
```

Anthropic 官方说明，Messages API 的对话历史主要使用交替的 `user` / `assistant` turns；连续同 role 消息可能在服务端合并。

### 2.5 当前 ToolExecutionResultMessage / CustomMessage 行为

当前 `AnthropicChatModel.toAnthropicMessages(...)` 的 role 判断逻辑是：

```text
AI      -> assistant
其他非 SYSTEM -> user
```

因此现阶段：

- `ToolExecutionResultMessage` 会按普通 `user` 文本发送；
- `CustomMessage` 没有 `text()`，调用时会抛 `UnsupportedOperationException`。

这只是 release_1.x 当前实现行为，**不等于完整的 Anthropic Tool Result 标准协议**。Anthropic 原生 Tool Calling 使用 content block，例如 `tool_use` / `tool_result`，后续 Tool Calling 阶段应改成强类型映射，不能长期把工具结果降级成普通 user 文本。

### 2.6 参数映射

| AgentForge 参数 | Anthropic Messages 字段 | 当前行为 |
|---|---|---|
| `modelName` | `model` | 必填；为空本地失败 |
| `maxTokens` | `max_tokens` | Anthropic 必填；AgentForge 默认 1024 |
| `temperature` | `temperature` | 非空发送；新模型兼容性需注意 |
| `topP` | `top_p` | 非空发送；新模型兼容性需注意 |
| `stopSequences` | `stop_sequences` | 非空发送 |
| `SystemMessage` | `system` | 多条以双换行拼接 |
| `customParameters` | 顶层字段透传 | 标准字段最终覆盖同名 custom 值 |

Anthropic 官方要求 `max_tokens` 指定最大生成 Token 数，模型也可能在达到该最大值之前自然停止。

### 2.7 标准请求示例

AgentForge：

```java
ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("You are a concise Java assistant."))
        .message(UserMessage.from("Explain volatile."))
        .parameters(DefaultChatRequestParameters.builder()
                .modelName("your-claude-model")
                .maxTokens(1024)
                .stopSequences(Collections.singletonList("<END>"))
                .build())
        .build();
```

Wire Request：

```http
POST /v1/messages
Content-Type: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

```json
{
  "model": "your-claude-model",
  "max_tokens": 1024,
  "stop_sequences": ["<END>"],
  "system": "You are a concise Java assistant.",
  "messages": [
    {
      "role": "user",
      "content": "Explain volatile."
    }
  ]
}
```

## 3. 标准出参协议

### 3.1 Anthropic Message 关键响应结构

典型响应：

```json
{
  "id": "msg_xxx",
  "type": "message",
  "role": "assistant",
  "model": "your-claude-model",
  "content": [
    {
      "type": "text",
      "text": "volatile provides visibility guarantees..."
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 24,
    "output_tokens": 40
  }
}
```

Anthropic 的 `content` 是 content block 数组，而不是简单假设为单个字符串。官方协议还可能包含 tool、thinking、document 等不同 block 类型。

### 3.2 AgentForge 文本提取规则

当前实现遍历 `content` 数组，只提取：

```json
{
  "type": "text",
  "text": "..."
}
```

多个 text blocks 会按原顺序直接拼接。

例如：

```json
{
  "content": [
    {"type": "text", "text": "Hello "},
    {"type": "text", "text": "world"}
  ]
}
```

最终：

```text
AiMessage.text() = "Hello world"
```

非 text block 当前不会进入 `AiMessage`，这也是后续 Tool Calling / Thinking / Multimodal 需要扩展响应模型的原因。

### 3.3 ChatResponse 映射

| Anthropic 字段 | AgentForge 字段 |
|---|---|
| `content[*].text` | `ChatResponse.aiMessage().text()` |
| `usage.input_tokens` | `TokenUsage.inputTokens()` |
| `usage.output_tokens` | `TokenUsage.outputTokens()` |
| `input + output` | `TokenUsage.totalTokens()` |
| `stop_reason` | `ChatResponse.finishReason()` |
| `id` | `metadata["id"]` |
| `model` | `metadata["model"]` |
| `type` | `metadata["type"]` |

当前不会把 `stop_sequence`、cache token 统计、server tool usage 等扩展 usage 字段放入标准 TokenUsage。

### 3.4 stop_reason 映射

| Anthropic `stop_reason` | AgentForge `FinishReason` |
|---|---|
| `end_turn` | `STOP` |
| `stop_sequence` | `STOP` |
| `max_tokens` | `LENGTH` |
| `tool_use` | `TOOL_EXECUTION` |
| 其他非空值 | `OTHER` |
| `null` | `null` |

Anthropic 官方说明：自然结束通常返回 `end_turn`；命中自定义 `stop_sequences` 时返回 `stop_sequence`，并可在响应中返回匹配的 `stop_sequence`。

## 4. 协议特性与 AgentForge 适配原则

### 4.1 Anthropic Content Block

Anthropic Messages API 的 content 不是单一文本协议，而是 block protocol。当前官方协议可以出现多种块，例如：

```text
text
tool_use
tool_result
thinking
redacted_thinking
image
document
server tool blocks
...
```

release_1.x AgentForge 只完整消费 `text` block。

因此需要区分：

```text
Anthropic 官方协议能力
        ≠
AgentForge release_1.x 当前已实现子集
```

后续最合理的扩展不是继续把所有 block 压成字符串，而是在 Core 建立统一 Content hierarchy。

### 4.2 System Prompt

Anthropic 的 system prompt 采用顶层字段是 Provider 协议差异，不应该改变 AgentForge Core 中 `SystemMessage` 的统一语义。

也就是说：

```text
Core:
SystemMessage

OpenAI Adapter:
messages[].role = system

Anthropic Adapter:
top-level system
```

这正是 Provider Adapter 层存在的原因。

### 4.3 max_tokens

Anthropic Messages API 当前要求显式提供 `max_tokens`。为保证最简单调用可以直接执行，`AnthropicChatModel.Builder` 默认：

```text
maxTokens = 1024
```

所以即使用户没有在 `ChatRequest` 中设置 maxTokens，也会生成：

```json
{
  "max_tokens": 1024
}
```

### 4.4 temperature / top_p 的当前兼容风险

AgentForge Core 为多 Provider 通用性保留 `temperature` 与 `topP`；但是截至本文日期，Anthropic 官方文档已经对较新模型收紧这些采样参数：

- `temperature` 被标记 Deprecated；
- `top_p` 被标记 Deprecated；
- 新模型可能只接受兼容值，否则返回 400。

因此推荐：

```text
默认不显式设置 temperature/topP
        │
        ├── 目标模型确认支持 -> 再配置
        └── 目标模型不支持   -> 保持 null，不发送
```

Provider 后续也可以加入 model capability 校验，提前在客户端阻止无效参数。

## 5. 错误处理、限制与后续演进

### 5.1 本地参数校验

请求发出前当前检查：

```text
modelName 必须非空
apiKey 必须非空
messages 中至少要有一条非 system 的 user/assistant 类消息
```

如果只有 SystemMessage：

```text
IllegalArgumentException:
Anthropic request requires at least one user/assistant message
```

### 5.2 HTTP 错误

非 2xx 响应转换为：

```java
new LlmException(
    "Anthropic request failed with HTTP " + statusCode,
    statusCode,
    responseBody
)
```

IOException 转换为：

```text
LlmException("Anthropic request failed", cause)
```

这样上层可以统一读取：

```java
exception.statusCode();
exception.responseBody();
```

### 5.3 当前未实现能力

当前 `AnthropicChatModel` 尚未完整支持：

- Streaming / SSE；
- `tool_use` 强类型响应；
- `tool_result` 强类型请求；
- thinking / redacted thinking；
- image / document content blocks；
- Prompt Caching 强类型配置与 usage；
- Structured Output；
- server tools；
- extended usage 字段；
- `stop_sequence` 写入 `ChatResponse.metadata`；
- provider refusal / stop details 的标准化。

### 5.4 推荐后续类结构

在保持 Core 稳定的情况下，可以继续演进为：

```text
AnthropicChatModel
    -> blocking Messages API

AnthropicStreamingChatModel
    -> Messages API SSE
```

Tool Calling 完成后，建议把：

```text
ToolExecutionResultMessage
```

映射成 Anthropic 原生 `tool_result` block，而不是普通 user text。

更进一步，当多模态和 Thinking 进入 release_1.x 后半段时，再在 Core 引入统一：

```text
Content
├── TextContent
├── ImageContent
├── ToolUseContent
├── ToolResultContent
└── ProviderCustomContent
```

这样 Anthropic 的 block protocol 与 OpenAI 的多模态 / tool message 都可以汇聚到同一套 AgentForge 上层模型。
