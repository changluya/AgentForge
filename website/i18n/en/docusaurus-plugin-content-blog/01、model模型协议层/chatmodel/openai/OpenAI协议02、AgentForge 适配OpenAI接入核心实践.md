---
title: "OpenAI Protocol 02, AgentForge OpenAI Integration Core Practices"
date: 2026-10-04
tags: [AgentForge, Model Protocol Layer, OpenAI]
---

# AgentForge OpenAI Integration Core Practices

> Updated: 2026-10-05  
> Applicable module: `agentforge-model-openai`  
> Current implementation: `OpenAiChatModel` (Blocking), `OpenAiStreamingChatModel` (SSE)  
> Wire API: OpenAI Chat Completions  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: The OpenAI Provider does a bidirectional mapping between AgentForge's unified `ChatRequest` / `ChatResponse` and the **Chat Completions** wire format; for the protocol itself see "OpenAI Protocol 01, A Quick Understanding of the OpenAI Low-Level Protocol", and this article covers AgentForge's implementation and boundaries.

---

## 1. Background and Problem Introduction

## 1.1 Scenario-Driven: One Core, Adapting to Multiple Models

> AgentForge's Agent Runtime depends only on the `ChatModel` / `StreamingChatModel` interfaces. We want "switching models" to mean switching just one Provider dependency, rather than changing Agent code.

So the OpenAI Provider's responsibility is limited to being a **protocol translation layer**:

```text
Agent Runtime
     │  ChatRequest / ChatResponse (Provider-agnostic)
     ▼
OpenAiChatModel / OpenAiStreamingChatModel
     │  Chat Completions wire
     ▼
OpenAI / OpenAI-compatible service
```

<br/>

## 1.2 Problem Guidance: How Do Unified Types Land on the OpenAI wire?

> **Question**: How do AgentForge's `ChatMessage`, `ChatRequestParameters`, and `ChatResponse` land on OpenAI's `messages` / request fields / response fields respectively? How are tool calls mapped bidirectionally? How is streaming aggregated?

This article provides the mapping tables and implementation locations item by item.

<br/>

## 1.3 Implementation Boundaries

- Wire API: `POST {baseUrl}/chat/completions` (default `https://api.openai.com/v1`);
- release_1.x uses Chat Completions as the first version of the unified protocol, and the Responses API adopts a "coexisting Adapter" strategy (see Chapter 6).

<br/>

## 1.4 Builder Parameters

| Builder parameter | Default value | Description |
|---|---|---|
| `baseUrl` | `https://api.openai.com/v1` | Also supports OpenAI-compatible services |
| `apiKey` | `null` | Sends `Authorization: Bearer ...` when non-empty |
| `modelName` | `null` | Must have a value before the request |
| `temperature` | `null` | Only sent when non-empty |
| `maxTokens` | `null` | Maps to `max_tokens` |
| `topP` | `null` | Maps to `top_p` |
| `stopSequences` | `null` | Maps to `stop` |
| `customParameter` | empty | Passes through Provider-specific top-level fields |
| `customHeader` | empty | Adds custom HTTP Headers |
| `httpTransport` | `JdkHttpTransport` | HTTP SPI |
| `connectTimeoutMillis` | `10000` | Connection timeout |
| `readTimeoutMillis` | `60000` | Read timeout |

> **Key point**: `OpenAiStreamingChatModel` reuses the same set of configuration and forcibly writes `stream=true` and `stream_options.include_usage=true`.

---

## 2. Core Concepts

## 2.1 Unified Input

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

## 2.2 Unified Output

```java
ChatResponse {
    AiMessage aiMessage;          // text + toolExecutionRequests
    TokenUsage tokenUsage;
    FinishReason finishReason;
    Map<String, Object> metadata;
}
```

> **Key point**: The Provider's responsibility is to land the wire fields losslessly onto these two unified objects.

---

## 3. Implementation Approach and Mapping

