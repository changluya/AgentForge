---
title: "Anthropic协议01、Anthropic底层协议快速理解"
date: 2026-10-05
tags: [AgentForge, 模型协议层, Anthropic]
---

# Anthropic 底层协议快速理解

> 更新日期：2026-10-05  
> 适用场景：理解 `agentforge-model-anthropic` 所对接的 Anthropic Messages API  
> 维护者：changlu

{/* truncate */}


一句话结论：Anthropic 对接的是 **Messages API**（`POST {baseUrl}/v1/messages`），它有两大差异点——**system 是顶层字段**、**content 是 block 数组**。本文只讲"协议本身长什么样"；AgentForge 的映射见《Anthropic协议02、AgentForge Anthropic接入核心实践》。

---

## 一、背景与问题引入

## 1.1、场景驱动：Claude 的 tool_use 与 content block

> 在为 AgentForge 接入 Claude 时，我们发现 Anthropic 与 OpenAI 的 wire 差异不小：鉴权头不同、system prompt 位置不同、content 不是字符串而是 block 数组，工具调用用 `tool_use` / `tool_result` 表达。

如果不先理解这些 wire 细节，就无法把响应正确归一化为 AgentForge 的统一消息。

<br/>

## 1.2、问题引导：Claude 的一次调用长什么样？

> **问题**：一次 Messages API 调用的请求头、`system`、`messages`、`content` block、流式 SSE 事件分别是什么？工具调用如何双向表达？

本文按"请求 → 响应 → 流式 → 工具调用"的顺序讲清。

<br/>

## 1.3、协议定位

```http
POST {baseUrl}/v1/messages
```

默认：

```text
baseUrl = https://api.anthropic.com
anthropic-version = 2023-06-01
→ 实际地址 https://api.anthropic.com/v1/messages
```

Messages API 是**无状态**消息协议：客户端提交当前会话历史，模型生成下一条 assistant message。

官方参考：

- https://platform.claude.com/docs/en/api/messages/create
- https://platform.claude.com/docs/zh-CN/api/messages/create

---

## 二、请求协议

## 2.1、Headers

