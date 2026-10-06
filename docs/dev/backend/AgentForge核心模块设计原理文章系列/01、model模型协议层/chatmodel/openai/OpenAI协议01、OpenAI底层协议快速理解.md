# OpenAI 底层协议快速理解

> 更新日期：2026-10-05  
> 适用场景：理解 `agentforge-model-openai` 所对接的 OpenAI Chat Completions wire protocol  
> 维护者：changlu

一句话结论：AgentForge 的 OpenAI Provider 对接的是 **Chat Completions**（`POST {baseUrl}/chat/completions`）。本文只讲"协议本身长什么样"；AgentForge 如何把它映射成统一 `ChatRequest` / `ChatResponse`，见《OpenAI协议02、AgentForge OpenAI接入核心实践》。

---

# 一、背景与问题引入

## 1.1、场景驱动：为什么第一版选 Chat Completions

> 在为 AgentForge 统一接入多家大模型时，我们遇到一个现实问题：OpenAI 官方一边推荐新的 **Responses API**，一边又有大量 OpenAI-compatible 服务（自建网关、国产模型）只兼容 `/chat/completions`。

因此 AgentForge release_1.x 选择 **Chat Completions** 作为第一版统一接入协议：它仍是明确存在的 API，且兼容面最广。

<br/>

## 1.2、问题引导：一次对话调用到底发了什么？

> **问题**：当 Agent 调用一次 OpenAI 模型，请求体、响应体、流式 SSE 分别长什么样？工具调用（function calling）在 wire 上如何表达与回填？

本文按"请求 → 响应 → 流式 → 工具调用"的顺序把协议讲清。

<br/>

## 1.3、协议定位

```http
POST {baseUrl}/chat/completions
```

默认：

```text
baseUrl = https://api.openai.com/v1
→ 实际地址 https://api.openai.com/v1/chat/completions
```

官方参考：

- https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
- https://developers.openai.com/api/docs/guides/text

---

# 二、请求协议