## 3.1 HTTP Headers

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer ${apiKey}   # when apiKey is non-empty
```

Afterwards `customHeaders` is appended; enterprise internal gateways can use `customHeader(...)` to add tenant, routing, trace, etc.

<br/>

## 3.2 Message Mapping

| AgentForge | OpenAI role | content / key field |
|---|---|---|
| `SystemMessage` | `system` | `message.text()` |
| `UserMessage` (single text) | `user` | `message.text()` |
| `UserMessage` (multiple `Content`) | `user` | `content[]`, each `TextContent` → `{"type":"text","text":...}` |
| `AiMessage` (plain text) | `assistant` | `message.text()` |
| `AiMessage` (with tool calls) | `assistant` | `content` (may be `null`) + `tool_calls[]` |
| `ToolExecutionResultMessage` | `tool` | `tool_call_id = message.id()`, `content = message.text()` |

The wire form of tool calls + result fill-back:

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

> **Note**: `CustomMessage` currently has no wire mapping implemented; encountering one throws `IllegalArgumentException`.

<br/>

## 3.3 Parameter Mapping

| AgentForge parameter | Chat Completions field | Current behavior |
|---|---|---|
| `modelName` | `model` | Required; fails locally if empty |
| `temperature` | `temperature` | Only sent when non-empty |
| `maxTokens` | `max_tokens` | Only sent when non-empty |
| `topP` | `top_p` | Only sent when non-empty |
| `stopSequences` | `stop` | Only sent when non-empty |
| `tools` | `tools[]` | `{"type":"function","function":{name,description,parameters,strict}}` |
| `toolChoice` | `tool_choice` | `AUTO→"auto"`, `NONE→"none"`, `REQUIRED→"required"`, `SPECIFIC→{"type":"function","function":{"name":X}}` |
| `customParameters` | Written verbatim at the top level | Generic fields are written then override same-named custom fields |

Construction order (standard fields have final priority):

```text
1. payload.putAll(customParameters)
2. write model / messages
3. write temperature / max_tokens / top_p / stop
4. write tools / tool_choice
```

> **Key point**: Even if `customParameters` writes another `model`, it will ultimately be overridden by `modelName`.

<br/>

## 3.4 Response Mapping

| OpenAI field | AgentForge field |
|---|---|
| `choices[0].message.content` | `ChatResponse.aiMessage().text()` |
| `choices[0].message.tool_calls[]` | `ChatResponse.aiMessage().toolExecutionRequests()` |
| `usage.prompt_tokens` | `TokenUsage.inputTokens()` |
| `usage.completion_tokens` | `TokenUsage.outputTokens()` |
| `usage.total_tokens` | `TokenUsage.totalTokens()` |
| `choices[0].finish_reason` | `ChatResponse.finishReason()` |
| `id` / `model` / `created` | `metadata["id"] / ["model"] / ["created"]` |

`tool_calls[]` mapping: `id → ToolExecutionRequest.id`, `function.name → name`, `function.arguments → arguments` (preserving the raw JSON string, without reformatting).

> **Note**: When `tool_calls` is non-empty and `content` is empty, `AiMessage.text()` returns `null`; when both exist, both are preserved.

<br/>

## 3.5 finish_reason Mapping

| OpenAI | AgentForge `FinishReason` |
|---|---|
| `stop` | `STOP` |
| `length` | `LENGTH` |
| `tool_calls` | `TOOL_EXECUTION` |
| `function_call` | `TOOL_EXECUTION` |
| `content_filter` | `CONTENT_FILTER` |
| Other non-empty | `OTHER` |
| `null` | `null` |

<br/>

## 3.6 Abnormal Response Structures

The following cases directly throw `ModelException` (rather than silently returning empty):

```text
choices does not exist / is empty / choices[0].message does not exist
```

<br/>

## 3.7 Streaming Implementation

**Request**: Same Endpoint, forcibly `stream=true` + `stream_options.include_usage=true`.

**SSE parsing**: Ignore empty lines, `:` comments, and non-`data:` lines; each `data:` chunk reads `choices[0].delta.content`, `delta.tool_calls[]`, `finish_reason`.

- `delta.content` → immediately `handler.onPartialResponse(...)`, while aggregating locally with a `StringBuilder`;
- `delta.tool_calls[]` → **accumulate bucketed by `index`** (rules in protocol article 4.1), without calling back half-finished pieces;
- End → `handler.onCompleteResponse(response)`.

**Final response**:

```java
ChatResponse.builder()
        .aiMessage(AiMessage.from(fullText, toolExecutionRequests))
        .finishReason(finishReason)
        .tokenUsage(tokenUsage)
        .metadata(metadata)
        .build();
