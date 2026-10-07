---
title: "Anthropic Protocol 02, Core Practices for AgentForge's Anthropic Integration"
date: 2026-10-06
tags: [AgentForge, Model Protocol Layer, Anthropic]
---

# Core Practices for AgentForge's Anthropic Integration

> Updated: 2026-10-05  
> Applicable module: `agentforge-model-anthropic`  
> Current implementation: `AnthropicChatModel` (Blocking), `AnthropicStreamingChatModel` (SSE), `AnthropicProtocol` (shared wire mapping)  
> Wire API: Anthropic Messages API  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: The Anthropic Provider bidirectionally maps AgentForge's unified `ChatRequest` / `ChatResponse` to the **Messages API**, and handles Anthropic's two peculiar differences — "top-level system field + content block"; for the protocol itself, see "Anthropic Protocol 01, A Quick Understanding of the Anthropic Low-Level Protocol".

---

## I. Background and Problem Introduction

## 1.1, Scenario-Driven: One Core, adapting to Claude's block protocol

> AgentForge's upper layers depend only on `ChatMessage` / `ChatRequest` / `ChatResponse`. When integrating Claude, wire details such as the "top-level system field", "content block", and "tool_use/tool_result" must not leak into the Agent Runtime.

Therefore the Anthropic Provider's responsibility is to be a **protocol translation layer**, and to let Blocking and Streaming share the same set of mapping logic.

<br/>

## 1.2, Problem Guidance: How does the block protocol map to unified messages?

> **Question**: How does `SystemMessage` map to the top-level `system`? How do `AiMessage`'s text and tool calls map to `content[]`? How does `ToolExecutionResultMessage` map to `tool_result`? How are streaming `input_json_delta`s aggregated into complete tool arguments?

This article provides the mapping and implementation location for each item.

<br/>

## 1.3, Builder parameters

| Builder parameter | Default | Description |
|---|---|---|
| `baseUrl` | `https://api.anthropic.com` | API root address |
| `apiKey` | `null` | Required, written to `x-api-key` |
| `anthropicVersion` | `2023-06-01` | Written to the `anthropic-version` Header |
| `modelName` | `null` | Must have a value before the request |
| `temperature` | `null` | Non-null maps to `temperature` (compatibility see 5.4) |
| `maxTokens` | `1024` | Maps to the required `max_tokens` |
| `topP` | `null` | Non-null maps to `top_p` (compatibility see 5.4) |
| `stopSequences` | `null` | Maps to `stop_sequences` |
| `customParameter` | empty | Provider top-level extension fields |
| `customHeader` | empty | Custom Header |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | Connect timeout |
| `readTimeoutMillis` | `60000` | Read timeout |

> **Key point**: `max_tokens` is a required field for Anthropic; AgentForge defaults to 1024 to ensure the simplest call can execute directly.

---

## II. Core Concepts

```java
ChatRequest { List<ChatMessage> messages; ChatRequestParameters parameters; }

ChatResponse { AiMessage aiMessage; TokenUsage tokenUsage; FinishReason finishReason; Map<String,Object> metadata; }
```

The Provider is responsible for translating Core's `ChatRequest` into the Anthropic wire, and then normalizing the response into `ChatResponse`.

---

## III. Implementation Approach and Mapping

## 3.1, HTTP Headers

```http
Content-Type: application/json
Accept: application/json
x-api-key: ${ANTHROPIC_API_KEY}
anthropic-version: 2023-06-01
```

Then append `customHeaders`.

> **Note**: It is not `Authorization: Bearer`, and `anthropic-version` must be sent explicitly.

<br/>

## 3.2, SystemMessage mapping

Anthropic's system prompt uses a **top-level `system` field**. AgentForge iterates over all `SystemMessage`:

```text
SystemMessage A + SystemMessage B → "A\n\nB" → {"system":"A\n\nB"}
```

The concatenated system **no longer enters the `messages` array**.

<br/>

## 3.3, User / AI message mapping

| AgentForge | Anthropic role | content |
|---|---|---|
| `UserMessage` (single text) | `user` | `message.text()` (string) |
| `UserMessage` (multiple `Content`) | `user` | `content[]`, each `TextContent` → `{"type":"text","text":...}` |
| `AiMessage` (plain text) | `assistant` | `message.text()` (string) |
| `AiMessage` (with tool calls) | `assistant` | `content[]`: optional text block + `tool_use` block |
| `ToolExecutionResultMessage` | `user` | `content[]`: `tool_result` block |
| `SystemMessage` | Does not enter messages | Aggregated into the top-level `system` |

<br/>

## 3.4, Tool call mapping

When `AiMessage.hasToolExecutionRequests()` is true, it maps to the `assistant`'s `content[]`:

```json
{ "role": "assistant", "content": [
  { "type": "text", "text": "I will check." },
  { "type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": { "city": "hangzhou" } }
]}
```

`ToolExecutionResultMessage` maps to the `user`'s `content[]`:

