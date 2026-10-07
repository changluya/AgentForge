---
title: "Anthropic Protocol 01, A Quick Understanding of the Anthropic Low-Level Protocol"
date: 2026-10-05
tags: [AgentForge, Model Protocol Layer, Anthropic]
---

# A Quick Understanding of the Anthropic Low-Level Protocol

> Updated: 2026-10-05  
> Applicable scenario: Understanding the Anthropic Messages API that `agentforge-model-anthropic` connects to  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: Anthropic connects to the **Messages API** (`POST {baseUrl}/v1/messages`), and it has two major differences — **system is a top-level field** and **content is a block array**. This article only covers "what the protocol itself looks like"; for AgentForge's mapping, see "Anthropic Protocol 02, Core Practices for AgentForge's Anthropic Integration".

---

## I. Background and Problem Introduction

## 1.1, Scenario-Driven: Claude's tool_use and content block

> When integrating Claude for AgentForge, we found that Anthropic and OpenAI differ considerably at the wire level: different auth headers, a different location for the system prompt, content being a block array rather than a string, and tool calls expressed via `tool_use` / `tool_result`.

Without first understanding these wire details, we cannot correctly normalize responses into AgentForge's unified messages.

<br/>

## 1.2, Problem Guidance: What does a single Claude call look like?

> **Question**: For a single Messages API call, what are the request headers, `system`, `messages`, `content` blocks, and streaming SSE events? How are tool calls expressed in both directions?

This article explains it in the order of "request → response → streaming → tool calls".

<br/>

## 1.3, Protocol Positioning

```http
POST {baseUrl}/v1/messages
```

Default:

```text
baseUrl = https://api.anthropic.com
anthropic-version = 2023-06-01
→ actual address https://api.anthropic.com/v1/messages
```

The Messages API is a **stateless** message protocol: the client submits the current conversation history, and the model generates the next assistant message.

Official references:

- https://platform.claude.com/docs/en/api/messages/create
- https://platform.claude.com/docs/zh-CN/api/messages/create

---

## II. Request Protocol

## 2.1, Headers