```http
Content-Type: application/json
Accept: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

> **重点**：鉴权不是 `Authorization: Bearer`，而是 `x-api-key`；并且必须显式发送 API version Header。

<br/>

## 2.2、system 是顶层字段（关键差异）

Anthropic 的 system prompt **不使用 `system` role message**，而是顶层 `system` 字段：

```json
{
  "model": "your-claude-model",
  "max_tokens": 1024,
  "system": "You are a concise Java assistant.",
  "messages": [
    { "role": "user", "content": "Explain volatile." }
  ]
}
```

> **注意**：多条 system 提示需要由客户端拼接（AgentForge 以双换行 `\n\n` 拼接）。

<br/>

## 2.3、messages：role 与 content

| role | content | 说明 |
|---|---|---|
| `user` | 字符串或块数组 | 用户输入；工具结果也用 `user` 承载 |
| `assistant` | 字符串或块数组 | 模型回复；工具调用用 `tool_use` 块 |

对话历史通常是交替的 `user` / `assistant` turns；连续同 role 消息可能被服务端合并。

<br/>

## 2.4、content block 类型

```text
text
tool_use
tool_result
thinking / redacted_thinking
image / document
server tool blocks
...
```

<br/>

## 2.5、请求参数

| 字段 | 说明 |
|---|---|
| `model` | 模型 ID |
| `max_tokens` | **必填**，最大生成 token |
| `temperature` / `top_p` | 采样参数（新模型已 deprecated/受限，见下） |
| `stop_sequences` | 停止序列 |
| `tools[]` | `{"name","description","input_schema"}` |
| `tool_choice` | `{"type":"auto"}` / `{"type":"none"}` / `{"type":"any"}` / `{"type":"tool","name":X}` |

> **注意**：Anthropic 官方已将 `temperature`、`top_p` 标注为面向新模型的 deprecated/受限参数；新模型可能只接受兼容值，否则返回 400。是否传入需按目标模型能力决定。

---

## 三、响应协议

## 3.1、message 结构

```json
{
  "id": "msg_xxx",
  "type": "message",
  "role": "assistant",
  "model": "your-claude-model",
  "content": [
    { "type": "text", "text": "volatile provides visibility guarantees..." }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": { "input_tokens": 24, "output_tokens": 40 }
}
```

> **重点**：`content` 是 **block 数组**，不是单一字符串；一个响应里可能有多个 text 块，也可能有 `tool_use` 块。

<br/>

## 3.2、stop_reason

| 值 | 含义 |
|---|---|
| `end_turn` | 自然结束 |
| `stop_sequence` | 命中自定义停止序列 |
| `max_tokens` | 达到 max_tokens |
| `tool_use` | 需要执行工具 |

<br/>

## 3.3、usage

`input_tokens` / `output_tokens`；官方还可能返回 cache token、server tool usage 等扩展字段。

---

## 四、流式协议（SSE）

请求增加：

```json
{ "stream": true }
```

响应为**带事件名的 SSE**：

```text
event: message_start
data: {"type":"message_start","message":{...}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

event: message_stop
data: {"type":"message_stop"}
```

## 4.1、事件一览

| 事件 | 含义 |
|---|---|
| `message_start` | 开始，携带 message 元信息与 `usage.input_tokens` |
| `content_block_start` | 开始一个 content block（含 `tool_use` 的 `id` / `name` / 初始 `input`） |
| `content_block_delta` | 增量：`text_delta` 或 `input_json_delta` |
| `content_block_stop` | 块结束 |
| `message_delta` | 携带 `delta.stop_reason` 与 `usage.output_tokens` |
| `message_stop` | 消息结束 |
| `error` | 错误事件 |

<br/>

## 4.2、工具参数的流式聚合

`tool_use` 的参数以 `input_json_delta` 增量下发，按 content block `index` 归属：

```text
content_block_start (index=1, type=tool_use, id=toolu_1, name=get_weather, input={})
content_block_delta (index=1, input_json_delta: "{\"city\":")
content_block_delta (index=1, input_json_delta: "\"hangzhou\"}")
        │
        ▼ accumulate by content block index
ToolRequest(id=toolu_1, name=get_weather, arguments={"city":"hangzhou"})
```

> **注意**：文本块与工具块可在同一条流里交错出现；`partial_json` 按到达顺序拼接原始 JSON 文本，不重新格式化。

---

## 五、工具调用（Tool Use）wire 细节

## 5.1、assistant 发起调用（tool_use 块）

```json
{
  "role": "assistant",
  "content": [
    { "type": "text", "text": "I will check." },
    { "type": "tool_use", "id": "toolu_1", "name": "get_weather",
      "input": { "city": "hangzhou" } }
  ]
}
```

> **重点**：与 OpenAI 的 `arguments` 字符串不同，Anthropic 的 `input` 是**对象**。

<br/>

## 5.2、工具结果回填（tool_result 块，role=user）

```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_1", "content": "{\"temperature\":22}" }
  ]
}
```

`tool_use_id` 与发起时的 `tool_use.id` 对应。

---

## 六、完整调用演练：user prompt + tool use（curl）

场景：用户问 **"杭州今天天气怎么样？"**，并声明一个 `get_weather` 工具。下面从 curl 构造到返回，非流式 / 流式都演示一遍（含工具结果回填的第二轮）。

<br/>

## 6.1、声明 tools

```json
"tools": [
  {
    "name": "get_weather",
    "description": "查询指定城市的当前天气",
    "input_schema": {
      "type": "object",
      "properties": { "city": { "type": "string", "description": "城市名" } },
      "required": ["city"]
    }
  }
]
```

> **注意**：Anthropic 工具用 `input_schema`（不是 OpenAI 的 `parameters`）。

<br/>

## 6.2、非流式 · 第一轮：模型决定调用工具

```bash
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-latest",
    "max_tokens": 1024,
    "system": "你是一个简洁的助手。",
    "messages": [
      { "role": "user", "content": "杭州今天天气怎么样？" }
    ],
    "tools": [
      { "name": "get_weather",
        "description": "查询指定城市的当前天气",
        "input_schema": { "type": "object",
          "properties": { "city": { "type": "string", "description": "城市名" } },
          "required": ["city"] } }
    ],
    "tool_choice": { "type": "auto" }
  }'
```

**返回（注意 `stop_reason=tool_use`、`content` 含 `tool_use` 块）：**

```json
{
  "id": "msg_abc",
  "type": "message",
  "role": "assistant",
  "model": "claude-3-5-sonnet-latest",
  "content": [
    { "type": "text", "text": "我来查询一下杭州的天气。" },
    { "type": "tool_use", "id": "toolu_1", "name": "get_weather",
      "input": { "city": "杭州" } }
  ],
  "stop_reason": "tool_use",
  "stop_sequence": null,
  "usage": { "input_tokens": 88, "output_tokens": 35 }
}
```

> **重点**：`tool_use.input` 是**对象** `{"city":"杭州"}`（与 OpenAI 的 `arguments` 字符串不同）。

<br/>

## 6.3、非流式 · 第二轮：回填工具结果，得到最终答案

```bash
curl https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-latest",
    "max_tokens": 1024,
    "system": "你是一个简洁的助手。",
    "messages": [
      { "role": "user", "content": "杭州今天天气怎么样？" },
      { "role": "assistant", "content": [
          { "type": "text", "text": "我来查询一下杭州的天气。" },
          { "type": "tool_use", "id": "toolu_1", "name": "get_weather",
            "input": { "city": "杭州" } } ] },
      { "role": "user", "content": [
          { "type": "tool_result", "tool_use_id": "toolu_1",
            "content": "{\"temperature\":26,\"text\":\"晴\"}" } ] }
    ],
    "tools": [
      { "name": "get_weather", "description": "查询指定城市的当前天气",
        "input_schema": { "type": "object",
          "properties": { "city": { "type": "string" } }, "required": ["city"] } }
    ]
  }'