```json
{ "role": "user", "content": [
  { "type": "tool_result", "tool_use_id": "toolu_1", "content": "{\"temperature\":22}" }
]}
```

| Core field | Anthropic field |
|---|---|
| `ToolExecutionRequest.id` | `tool_use.id` (written back to the corresponding `tool_result.tool_use_id`) |
| `ToolExecutionRequest.name` | `tool_use.name` |
| `ToolExecutionRequest.arguments` (JSON string) | `tool_use.input` (sent after being **parsed into an object**) |
| `AiMessage.text` | Optional preceding `text` block |
| `ToolExecutionResultMessage.isError == true` | `tool_result.is_error = true` |

> **Note**: `CustomMessage` has no `text()`, and currently still throws `UnsupportedOperationException`.

<br/>

## 3.5, Parameter mapping

| AgentForge parameter | Anthropic field | Current behavior |
|---|---|---|
| `modelName` | `model` | Required; fails locally if empty |
| `maxTokens` | `max_tokens` | Required; defaults to 1024 |
| `temperature` | `temperature` | Sent if non-null; note new-model compatibility |
| `topP` | `top_p` | Sent if non-null; note new-model compatibility |
| `stopSequences` | `stop_sequences` | Sent if non-null |
| `tools` | `tools[]` | `{"name","description","input_schema"}`; sends an empty object schema when there are no parameters |
| `toolChoice` | `tool_choice` | `AUTO→{"type":"auto"}`、`NONE→{"type":"none"}`、`REQUIRED→{"type":"any"}`、`SPECIFIC→{"type":"tool","name":X}` |
| `SystemMessage` | `system` | Multiple concatenated with a double newline |
| `customParameters` | Top-level pass-through | Standard fields ultimately override custom values of the same name |

<br/>

## 3.6, Response extraction rules

Iterate over the `content` array:

- Only extract blocks with `type == "text"` and concatenate them in order into `AiMessage.text()`;
- Extract blocks with `type == "tool_use"` and map them to `ToolExecutionRequest`:

| Anthropic `tool_use` | AgentForge |
|---|---|
| `id` | `id` |
| `name` | `name` |
| `input` (object) | `arguments` (`Json.stringify(input)`) |

```text
content = [text("I will check."), tool_use(...)]
        ↓
AiMessage.text()                     = "I will check."
AiMessage.hasToolExecutionRequests() = true
AiMessage.toolExecutionRequests()    = [ToolExecutionRequest(toolu_1, get_weather, {"city":"hangzhou"})]
```

> **Note**: When there is only a `tool_use` and no text block, `AiMessage.text()` is `null`; `thinking` / `image` / `document` / server tool blocks currently do not enter `AiMessage`.

<br/>

## 3.7, ChatResponse mapping

| Anthropic field | AgentForge field |
|---|---|
| `content[*].text` | `ChatResponse.aiMessage().text()` |
| `content[*].tool_use` | `ChatResponse.aiMessage().toolExecutionRequests()` |
| `usage.input_tokens` | `TokenUsage.inputTokens()` |
| `usage.output_tokens` | `TokenUsage.outputTokens()` |
| `input + output` | `TokenUsage.totalTokens()` |
| `stop_reason` | `ChatResponse.finishReason()` |
| `id` / `model` / `type` | `metadata["id"] / ["model"] / ["type"]` |

## 3.8, stop_reason mapping

| Anthropic | AgentForge `FinishReason` |
|---|---|
| `end_turn` | `STOP` |
| `stop_sequence` | `STOP` |
| `max_tokens` | `LENGTH` |
| `tool_use` | `TOOL_EXECUTION` |
| Other non-null | `OTHER` |
| `null` | `null` |

<br/>

## 3.9, Streaming implementation

`AnthropicStreamingChatModel` uses the same Endpoint, forces `stream=true`, and `Accept: text/event-stream`; the request body mapping is exactly the same as Blocking, uniformly generated by `AnthropicProtocol`.

`JdkHttpTransport` only calls back line by line and does not parse SSE semantics; `AnthropicStreamState` itself accumulates one frame by `event:` / `data:`, and dispatches on encountering an empty line (or a single-line `data:` frame):

| Event | AgentForge behavior |
|---|---|
| `message_start` | Records `metadata["id"]/["model"]`, reads `usage.input_tokens` |
| `content_block_start` | For `tool_use`, creates an accumulator by `index`, recording `id` / `name` / initial `input` |
| `content_block_delta` | `text_delta` immediately `onPartialResponse(...)`; `input_json_delta` appended to the tool arguments of the same `index` |
| `content_block_stop` | Not processed, waits for the whole block to end |
| `message_delta` | Reads `delta.stop_reason` and `usage.output_tokens` |
| `message_stop` | Triggers the final response via EOF / completion callback |
| `error` | Wrapped as `ModelException` and `onError(...)` |

Tool call aggregation rules:

