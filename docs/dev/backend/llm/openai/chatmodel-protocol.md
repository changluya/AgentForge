# OpenAI ChatModel 标准入参、出参协议

> 更新日期：2026-09-13  
> 适用模块：`agentforge-llm-openai`  
> 当前实现：`OpenAiChatModel`、`OpenAiStreamingChatModel`  
> 当前 Wire API：OpenAI Chat Completions  
> 维护者：changlu

## 1. 协议定位与当前实现边界

AgentForge 当前 OpenAI Provider 实现的是 **Chat Completions 协议**：

```http
POST {baseUrl}/chat/completions
```

默认：

```text
baseUrl = https://api.openai.com/v1
```

因此默认实际地址为：

```text
https://api.openai.com/v1/chat/completions
```

OpenAI 当前官方对于新的文本生成应用更推荐 Responses API；但 Chat Completions 仍是明确存在的 API，并且大量 OpenAI-compatible 模型服务继续兼容该协议。AgentForge release_1.x 继续以 Chat Completions 作为第一版统一接入协议，后续可以在不修改 Core 的前提下增加独立 Responses API Adapter。

官方参考：

- https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
- https://developers.openai.com/api/docs/guides/text

### 1.1 AgentForge 模型构建参数

`OpenAiChatModel.Builder` 当前支持：

| Builder 参数 | 默认值 | 说明 |
|---|---:|---|
| `baseUrl` | `https://api.openai.com/v1` | API 根地址，也支持 OpenAI-compatible 服务 |
| `apiKey` | `null` | 非空时发送 `Authorization: Bearer ...` |
| `modelName` | `null` | 模型名，请求前必须有值 |
| `temperature` | `null` | 采样参数 |
| `maxTokens` | `null` | 当前映射为 `max_tokens` |
| `topP` | `null` | 当前映射为 `top_p` |
| `stopSequences` | `null` | 当前映射为 `stop` |
| `customParameter` | 空 | 透传 Provider 扩展请求字段 |
| `customHeader` | 空 | 追加自定义 HTTP Header |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | 连接超时 |
| `readTimeoutMillis` | `60000` | 读取超时 |

`OpenAiStreamingChatModel` 使用相同的基础配置，但会强制写入：

```json
{
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

## 2. 标准入参协议

### 2.1 AgentForge 统一入参

上层统一传入：

```java
ChatRequest {
    List<ChatMessage> messages;
    ChatRequestParameters parameters;
}
```

标准参数：

```java
ChatRequestParameters {
    String modelName();
    Double temperature();
    Integer maxTokens();
    Double topP();
    List<String> stopSequences();
    Map<String, Object> customParameters();
}
```

Provider 最终生成 OpenAI JSON。

### 2.2 HTTP Headers

当前实现发送：

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer ${apiKey}   # apiKey 非空时
```

此外追加：

```text
customHeaders
```

因此企业内部 OpenAI-compatible Gateway 可以通过 `customHeader(...)` 增加租户、路由、trace 等 Header。

### 2.3 消息映射

release_1.x 当前支持三种标准文本消息：

| AgentForge | OpenAI role | content |
|---|---|---|
| `SystemMessage` | `system` | `message.text()` |
| `UserMessage` | `user` | `message.text()` |
| `AiMessage` | `assistant` | `message.text()` |

例如：

```java
ChatRequest.builder()
        .message(SystemMessage.from("You are a Java expert."))
        .message(UserMessage.from("Explain volatile."))
        .build();
```

转换为：

```json
{
  "messages": [
    {
      "role": "system",
      "content": "You are a Java expert."
    },
    {
      "role": "user",
      "content": "Explain volatile."
    }
  ]
}
```

当前 `OpenAiChatModel.toOpenAiRole(...)` 对以下消息**尚未实现 wire mapping**：

```text
ToolExecutionResultMessage
CustomMessage
```

遇到这些类型会抛出：

```text
IllegalArgumentException: Unsupported message type: ...
```

这意味着 Core 已经为 Tool Result 建模，但 OpenAI Tool Calling 的完整发送协议属于后续阶段。

### 2.4 参数映射

| AgentForge 参数 | Chat Completions 字段 | 当前行为 |
|---|---|---|
| `modelName` | `model` | 必填；为空时本地直接失败 |
| `temperature` | `temperature` | 非空才发送 |
| `maxTokens` | `max_tokens` | 非空才发送 |
| `topP` | `top_p` | 非空才发送 |
| `stopSequences` | `stop` | 非空才发送 |
| `customParameters` | 原样顶层写入 | 通用字段写入后会覆盖同名 custom 字段 |

构建顺序是：

```text
1. payload.putAll(customParameters)
2. 写入 model/messages
3. 写入 temperature/max_tokens/top_p/stop
```

因此标准字段拥有最终优先级。例如 `customParameters` 中即使写入了另一个 `model`，最终仍会被 `modelName` 覆盖。

### 2.5 标准非流式请求示例

AgentForge：

```java
ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("You are a concise Java assistant."))
        .message(UserMessage.from("What is CAS?"))
        .parameters(DefaultChatRequestParameters.builder()
                .modelName("your-model")
                .temperature(0.2)
                .maxTokens(1024)
                .topP(0.9)
                .build())
        .build();
```

Wire Request：

```http
POST /v1/chat/completions
Authorization: Bearer ${OPENAI_API_KEY}
Content-Type: application/json
```

```json
{
  "model": "your-model",
  "messages": [
    {
      "role": "system",
      "content": "You are a concise Java assistant."
    },
    {
      "role": "user",
      "content": "What is CAS?"
    }
  ],
  "temperature": 0.2,
  "max_tokens": 1024,
  "top_p": 0.9
}
```

