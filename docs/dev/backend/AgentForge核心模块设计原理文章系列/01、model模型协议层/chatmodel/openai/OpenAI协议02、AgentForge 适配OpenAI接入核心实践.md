# AgentForge OpenAI 接入核心实践

> 更新日期：2026-10-05  
> 适用模块：`agentforge-model-openai`  
> 当前实现：`OpenAiChatModel`（Blocking）、`OpenAiStreamingChatModel`（SSE）  
> Wire API：OpenAI Chat Completions  
> 维护者：changlu

一句话结论：OpenAI Provider 把 AgentForge 统一的 `ChatRequest` / `ChatResponse` 与 **Chat Completions** wire 格式做双向映射；协议本身见《OpenAI协议01、OpenAI底层协议快速理解》，本文讲 AgentForge 的实现与边界。

---

# 一、背景与问题引入

## 1.1、场景驱动：一套 Core，适配多家模型

> AgentForge 的 Agent Runtime 只依赖 `ChatModel` / `StreamingChatModel` 接口。我们希望"换模型"只换一个 Provider 依赖，而不是改 Agent 代码。

于是 OpenAI Provider 的职责被限定为**协议翻译层**：

```text
Agent Runtime
     │  ChatRequest / ChatResponse（Provider 无关）
     ▼
OpenAiChatModel / OpenAiStreamingChatModel
     │  Chat Completions wire
     ▼
OpenAI / OpenAI-compatible 服务
```

<br/>

## 1.2、问题引导：统一类型如何落到 OpenAI wire？

> **问题**：AgentForge 的 `ChatMessage`、`ChatRequestParameters`、`ChatResponse` 分别怎么落到 OpenAI 的 `messages` / 请求字段 / 响应字段？工具调用如何双向映射？流式如何聚合？

本文逐项给出映射表与实现位置。

<br/>

## 1.3、实现边界

- Wire API：`POST {baseUrl}/chat/completions`（默认 `https://api.openai.com/v1`）；
- release_1.x 以 Chat Completions 为第一版统一协议，Responses API 采用"并存 Adapter"策略（见第六章）。

<br/>

## 1.4、Builder 参数

| Builder 参数 | 默认值 | 说明 |
|---|---|---|
| `baseUrl` | `https://api.openai.com/v1` | 也支持 OpenAI-compatible 服务 |
| `apiKey` | `null` | 非空时发送 `Authorization: Bearer ...` |
| `modelName` | `null` | 请求前必须有值 |
| `temperature` | `null` | 非空才发送 |
| `maxTokens` | `null` | 映射 `max_tokens` |
| `topP` | `null` | 映射 `top_p` |
| `stopSequences` | `null` | 映射 `stop` |
| `customParameter` | 空 | 透传 Provider 扩展顶层字段 |
| `customHeader` | 空 | 追加自定义 HTTP Header |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | 连接超时 |
| `readTimeoutMillis` | `60000` | 读取超时 |

> **重点**：`OpenAiStreamingChatModel` 复用同一套配置，并强制写入 `stream=true` 与 `stream_options.include_usage=true`。

---

# 二、核心概念

## 2.1、统一入参

```java
ChatRequest {
    List<ChatMessage> messages;
    ChatRequestParameters parameters;
}

ChatRequestParameters {
    String modelName();
    Double temperature();
    Integer maxTokens();
    Double topP();
    List<String> stopSequences();
    Map<String, Object> customParameters();
}
```

<br/>

## 2.2、统一出参

```java
ChatResponse {
    AiMessage aiMessage;          // text + toolExecutionRequests
    TokenUsage tokenUsage;
    FinishReason finishReason;
    Map<String, Object> metadata;
}
```

> **重点**：Provider 的职责就是把 wire 字段无损地落到这两个统一对象上。

---

# 三、实现思路与映射