```

**返回（`stop_reason=end_turn`）：**

```json
{
  "id": "msg_def",
  "type": "message",
  "role": "assistant",
  "content": [ { "type": "text", "text": "杭州今天 26℃，天气晴。" } ],
  "stop_reason": "end_turn",
  "usage": { "input_tokens": 140, "output_tokens": 16 }
}
```

<br/>

## 6.4、流式 · 第一轮：`input_json_delta` 增量（`stream:true`）

```bash
curl -N https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet-latest",
    "max_tokens": 1024,
    "stream": true,
    "messages": [ { "role": "user", "content": "杭州今天天气怎么样？" } ],
    "tools": [
      { "name": "get_weather", "description": "查询指定城市的当前天气",
        "input_schema": { "type": "object",
          "properties": { "city": { "type": "string" } }, "required": ["city"] } }
    ],
    "tool_choice": { "type": "auto" }
  }'
```

**SSE（按到达顺序，注意 `event:` + `data:`）：**

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_abc","role":"assistant","content":[],"usage":{"input_tokens":88,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我来查询一下杭州的天气。"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"杭州\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":35}}

event: message_stop
data: {"type":"message_stop"}
```

**聚合**（按 content block `index` 归属；`partial_json` 拼接）：

```text
index=1 (tool_use) → id=toolu_1, name=get_weather, input={"city":"杭州"}
```

<br/>

## 6.5、流式 · 第二轮：回填后生成最终文本

请求体同 6.3，追加 `"stream": true`。

```text
event: message_start
data: {"type":"message_start","message":{"id":"msg_def","role":"assistant","content":[],"usage":{"input_tokens":140,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"杭州今天 26℃"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，天气晴。"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":16}}

event: message_stop
data: {"type":"message_stop"}
```

**聚合**：`text_delta` 顺序拼接 → `"杭州今天 26℃，天气晴。"`。

<br/>

## 6.6、演练要点小结

| 观测点 | 非流式 | 流式 |
|---|---|---|
| 工具参数 | `content[].tool_use.input`（对象） | `content_block_delta.input_json_delta.partial_json` 分片，按 block `index` 聚合 |
| 是否发工具 | `stop_reason == "tool_use"` | `message_delta.delta.stop_reason == "tool_use"` |
| 文本 | `content[].text` | `content_block_delta.text_delta.text` 逐段回调 |
| usage | 响应体 `usage` | `message_start`（input）+ `message_delta`（output） |
| 结束 | — | `event: message_stop` |

<br/>

## 6.7、真实测试验证

可直接运行仓库内脚本（需设置 API Key）：

```bash
export ANTHROPIC_API_KEY=sk-ant-...
bash docs/dev/backend/AgentForge核心模块设计原理/01、model模型协议层/chatmodel/anthropic/verify-anthropic-chat.sh
```

脚本依次执行：① 非流式工具调用请求；② 用 `jq` 打印 `tool_use` 块；③ 流式请求打印 SSE。可选环境变量 `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` 覆盖默认值。

> **注意**：真实调用会产生费用；请勿把 Key 写入脚本或提交到仓库。

---

## 七、总结

1. **Endpoint**：`POST {baseUrl}/v1/messages`；`x-api-key` + `anthropic-version` 鉴权。
2. **system**：顶层字段，不是 role message。
3. **content**：block 数组（`text` / `tool_use` / `tool_result` / …）。
4. **请求**：`model` + 必填 `max_tokens` + 采样/工具参数。
5. **响应**：`content[]` + `stop_reason` + `usage`。
6. **流式**：带事件名的 SSE；工具参数按 block `index` 聚合 `input_json_delta`。
7. **工具**：`tool_use`（`input` 为对象）→ `tool_result`（以 `user` 角色回填）。

> AgentForge 如何映射为统一类型，见《Anthropic协议02、AgentForge Anthropic接入核心实践》。

---

## 参考资料

[1]. [Anthropic Messages API（英文）](https://platform.claude.com/docs/en/api/messages/create)

[2]. [Anthropic Messages API（中文）](https://platform.claude.com/docs/zh-CN/api/messages/create)

[3]. [Server-Sent Events（MDN）](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

[4]. 相关内部文档：[AgentForge Anthropic 接入核心实践](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