```http
Content-Type: application/json
Accept: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

> **Key point**: Authentication is not `Authorization: Bearer` but `x-api-key`; moreover, the API version Header must be sent explicitly.

<br/>

## 2.2, system is a top-level field (key difference)

Anthropic's system prompt does **not use a `system` role message**, but rather a top-level `system` field:

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

> **Note**: Multiple system prompts need to be concatenated by the client (AgentForge concatenates with a double newline `\n\n`).

<br/>

## 2.3, messages: role and content

| role | content | Description |
|---|---|---|
| `user` | string or block array | User input; tool results are also carried by `user` |
| `assistant` | string or block array | Model reply; tool calls use the `tool_use` block |

The conversation history is usually alternating `user` / `assistant` turns; consecutive messages with the same role may be merged by the server.

<br/>

## 2.4, content block types

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

## 2.5, Request parameters

| Field | Description |
|---|---|
| `model` | Model ID |
| `max_tokens` | **Required**, maximum generated tokens |
| `temperature` / `top_p` | Sampling parameters (deprecated/restricted for new models, see below) |
| `stop_sequences` | Stop sequences |
| `tools[]` | `{"name","description","input_schema"}` |
| `tool_choice` | `{"type":"auto"}` / `{"type":"none"}` / `{"type":"any"}` / `{"type":"tool","name":X}` |

> **Note**: Anthropic has officially marked `temperature` and `top_p` as deprecated/restricted parameters for new models; new models may only accept compatible values, otherwise returning 400. Whether to send them should be decided based on the target model's capabilities.

---

## III. Response Protocol

## 3.1, message structure

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

> **Key point**: `content` is a **block array**, not a single string; a response may contain multiple text blocks and possibly a `tool_use` block.

<br/>

## 3.2, stop_reason

| Value | Meaning |
|---|---|
| `end_turn` | Natural end |
| `stop_sequence` | Hit a custom stop sequence |
| `max_tokens` | Reached max_tokens |
| `tool_use` | Needs to execute a tool |

<br/>

## 3.3, usage

`input_tokens` / `output_tokens`; the official API may also return extended fields such as cache tokens and server tool usage.

---

## IV. Streaming Protocol (SSE)

Add to the request:

```json
{ "stream": true }
```

The response is **SSE with event names**:

```text
event: message_start
data: {"type":"message_start","message":{...}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

event: message_stop
data: {"type":"message_stop"}
```

## 4.1, Event overview

| Event | Meaning |
|---|---|
| `message_start` | Start, carrying message metadata and `usage.input_tokens` |
| `content_block_start` | Start a content block (including the `tool_use`'s `id` / `name` / initial `input`) |
| `content_block_delta` | Increment: `text_delta` or `input_json_delta` |
| `content_block_stop` | Block end |
| `message_delta` | Carries `delta.stop_reason` and `usage.output_tokens` |
| `message_stop` | Message end |
| `error` | Error event |

<br/>

## 4.2, Streaming aggregation of tool arguments

`tool_use` arguments are delivered incrementally as `input_json_delta`, attributed by the content block `index`:

```text
content_block_start (index=1, type=tool_use, id=toolu_1, name=get_weather, input={})
content_block_delta (index=1, input_json_delta: "{\"city\":")
content_block_delta (index=1, input_json_delta: "\"hangzhou\"}")
        │
        ▼ accumulate by content block index
ToolRequest(id=toolu_1, name=get_weather, arguments={"city":"hangzhou"})
```

> **Note**: Text blocks and tool blocks can interleave within the same stream; `partial_json` concatenates the raw JSON text in arrival order, without reformatting.

---

## V. Tool Use wire details

## 5.1, assistant initiates a call (tool_use block)

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

> **Key point**: Unlike OpenAI's `arguments` string, Anthropic's `input` is an **object**.

<br/>

## 5.2, Tool result write-back (tool_result block, role=user)

```json
{
  "role": "user",
  "content": [
    { "type": "tool_result", "tool_use_id": "toolu_1", "content": "{\"temperature\":22}" }
  ]
}
```

`tool_use_id` corresponds to the `tool_use.id` at initiation.

---

## VI. Complete Call Walkthrough: user prompt + tool use (curl)

Scenario: The user asks **"What's the weather like in Hangzhou today?"** and declares a `get_weather` tool. Below, from constructing the curl to the return, both non-streaming / streaming are demonstrated (including the second round of tool result write-back).

<br/>

## 6.1, Declaring tools

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

> **Note**: Anthropic tools use `input_schema` (not OpenAI's `parameters`).

<br/>

## 6.2, Non-streaming · Round 1: The model decides to call a tool

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

**Return (note `stop_reason=tool_use`, `content` contains a `tool_use` block):**

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

> **Key point**: `tool_use.input` is an **object** `{"city":"杭州"}` (different from OpenAI's `arguments` string).

<br/>

## 6.3, Non-streaming · Round 2: Write back the tool result and get the final answer

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

**Return (`stop_reason=end_turn`):**

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

## 6.4, Streaming · Round 1: `input_json_delta` increments (`stream:true`)

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

**SSE (in arrival order, note `event:` + `data:`):**

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

**Aggregation** (attributed by content block `index`; `partial_json` concatenated):

```text
index=1 (tool_use) → id=toolu_1, name=get_weather, input={"city":"杭州"}
```

<br/>

## 6.5, Streaming · Round 2: Generate the final text after write-back

The request body is the same as 6.3, with `"stream": true` appended.

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

**Aggregation**: `text_delta` concatenated in order → `"杭州今天 26℃，天气晴。"`.

<br/>

## 6.6, Walkthrough key points summary

| Observation point | Non-streaming | Streaming |
|---|---|---|
| Tool arguments | `content[].tool_use.input` (object) | `content_block_delta.input_json_delta.partial_json` fragments, aggregated by block `index` |
| Whether a tool is called | `stop_reason == "tool_use"` | `message_delta.delta.stop_reason == "tool_use"` |
| Text | `content[].text` | `content_block_delta.text_delta.text` called back segment by segment |
| usage | Response body `usage` | `message_start` (input) + `message_delta` (output) |
| End | — | `event: message_stop` |

<br/>

## 6.7, Real test verification

You can directly run the script in the repository (requires setting the API Key):

```bash
export ANTHROPIC_API_KEY=sk-ant-...
bash docs/dev/backend/AgentForge核心模块设计原理/01、model模型协议层/chatmodel/anthropic/verify-anthropic-chat.sh
```

The script executes in sequence: ① a non-streaming tool call request; ② printing the `tool_use` block with `jq`; ③ a streaming request printing SSE. The optional environment variables `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` override the defaults.

> **Note**: Real calls incur costs; do not write the Key into scripts or commit it to the repository.

---

## VII. Summary

1. **Endpoint**: `POST {baseUrl}/v1/messages`; `x-api-key` + `anthropic-version` authentication.
2. **system**: A top-level field, not a role message.
3. **content**: A block array (`text` / `tool_use` / `tool_result` / …).
4. **Request**: `model` + required `max_tokens` + sampling/tool parameters.
5. **Response**: `content[]` + `stop_reason` + `usage`.
6. **Streaming**: SSE with event names; tool arguments aggregate `input_json_delta` by block `index`.
7. **Tools**: `tool_use` (`input` is an object) → `tool_result` (written back with the `user` role).

> For how AgentForge maps these to unified types, see "Anthropic Protocol 02, Core Practices for AgentForge's Anthropic Integration".

---

## References

[1]. [Anthropic Messages API (English)](https://platform.claude.com/docs/en/api/messages/create)

[2]. [Anthropic Messages API (Chinese)](https://platform.claude.com/docs/zh-CN/api/messages/create)

[3]. [Server-Sent Events (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

[4]. Related internal document: [Core Practices for AgentForge Anthropic Integration](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践)

<br/>

Compiled by: changlu Created: 2026.10.5 Updated: 2026.10.5