- Text blocks and tool blocks can interleave and do not affect each other;
- `arguments` concatenates the raw JSON text in arrival order, without reformatting;
- If there is only `content_block_start` and no `input_json_delta` (`input` directly given as an object), use the start frame's `input`;
- When there is no `partial_json` at all, `arguments` is normalized to `{}`;
- Consistent with LangChain4j: during streaming, **do not** expose half-finished tool calls; only the final `ChatResponse` gives the complete result.

> **Key point**: `temperature` / `top_p` have been officially marked by Anthropic as deprecated/restricted for newer models; it is recommended not to set them explicitly by default, and to decide whether to send them based on the target model's capabilities. The Provider may later add model capability validation to block invalid parameters on the client side in advance.

---

## IV. Practical Code

## 4.1, Non-streaming

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

**Run output (simulated terminal)**

```shell
$ curl -s https://api.anthropic.com/v1/messages -H "x-api-key: $ANTHROPIC_API_KEY" \
    -H "anthropic-version: 2023-06-01" \
    -d '{"model":"your-claude-model","max_tokens":1024,"system":"You are a concise Java assistant.","messages":[{"role":"user","content":"Explain volatile."}]}'

{"id":"msg_xxx","type":"message","role":"assistant",
 "content":[{"type":"text","text":"volatile provides visibility guarantees..."}],
 "stop_reason":"end_turn","usage":{"input_tokens":24,"output_tokens":40}}
```

<br/>

## 4.2, Tool call closed loop (wire example)

```json
// assistant initiates
{"role":"assistant","content":[
  {"type":"text","text":"I will check."},
  {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"hangzhou"}}]}

// tool result write-back
{"role":"user","content":[
  {"type":"tool_result","tool_use_id":"toolu_1","content":"{\"temperature\":22}"}]}
```

---

## V. Error Handling, Limitations and Evolution

## 5.1, Local parameter validation

```text
modelName must be non-empty
apiKey must be non-empty
messages must contain at least one non-system user/assistant message
```

When there is only a `SystemMessage`, it throws:

```text
IllegalArgumentException: Anthropic request requires at least one user/assistant message
```

<br/>

## 5.2, HTTP errors

Non-2xx → `new ModelException("Anthropic request failed with HTTP " + statusCode, statusCode, responseBody)`; IOException → `ModelException("Anthropic request failed", cause)`. Upper layers can uniformly read `exception.statusCode()` / `exception.responseBody()`.

<br/>

## 5.3, Currently unimplemented capabilities

Implemented: Streaming/SSE, strongly-typed `tool_use` responses (including streaming `input_json_delta` aggregation), strongly-typed `tool_result` requests, `tools/tool_choice` request mapping, `UserMessage` multiple `Content` → `content[]`.

Not yet fully supported: thinking / redacted thinking, image / document content blocks, Prompt Caching strongly-typed configuration and usage, Structured Output, server tools, extended usage fields (cache token, etc.), writing `stop_sequence` into metadata, provider refusal / stop details normalization.

<br/>

## 5.4, Class structure and evolution

Blocking and Streaming share the same set of wire mapping:

```text
AnthropicProtocol（package-private）
    ├── collectSystemMessages / serializeMessages
    ├── serializeTools / toolChoice
    ├── extractText / extractToolUses
        │
        ├── AnthropicChatModel          -> blocking Messages API
        └── AnthropicStreamingChatModel -> Messages API SSE
```

When introducing Thinking / Multimodal later, it is recommended to extend a unified Content hierarchy in Core (`TextContent` already exists): `ImageContent` / `AudioContent` / `VideoContent` / `PdfFileContent`, so that the Anthropic block protocol and OpenAI multimodal converge onto the same upper-layer model.

---

## VI. Summary

1. **Two major differences**: The top-level `system` field and the `content` block array — both are absorbed by the Provider layer, leaving Core unchanged.
2. **Tool calls**: `tool_use` (`input` object) → `tool_result` (written back with the `user` role), a strongly-typed bidirectional mapping.
3. **Streaming**: `AnthropicStreamState` parses the SSE with event names itself, and `input_json_delta` is aggregated by block `index`.
4. **Required constraint**: `max_tokens` defaults to 1024, ensuring it can be called directly.
5. **Risk note**: Newer Claude models tighten `temperature` / `top_p`; leaving them unset by default is safer.

---

## References

[1]. [Anthropic Messages API (English)](https://platform.claude.com/docs/en/api/messages/create)

[2]. [Anthropic Messages API (Chinese)](https://platform.claude.com/docs/zh-CN/api/messages/create)

[3]. Related internal documents: [A Quick Understanding of the Anthropic Low-Level Protocol](/blog/01、model模型协议层/chatmodel/anthropic/Anthropic协议01、Anthropic底层协议快速理解), [ChatModel Core Protocol Layer Design](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现)

<br/>

Compiled by: changlu Created: 2026.10.5 Updated: 2026.10.5