## 3.1、HTTP Headers

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer ${apiKey}   # apiKey 非空时
```

之后追加 `customHeaders`；企业内部网关可用 `customHeader(...)` 增加租户、路由、trace 等。

<br/>

## 3.2、消息映射

| AgentForge | OpenAI role | content / 关键字段 |
|---|---|---|
| `SystemMessage` | `system` | `message.text()` |
| `UserMessage`（单文本） | `user` | `message.text()` |
| `UserMessage`（多 `Content`） | `user` | `content[]`，逐条 `TextContent` → `{"type":"text","text":...}` |
| `AiMessage`（纯文本） | `assistant` | `message.text()` |
| `AiMessage`（含工具调用） | `assistant` | `content`（可为 `null`）+ `tool_calls[]` |
| `ToolExecutionResultMessage` | `tool` | `tool_call_id = message.id()`，`content = message.text()` |

工具调用 + 结果回填的 wire 形态：

```json
{
  "messages": [
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        { "id": "call_1", "type": "function",
          "function": { "name": "getWeather", "arguments": "{\"city\":\"hangzhou\"}" } }
      ]
    },
    { "role": "tool", "tool_call_id": "call_1", "content": "{\"temperature\":22}" }
  ]
}
```

> **注意**：`CustomMessage` 当前未实现 wire mapping，遇到会抛 `IllegalArgumentException`。

<br/>

## 3.3、参数映射

| AgentForge 参数 | Chat Completions 字段 | 当前行为 |
|---|---|---|
| `modelName` | `model` | 必填；为空本地失败 |
| `temperature` | `temperature` | 非空才发送 |
| `maxTokens` | `max_tokens` | 非空才发送 |
| `topP` | `top_p` | 非空才发送 |
| `stopSequences` | `stop` | 非空才发送 |
| `tools` | `tools[]` | `{"type":"function","function":{name,description,parameters,strict}}` |
| `toolChoice` | `tool_choice` | `AUTO→"auto"`、`NONE→"none"`、`REQUIRED→"required"`、`SPECIFIC→{"type":"function","function":{"name":X}}` |
| `customParameters` | 顶层原样写入 | 通用字段写入后覆盖同名 custom 字段 |

构建顺序（标准字段拥有最终优先级）：

```text
1. payload.putAll(customParameters)
2. 写入 model / messages
3. 写入 temperature / max_tokens / top_p / stop
4. 写入 tools / tool_choice
```

> **重点**：即使 `customParameters` 写了另一个 `model`，最终仍会被 `modelName` 覆盖。

<br/>

## 3.4、响应映射

| OpenAI 字段 | AgentForge 字段 |
|---|---|
| `choices[0].message.content` | `ChatResponse.aiMessage().text()` |
| `choices[0].message.tool_calls[]` | `ChatResponse.aiMessage().toolExecutionRequests()` |
| `usage.prompt_tokens` | `TokenUsage.inputTokens()` |
| `usage.completion_tokens` | `TokenUsage.outputTokens()` |
| `usage.total_tokens` | `TokenUsage.totalTokens()` |
| `choices[0].finish_reason` | `ChatResponse.finishReason()` |
| `id` / `model` / `created` | `metadata["id"] / ["model"] / ["created"]` |

`tool_calls[]` 映射：`id → ToolExecutionRequest.id`、`function.name → name`、`function.arguments → arguments`（保留原始 JSON 字符串，不重新格式化）。

> **注意**：当 `tool_calls` 非空且 `content` 为空时，`AiMessage.text()` 返回 `null`；两者同时存在时，两者都会保留。

<br/>

## 3.5、finish_reason 映射

| OpenAI | AgentForge `FinishReason` |
|---|---|
| `stop` | `STOP` |
| `length` | `LENGTH` |
| `tool_calls` | `TOOL_EXECUTION` |
| `function_call` | `TOOL_EXECUTION` |
| `content_filter` | `CONTENT_FILTER` |
| 其他非空 | `OTHER` |
| `null` | `null` |

<br/>

## 3.6、响应结构异常

以下情况直接抛 `ModelException`（不静默返回空）：

```text
choices 不存在 / 为空 / choices[0].message 不存在
```

<br/>

## 3.7、流式实现

**请求**：同 Endpoint，强制 `stream=true` + `stream_options.include_usage=true`。

**SSE 解析**：忽略空行、`:` 注释、非 `data:` 行；每个 `data:` chunk 读取 `choices[0].delta.content`、`delta.tool_calls[]`、`finish_reason`。

- `delta.content` → 立即 `handler.onPartialResponse(...)`，同时本地 `StringBuilder` 聚合；
- `delta.tool_calls[]` → **按 `index` 分桶累加**（规则见协议篇 4.1），不回调半成品；
- 结束 → `handler.onCompleteResponse(response)`。

**最终响应**：

```java
ChatResponse.builder()
        .aiMessage(AiMessage.from(fullText, toolExecutionRequests))
        .finishReason(finishReason)
        .tokenUsage(tokenUsage)
        .metadata(metadata)
        .build();