## 2.1、Headers

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer ${apiKey}
```

> **重点**：鉴权用标准 `Authorization: Bearer`；企业网关可追加自定义 Header（trace / 租户 / 路由）。

<br/>

## 2.2、请求体：messages（核心）

| role | 关键字段 | 含义 |
|---|---|---|
| `system` | `content` | 系统提示 |
| `user` | `content` 或 `content[]` | 用户输入（多模态时为数组） |
| `assistant` | `content` / `tool_calls[]` | 模型回复，可携带工具调用 |
| `tool` | `tool_call_id` + `content` | 工具执行结果回填 |

纯文本示例：

```json
{
  "model": "your-model",
  "messages": [
    { "role": "system", "content": "You are a concise Java assistant." },
    { "role": "user", "content": "What is CAS?" }
  ]
}
```

<br/>

## 2.3、请求体：采样与工具参数

| 字段 | 说明 |
|---|---|
| `temperature` | 采样温度 |
| `max_tokens` | 最大生成 token |
| `top_p` | 核采样 |
| `stop` | 停止序列 |
| `tools[]` | 工具声明：`{"type":"function","function":{name,description,parameters,strict}}` |
| `tool_choice` | `"auto"` / `"none"` / `"required"` / `{"type":"function","function":{"name":X}}` |

<br/>

## 2.4、完整非流式请求示例

```json
{
  "model": "your-model",
  "messages": [
    { "role": "system", "content": "You are a concise Java assistant." },
    { "role": "user", "content": "What is CAS?" }
  ],
  "temperature": 0.2,
  "max_tokens": 1024,
  "top_p": 0.9
}
```

---

# 三、响应协议

## 3.1、非流式 chat.completion

```json
{
  "id": "chatcmpl_xxx",
  "object": "chat.completion",
  "created": 1780000000,
  "model": "your-model",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "CAS means Compare-And-Swap." },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30 }
}
```

> **注意**：`choices` 是数组，消费方通常只取 `choices[0]`。

<br/>

## 3.2、finish_reason

| 值 | 含义 |
|---|---|
| `stop` | 正常结束 |
| `length` | 达到 max_tokens |
| `tool_calls` | 需要执行工具 |
| `function_call` | 旧字段（deprecated） |
| `content_filter` | 内容被过滤 |

---

# 四、流式协议（SSE）

请求时增加：

```json
{ "stream": true, "stream_options": { "include_usage": true } }
```

响应为 SSE：

```text
data: {"choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}

data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":2,"total_tokens":22}}

data: [DONE]
```

> **重点**：`stream_options.include_usage=true` 时，`[DONE]` 之前会出现一个 `choices=[]` 且携带整体 `usage` 的额外 chunk；解析时应先读 usage，再判断 choices 是否为空。

<br/>

## 4.1、工具调用的流式增量（按 index 聚合）

`delta.tool_calls[]` 是分片下发的，必须**按 `index` 聚合**：

```text
chunk: {"index":0,"id":"call_1","function":{"name":"getWeather","arguments":""}}
chunk: {"index":0,"function":{"arguments":"{\"city\":"}}
chunk: {"index":0,"function":{"arguments":"\"hangzhou\"}"}}
        │
        ▼ merge by index
ToolRequest(id=call_1, name=getWeather, arguments={"city":"hangzhou"})
```

| delta 字段 | 聚合方式 |
|---|---|
| `index` | 分桶键 |
| `id` | 覆盖式写入当前桶（首个 chunk 携带完整 id） |
| `function.name` | 追加 |
| `function.arguments` | 追加原文，保留 JSON 文本 |

> **注意**：部分兼容网关不下发 `index`，此时以"出现一个新的非空 `id`"作为新一次调用的起点（fallback 计数器分桶），避免把同一次调用的参数增量错拆成多次调用。

---

# 五、工具调用（Function Calling）wire 细节

## 5.1、assistant 发起调用

```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_1",
      "type": "function",
      "function": { "name": "getWeather", "arguments": "{\"city\":\"hangzhou\"}" }
    }
  ]
}
```

> **重点**：`arguments` 是**字符串**（JSON 文本），不是对象——这是后续 AgentForge 归一化与再解析的关键。

<br/>

## 5.2、tool 结果回填

```json
{ "role": "tool", "tool_call_id": "call_1", "content": "{\"temperature\":22}" }
```

`tool_call_id` 必须与发起时的 `id` 一一对应。

<br/>

## 5.3、多工具调用

一次响应可返回多个 `tool_calls`，需逐个执行并分别以 `tool` role 回填，`id` 一一对应。

---

# 六、完整调用演练：user prompt + function calls（curl）

场景：用户问 **"杭州今天天气怎么样？"**，并声明一个 `get_weather` 工具。下面从 curl 构造到返回，非流式 / 流式都演示一遍（含工具结果回填的第二轮）。

<br/>

## 6.1、声明 tools

```json
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
]
```

<br/>

## 6.2、非流式 · 第一轮：模型决定调用工具

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      { "role": "user", "content": "杭州今天天气怎么样？" }
    ],
    "tools": [
      { "type": "function", "function": {
          "name": "get_weather",
          "description": "查询指定城市的当前天气",
          "parameters": { "type": "object",
            "properties": { "city": { "type": "string", "description": "城市名" } },
            "required": ["city"] } } }
    ],
    "tool_choice": "auto"
  }'
```

**返回（注意 `finish_reason=tool_calls`、`content=null`）：**

```json
{
  "id": "chatcmpl_abc",
  "object": "chat.completion",
  "created": 1780000000,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_1",
            "type": "function",
            "function": { "name": "get_weather", "arguments": "{\"city\":\"杭州\"}" }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": { "prompt_tokens": 62, "completion_tokens": 15, "total_tokens": 77 }
}
```

> **重点**：`arguments` 是字符串 `"{\"city\":\"杭州\"}"`，本地解析后执行 `get_weather("杭州")`。

<br/>

## 6.3、非流式 · 第二轮：回填工具结果，得到最终答案

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [
      { "role": "user", "content": "杭州今天天气怎么样？" },
      { "role": "assistant", "content": null,
        "tool_calls": [
          { "id": "call_1", "type": "function",
            "function": { "name": "get_weather", "arguments": "{\"city\":\"杭州\"}" } } ] },
      { "role": "tool", "tool_call_id": "call_1",
        "content": "{\"temperature\":26,\"text\":\"晴\"}" }
    ]
  }'