## 3. 标准出参协议

### 3.1 OpenAI Chat Completion 关键响应结构

典型非流式响应：

```json
{
  "id": "chatcmpl_xxx",
  "object": "chat.completion",
  "created": 1780000000,
  "model": "your-model",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "CAS means Compare-And-Swap."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 10,
    "total_tokens": 30
  }
}
```

当前 AgentForge 只消费 `choices[0]`，不会返回多 choice。

### 3.2 ChatResponse 映射

Wire Response 转换为：

```java
ChatResponse {
    aiMessage = AiMessage(...);
    tokenUsage = TokenUsage(...);
    finishReason = FinishReason...;
    metadata = {...};
}
```

映射表：

| OpenAI 字段 | AgentForge 字段 |
|---|---|
| `choices[0].message.content` | `ChatResponse.aiMessage().text()` |
| `usage.prompt_tokens` | `TokenUsage.inputTokens()` |
| `usage.completion_tokens` | `TokenUsage.outputTokens()` |
| `usage.total_tokens` | `TokenUsage.totalTokens()` |
| `choices[0].finish_reason` | `ChatResponse.finishReason()` |
| `id` | `metadata["id"]` |
| `model` | `metadata["model"]` |
| `created` | `metadata["created"]` |

如果 `content` 是 String，直接读取；如果 Provider 返回 content blocks 数组，当前实现会遍历 block，并拼接其中的 `text` 字段。

### 3.3 finish_reason 映射

| OpenAI `finish_reason` | AgentForge `FinishReason` |
|---|---|
| `stop` | `STOP` |
| `length` | `LENGTH` |
| `tool_calls` | `TOOL_EXECUTION` |
| `function_call` | `TOOL_EXECUTION` |
| `content_filter` | `CONTENT_FILTER` |
| 其他非空值 | `OTHER` |
| `null` | `null` |

OpenAI 官方当前响应中仍定义 `stop / length / tool_calls / content_filter / function_call(deprecated)` 等结束原因。

### 3.4 响应结构异常

当前以下情况会直接抛 `LlmException`：

```text
choices 不存在
choices 为空
choices[0].message 不存在
```

目的不是静默返回空内容，而是让上层明确识别 Provider 响应协议异常。

## 4. StreamingChatModel 协议

### 4.1 请求

`OpenAiStreamingChatModel` 使用相同 Endpoint，但强制：

```json
{
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

OpenAI Chat Completions 流式响应基于 SSE，每个 chunk 形态通常为：

```text
data: {json}

data: {json}

data: [DONE]
```

当前实现忽略：

```text
空行
SSE comment (:...)
非 data: 行
```

### 4.2 Delta 文本

每个 JSON chunk 当前读取：

```text
choices[0].delta.content
```

并立即回调：

```java
handler.onPartialResponse(partial);
```

同时在本地 `StringBuilder` 聚合完整文本。

### 4.3 最终响应

流结束后，Provider 构造标准：

```java
ChatResponse.builder()
        .aiMessage(AiMessage.from(fullText))
        .finishReason(finishReason)
        .tokenUsage(tokenUsage)
        .metadata(metadata)
        .build();
```

然后：

```java
handler.onCompleteResponse(response);
```

OpenAI 官方规定，当 `stream_options.include_usage=true` 时，最终 `data: [DONE]` 之前可以出现一个 `choices=[]` 且携带整体 `usage` 的额外 chunk；当前实现能够处理这种结构，因为会先读取 usage，再判断 choices 是否为空。

如果流中断，则官方也明确说明最终 usage chunk 可能无法收到，因此 `ChatResponse.tokenUsage()` 在异常流场景不能被假设一定存在。

## 5. 兼容性、错误与后续演进

### 5.1 OpenAI-compatible 服务

由于 `baseUrl` 可配置，当前实现也适合兼容 `/chat/completions` 的第三方服务：

```java
OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://example.com/v1")
        .apiKey("...")
        .modelName("provider-model")
        .build();
```

兼容前提是服务至少支持当前 AgentForge 使用的字段和返回结构。

### 5.2 HTTP 错误

非 2xx 响应统一转换为：

```java
new LlmException(
    "OpenAI request failed with HTTP " + statusCode,
    statusCode,
    responseBody
)
```

网络层 IOException 转换为：

```text
LlmException("OpenAI request failed", cause)
```

流式 HTTP 非 2xx 会累积响应 body 并通过 `handler.onError(...)` 返回。

### 5.3 当前协议差距

release_1.x 当前没有完整映射以下 Chat Completions 能力：

- `developer` role；
- `tool` role；
- `tools` / `tool_choice` 强类型对象；
- `tool_calls` 响应强类型解析；
- multimodal content；
- refusal；
- logprobs；
- structured outputs；
- audio；
- provider reasoning 参数；
- 多 `choices`。

`customParameters` 可以临时透传部分请求字段，但如果返回结构需要框架理解，就仍然需要正式扩展 Core / Provider 类型。

### 5.4 OpenAI 新 API 的演进策略

OpenAI 官方当前建议新的文本生成应用优先使用 Responses API。因此 AgentForge 后续建议采用“并存 Adapter”而不是直接破坏当前类：

```text
OpenAiChatModel
    -> /chat/completions

OpenAiStreamingChatModel
    -> /chat/completions + SSE

未来：OpenAiResponsesModel
    -> /responses
```

上层依旧只依赖 Core 接口，协议迁移不应该侵入 Agent Runtime。