```

> **重点**：最终 usage chunk（`choices=[]`）由"先读 usage、再判断 choices 是否为空"处理；流中断时最终 usage 可能缺失，`ChatResponse.tokenUsage()` 不保证一定存在。

---

# 四、实战代码

## 4.1、非流式

```java
OpenAiChatModel model =
        OpenAiChatModel.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("your-model")
                .temperature(0.2)
                .maxTokens(1024)
                .build();

ChatRequest request =
        ChatRequest.builder()
                .message(SystemMessage.from("You are a concise Java assistant."))
                .message(UserMessage.from("What is CAS?"))
                .build();

ChatResponse response = model.chat(request);
System.out.println(response.aiMessage().text());
```

**运行输出（模拟终端）**

```shell
$ curl -s https://api.openai.com/v1/chat/completions -H "Authorization: Bearer $OPENAI_API_KEY" \
    -d '{"model":"your-model","messages":[{"role":"system","content":"..."},{"role":"user","content":"What is CAS?"}]}'

{"choices":[{"index":0,"message":{"role":"assistant","content":"CAS means Compare-And-Swap."},"finish_reason":"stop"}],
 "usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}
```

<br/>

## 4.2、流式

```java
OpenAiStreamingChatModel streaming =
        OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.openai.com/v1")
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("your-model")
                .build();

streaming.chat(request, new StreamingChatResponseHandler() {
    @Override public void onPartialResponse(String partial) { System.out.print(partial); }
    @Override public void onCompleteResponse(ChatResponse response) { System.out.println(); }
    @Override public void onError(Throwable error) { error.printStackTrace(); }
});
```

**运行输出（模拟终端）**

```shell
CAS means Compare-And-Swap.
```

<br/>

## 4.3、OpenAI-compatible 服务

```java
OpenAiChatModel model =
        OpenAiChatModel.builder()
                .baseUrl("https://example.com/v1")
                .apiKey("...")
                .modelName("provider-model")
                .build();
```

---

# 五、兼容性、错误与边界

## 5.1、HTTP 错误

非 2xx → `new ModelException("OpenAI request failed with HTTP " + statusCode, statusCode, responseBody)`；网络层 IOException → `ModelException("OpenAI request failed", cause)`。流式非 2xx 会累积 body 并通过 `handler.onError(...)` 返回。

<br/>

## 5.2、协议差距（release_1.x 未映射）

`developer` role、图片 / 音频多模态 `content`、流式 `onPartialToolCall`、refusal、logprobs、structured outputs、audio、provider reasoning（`AiMessage.thinking()` 字段位已留未接线）、多 `choices`。

> `customParameters` 可临时透传请求字段；但若返回结构需要框架理解，仍须正式扩展 Core / Provider 类型。

---

# 六、总结与演进

已落地：`tool` role 的 `tool_call_id` 请求映射、`tools/tool_choice`（含 `SPECIFIC`）、`tool_calls` 非流式解析与流式按 `index` 聚合、`UserMessage` 多 `Content` → `content[]`。

OpenAI 官方建议新应用优先 Responses API，因此后续采用**并存 Adapter**：`OpenAiChatModel → /chat/completions`、`OpenAiStreamingChatModel → /chat/completions + SSE`，未来新增 `OpenAiResponsesModel → /responses`。上层仍只依赖 Core 接口，协议迁移不侵入 Agent Runtime。

---

# 参考资料

[1]. [OpenAI Chat Completions API（官方参考）](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

[2]. [OpenAI 文本生成指南](https://developers.openai.com/api/docs/guides/text)

[3]. 相关内部文档：[OpenAI 底层协议快速理解](OpenAI%E5%8D%8F%E8%AE%AE01%E3%80%81OpenAI%E5%BA%95%E5%B1%82%E5%8D%8F%E8%AE%AE%E5%BF%AB%E9%80%9F%E7%90%86%E8%A7%A3.md)、[ChatModel 核心协议层设计](../../ChatModel%E5%8E%9F%E7%90%8601%E3%80%81ChatModel%20%E6%A0%B8%E5%BF%83%E5%8D%8F%E8%AE%AE%E5%B1%82%E8%AE%BE%E8%AE%A1%EF%BC%9A%E7%BB%9F%E4%B8%80%20API%20%E5%A5%91%E7%BA%A6%E5%B0%81%E8%A3%85%E4%B8%8E%E5%A4%9A%E5%8D%8F%E8%AE%AE%E6%89%A9%E5%B1%95%E5%AE%9E%E7%8E%B0.md)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