```

**返回（`finish_reason=stop`）：**

```json
{
  "id": "chatcmpl_def",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "杭州今天 26℃，天气晴。" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 85, "completion_tokens": 12, "total_tokens": 97 }
}
```

<br/>

## 6.4、流式 · 第一轮：tool_calls 增量（`stream:true`）

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "stream": true,
    "stream_options": { "include_usage": true },
    "messages": [ { "role": "user", "content": "杭州今天天气怎么样？" } ],
    "tools": [
      { "type": "function", "function": {
          "name": "get_weather", "description": "查询指定城市的当前天气",
          "parameters": { "type": "object",
            "properties": { "city": { "type": "string" } }, "required": ["city"] } } }
    ],
    "tool_choice": "auto"
  }'
```

**SSE（按到达顺序）：**

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"杭州\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: {"choices":[],"usage":{"prompt_tokens":62,"completion_tokens":15,"total_tokens":77}}

data: [DONE]
```

**聚合**（按 `tool_calls[].index` 分桶；`id` 覆盖、`name`/`arguments` 追加）：

```text
index=0 → id=call_1, name=get_weather, arguments={"city":"杭州"}
```

<br/>

## 6.5、流式 · 第二轮：回填后生成最终文本

请求体同 6.3，追加 `"stream": true, "stream_options": {"include_usage": true}`。

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"杭州"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"今天 26℃"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"，天气晴。"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":85,"completion_tokens":12,"total_tokens":97}}

data: [DONE]
```

**聚合**：`delta.content` 顺序拼接 → `"杭州今天 26℃，天气晴。"`。

<br/>

## 6.6、演练要点小结

| 观测点 | 非流式 | 流式 |
|---|---|---|
| 工具参数 | `message.tool_calls[].function.arguments`（字符串） | `delta.tool_calls[].function.arguments` 分片，按 `index` 聚合 |
| 是否发工具 | `finish_reason == "tool_calls"` | 最后一个 chunk 的 `finish_reason == "tool_calls"` |
| 文本 | `message.content` | `delta.content` 逐段回调 |
| usage | 响应体 `usage` | 末尾 `choices=[]` 的 usage chunk |
| 结束 | — | `data: [DONE]` |

<br/>

## 6.7、真实测试验证

可直接运行仓库内脚本（需设置 API Key）：

```bash
export OPENAI_API_KEY=sk-...
bash docs/dev/backend/AgentForge核心模块设计原理/01、model模型协议层/chatmodel/openai/verify-openai-chat.sh
```

脚本依次执行：① 非流式工具调用请求；② 用 `jq` 打印 `tool_calls`；③ 流式请求打印 SSE。可选环境变量 `OPENAI_BASE_URL` / `OPENAI_MODEL` 覆盖默认值。

> **注意**：真实调用会产生费用；请勿把 Key 写入脚本或提交到仓库。

---

# 七、总结

1. **Endpoint**：`POST {baseUrl}/chat/completions`，`Authorization: Bearer` 鉴权。
2. **请求**：`messages`（system / user / assistant / tool）+ 采样参数 + `tools` / `tool_choice`。
3. **响应**：`choices[0].message`（`content` 或 `tool_calls`）+ `usage` + `finish_reason`。
4. **流式**：SSE `data:` chunk；`delta.content` 文本、`delta.tool_calls` 按 `index` 聚合；`[DONE]` 结束。
5. **工具**：`assistant.tool_calls`（`arguments` 为字符串）→ `tool` role 按 `tool_call_id` 回填。

> AgentForge 如何把这些字段映射为统一类型，见《OpenAI协议02、AgentForge OpenAI接入核心实践》。

---

# 参考资料

[1]. [OpenAI Chat Completions API（官方参考）](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

[2]. [OpenAI 文本生成指南](https://developers.openai.com/api/docs/guides/text)

[3]. [Server-Sent Events（MDN）](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

[4]. 相关内部文档：[AgentForge OpenAI 接入核心实践](OpenAI%E5%8D%8F%E8%AE%AE02%E3%80%81AgentForge%20OpenAI%E6%8E%A5%E5%85%A5%E6%A0%B8%E5%BF%83%E5%AE%9E%E8%B7%B5.md)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