```

> **Key point**: The final usage chunk (`choices=[]`) is handled by "read usage first, then determine whether choices is empty"; if the stream is interrupted the final usage may be missing, and `ChatResponse.tokenUsage()` is not guaranteed to exist.

---

## 4. Practical Code

## 4.1 Non-Streaming

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

**Run output (simulated terminal)**

```shell
$ curl -s https://api.openai.com/v1/chat/completions -H "Authorization: Bearer $OPENAI_API_KEY" \
    -d '{"model":"your-model","messages":[{"role":"system","content":"..."},{"role":"user","content":"What is CAS?"}]}'

{"choices":[{"index":0,"message":{"role":"assistant","content":"CAS means Compare-And-Swap."},"finish_reason":"stop"}],
 "usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}
```

<br/>

## 4.2 Streaming

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

**Run output (simulated terminal)**

```shell
CAS means Compare-And-Swap.
```

<br/>

## 4.3 OpenAI-compatible Service

```java
OpenAiChatModel model =
        OpenAiChatModel.builder()
                .baseUrl("https://example.com/v1")
                .apiKey("...")
                .modelName("provider-model")
                .build();
```

---

## 5. Compatibility, Errors, and Boundaries

## 5.1 HTTP Errors

Non-2xx → `new ModelException("OpenAI request failed with HTTP " + statusCode, statusCode, responseBody)`; network-layer IOException → `ModelException("OpenAI request failed", cause)`. For streaming, a non-2xx accumulates the body and returns it via `handler.onError(...)`.

<br/>

## 5.2 Protocol Gaps (Not Mapped in release_1.x)

`developer` role, image / audio multimodal `content`, streaming `onPartialToolCall`, refusal, logprobs, structured outputs, audio, provider reasoning (the `AiMessage.thinking()` field slot is reserved but not wired), multiple `choices`.

> `customParameters` can temporarily pass through request fields; but if the returned structure needs to be understood by the framework, the Core / Provider types must still be formally extended.

---

## 6. Summary and Evolution

Already delivered: request mapping of the `tool` role's `tool_call_id`, `tools/tool_choice` (including `SPECIFIC`), non-streaming parsing of `tool_calls` and streaming aggregation by `index`, `UserMessage` multiple `Content` → `content[]`.

OpenAI officially recommends that new applications prioritize the Responses API, so a **coexisting Adapter** is adopted going forward: `OpenAiChatModel → /chat/completions`, `OpenAiStreamingChatModel → /chat/completions + SSE`, with `OpenAiResponsesModel → /responses` to be added in the future. The upper layer still depends only on the Core interfaces, and protocol migration does not intrude into the Agent Runtime.

---

## References

[1]. [OpenAI Chat Completions API (official reference)](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

[2]. [OpenAI Text Generation Guide](https://developers.openai.com/api/docs/guides/text)

[3]. Related internal documents: [A Quick Understanding of the OpenAI Low-Level Protocol](/blog/01、model模型协议层/chatmodel/openai/OpenAI协议01、OpenAI底层协议快速理解), [ChatModel Core Protocol Layer Design](/blog/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现)

<br/>

Compiler: changlu Created: 2026.10.5 Updated: 2026.10.5
