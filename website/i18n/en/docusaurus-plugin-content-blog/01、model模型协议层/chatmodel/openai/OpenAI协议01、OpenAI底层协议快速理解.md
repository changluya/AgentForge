---
title: "OpenAI Protocol 01, A Quick Understanding of the OpenAI Low-Level Protocol"
date: 2026-10-03
tags: [AgentForge, Model Protocol Layer, OpenAI]
---

# A Quick Understanding of the OpenAI Low-Level Protocol

> Updated: 2026-10-05  
> Applicable scenario: understanding the OpenAI Chat Completions wire protocol that `agentforge-model-openai` connects to  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: AgentForge's OpenAI Provider connects to **Chat Completions** (`POST {baseUrl}/chat/completions`). This article only covers "what the protocol itself looks like"; for how AgentForge maps it to the unified `ChatRequest` / `ChatResponse`, see "OpenAI Protocol 02, AgentForge OpenAI Integration Core Practices".

---

## 1. Background and Problem Introduction

## 1.1 Scenario-Driven: Why Chat Completions Was Chosen for the First Version

> When unifying access to multiple large models for AgentForge, we ran into a real problem: OpenAI officially recommends the new **Responses API**, while at the same time there are a large number of OpenAI-compatible services (self-built gateways, domestic models) that only support `/chat/completions`.

Therefore AgentForge release_1.x chose **Chat Completions** as the first version of the unified integration protocol: it is still an API that clearly exists, and it has the broadest compatibility.

<br/>

## 1.2 Problem Guidance: What Exactly Is Sent in a Single Conversation Call?

> **Question**: When an Agent calls an OpenAI model once, what do the request body, response body, and streaming SSE look like respectively? How are tool calls (function calling) expressed and filled back on the wire?

This article explains the protocol in the order of "request → response → streaming → tool calls".

<br/>

## 1.3 Protocol Positioning

```http
POST {baseUrl}/chat/completions
```

By default:

```text
baseUrl = https://api.openai.com/v1
→ actual address https://api.openai.com/v1/chat/completions
```

Official references:

- https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
- https://developers.openai.com/api/docs/guides/text

---

## 2. Request Protocol

## 2.1 Headers

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer ${apiKey}
```

> **Key point**: Authentication uses the standard `Authorization: Bearer`; enterprise gateways can add custom Headers (trace / tenant / routing).

<br/>

## 2.2 Request Body: messages (Core)

| role | Key field | Meaning |
|---|---|---|
| `system` | `content` | System prompt |
| `user` | `content` or `content[]` | User input (an array when multimodal) |
| `assistant` | `content` / `tool_calls[]` | Model reply, may carry tool calls |
| `tool` | `tool_call_id` + `content` | Tool execution result fill-back |

Plain text example:

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

## 2.3 Request Body: Sampling and Tool Parameters

| Field | Description |
|---|---|
| `temperature` | Sampling temperature |
| `max_tokens` | Maximum generated tokens |
| `top_p` | Nucleus sampling |
| `stop` | Stop sequences |
| `tools[]` | Tool declarations: `{"type":"function","function":{name,description,parameters,strict}}` |
| `tool_choice` | `"auto"` / `"none"` / `"required"` / `{"type":"function","function":{"name":X}}` |

<br/>

## 2.4 Complete Non-Streaming Request Example

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

## 3. Response Protocol

## 3.1 Non-Streaming chat.completion

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

> **Note**: `choices` is an array; consumers typically only take `choices[0]`.

<br/>

## 3.2 finish_reason

| Value | Meaning |
|---|---|
| `stop` | Normal completion |
| `length` | Reached max_tokens |
| `tool_calls` | A tool needs to be executed |
| `function_call` | Legacy field (deprecated) |
| `content_filter` | Content was filtered |

---

## 4. Streaming Protocol (SSE)

Add to the request:

```json
{ "stream": true, "stream_options": { "include_usage": true } }
```

The response is SSE:

```text
data: {"choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}

data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":2,"total_tokens":22}}

data: [DONE]
```

> **Key point**: When `stream_options.include_usage=true`, an extra chunk with `choices=[]` carrying the overall `usage` appears before `[DONE]`; during parsing you should read usage first, then determine whether choices is empty.

<br/>

## 4.1 Streaming Increments of Tool Calls (Aggregate by index)

`delta.tool_calls[]` is delivered in fragments and must be **aggregated by `index`**:

```text
chunk: {"index":0,"id":"call_1","function":{"name":"getWeather","arguments":""}}
chunk: {"index":0,"function":{"arguments":"{\"city\":"}}
chunk: {"index":0,"function":{"arguments":"\"hangzhou\"}"}}
        │
        ▼ merge by index
ToolRequest(id=call_1, name=getWeather, arguments={"city":"hangzhou"})
```

| delta field | Aggregation method |
|---|---|
| `index` | Bucket key |
| `id` | Overwrite into the current bucket (the first chunk carries the full id) |
| `function.name` | Append |
| `function.arguments` | Append the raw text, preserving the JSON text |

> **Note**: Some compatible gateways do not deliver `index`; in that case use "a new non-empty `id` appears" as the start of a new call (a fallback counter buckets it), to avoid incorrectly splitting the argument increments of the same call into multiple calls.

---

## 5. Tool Calling (Function Calling) Wire Details

## 5.1 The assistant Initiates a Call

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

> **Key point**: `arguments` is a **string** (JSON text), not an object—this is the key to the subsequent AgentForge normalization and re-parsing.

<br/>

## 5.2 tool Result Fill-Back

```json
{ "role": "tool", "tool_call_id": "call_1", "content": "{\"temperature\":22}" }
```

`tool_call_id` must correspond one-to-one with the `id` at initiation time.

<br/>

## 5.3 Multiple Tool Calls

A single response can return multiple `tool_calls`, which need to be executed one by one and filled back separately with the `tool` role, with `id` matching one-to-one.

---

## 6. Complete Call Walkthrough: user prompt + function calls (curl)

Scenario: The user asks **"What's the weather like in Hangzhou today?"** and declares a `get_weather` tool. Below, from curl construction to response, both non-streaming and streaming are demonstrated (including the second round of tool result fill-back).

<br/>

## 6.1 Declare tools

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

## 6.2 Non-Streaming · Round One: The Model Decides to Call a Tool

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

**Return (note `finish_reason=tool_calls`, `content=null`):**

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

> **Key point**: `arguments` is the string `"{\"city\":\"杭州\"}"`; after local parsing, execute `get_weather("杭州")`.

<br/>

## 6.3 Non-Streaming · Round Two: Fill Back the Tool Result to Get the Final Answer

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

**Return (`finish_reason=stop`):**

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

## 6.4 Streaming · Round One: tool_calls Increments (`stream:true`)

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

**SSE (in arrival order):**

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"杭州\"}"}}]},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

data: {"choices":[],"usage":{"prompt_tokens":62,"completion_tokens":15,"total_tokens":77}}

data: [DONE]
```

**Aggregation** (bucket by `tool_calls[].index`; `id` overwrites, `name`/`arguments` append):

```text
index=0 → id=call_1, name=get_weather, arguments={"city":"杭州"}
```

<br/>

## 6.5 Streaming · Round Two: Generate the Final Text After Fill-Back

The request body is the same as 6.3, with `"stream": true, "stream_options": {"include_usage": true}` appended.

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"杭州"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"今天 26℃"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"，天气晴。"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":85,"completion_tokens":12,"total_tokens":97}}

data: [DONE]
```

**Aggregation**: concatenate `delta.content` in order → `"杭州今天 26℃，天气晴。"`.

<br/>

## 6.6 Walkthrough Key Points Summary

| Observation point | Non-streaming | Streaming |
|---|---|---|
| Tool arguments | `message.tool_calls[].function.arguments` (string) | `delta.tool_calls[].function.arguments` fragmented, aggregated by `index` |
| Whether a tool is sent | `finish_reason == "tool_calls"` | `finish_reason == "tool_calls"` of the last chunk |
| Text | `message.content` | `delta.content` returned segment by segment |
| usage | Response body `usage` | The usage chunk with `choices=[]` at the end |
| End | — | `data: [DONE]` |

<br/>

## 6.7 Real Test Verification

You can run the script in the repository directly (an API Key needs to be set):

```bash
export OPENAI_API_KEY=sk-...
bash docs/dev/backend/AgentForge核心模块设计原理/01、model模型协议层/chatmodel/openai/verify-openai-chat.sh
```

The script executes in order: ① a non-streaming tool-calling request; ② printing `tool_calls` with `jq`; ③ a streaming request printing SSE. Optional environment variables `OPENAI_BASE_URL` / `OPENAI_MODEL` override the defaults.

> **Note**: Real calls incur charges; do not write the Key into the script or commit it to the repository.

---

## 7. Summary

1. **Endpoint**: `POST {baseUrl}/chat/completions`, authenticated with `Authorization: Bearer`.
2. **Request**: `messages` (system / user / assistant / tool) + sampling parameters + `tools` / `tool_choice`.
3. **Response**: `choices[0].message` (`content` or `tool_calls`) + `usage` + `finish_reason`.
4. **Streaming**: SSE `data:` chunk; `delta.content` text, `delta.tool_calls` aggregated by `index`; ends with `[DONE]`.
5. **Tools**: `assistant.tool_calls` (`arguments` is a string) → `tool` role filled back by `tool_call_id`.

> For how AgentForge maps these fields to unified types, see "OpenAI Protocol 02, AgentForge OpenAI Integration Core Practices".

---

## References

[1]. [OpenAI Chat Completions API (official reference)](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

[2]. [OpenAI Text Generation Guide](https://developers.openai.com/api/docs/guides/text)

[3]. [Server-Sent Events (MDN)](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)

[4]. Related internal document: [AgentForge OpenAI Integration Core Practices](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践)

<br/>

Compiler: changlu Created: 2026.10.5 Updated: 2026.10.5
