---
title: "MCP Protocol to FunctionCall Protocol 02: MCP Framework Research and AgentForge Integration Implementation"
date: 2026-10-10
tags: [AgentForge, General ReAct Agent Layer, Function Calling, MCP]
---

#  MCP Framework Research and AgentForge Integration Implementation

> This article unfolds progressively along the line of "**background/problem introduction → core concepts → mainstream framework research → implementation approach → hands-on code → verification testing → summary**", in seven parts.

{/* truncate */}


<br/>

One-sentence conclusion: Among the three parties, **only LangChain4j has its own in-house protocol stack; both Spring AI and AgentScope Java are based on the official MCP Java SDK**; while **AgentForge chooses "in-house core protocol stack + optional official SDK bridge"**. Let's clarify the full story behind this conclusion below.

---

## 1. Background and Problem Introduction

## 1.1 Scenario Drive: A Real Integration Challenge

When planning MCP ecosystem integration for AgentForge, we ran into this concrete problem:

> The team hoped AgentForge's `ReActAgent` could directly consume the ready-made tools in the MCP ecosystem (filesystem, git, fetch, database…). But AgentForge has a hard constraint—**Java 8 compatibility, zero third-party dependencies in the core**. And looking across the Java ecosystem, both Spring AI and AgentScope chose the official MCP Java SDK, yet that SDK requires **JDK 17+** and also pulls in Jackson, Reactor, and SLF4J.  
> So the question arose: **Should we follow the official SDK or not?**

This is not a confusion unique to AgentForge. Any framework oriented toward "low JDK / zero dependencies" will hit the same wall when integrating MCP.

---

## 1.2 Problem Guidance: What Do We Need to Answer?

1. **For the three mainstream Java frameworks, do they support stdio and HTTP? To what extent?**<br/>
2. **Is their protocol "hand-written by themselves" or "based on the official MCP Java SDK"?**<br/>
3. **How should AgentForge choose? In-house or official? To what extent should it support?**<br/>
4. **After implementation, how do we unify it with AgentForge's existing `http` / `local` tool modes?**

---

## 1.3 Research Scope and Comparison Baseline

Research targets: **LangChain4j**, **Spring AI**, **AgentScope Java**. The comparison baseline is the official **MCP Java SDK**.

| Item | Official MCP Java SDK |
|---|---|
| Coordinates | `io.modelcontextprotocol.sdk:mcp` (BOM: `mcp-bom`) |
| Current version | **2.0.1** |
| JDK | **17+** |
| Programming model | Synchronous + Asynchronous (Reactor) |
| JSON / Logging | Jackson (pluggable) / SLF4J |
| Client transport | **STDIO, Streamable HTTP, SSE (legacy)** |
| Server transport | **STDIO, Streamable-HTTP, Stateless Streamable-HTTP, SSE** |
| Architecture layering | Client/Server → Session → Transport, three layers |

---

## 2. Core Concepts

## 2.1 Two Technical Routes: Official SDK vs In-House

### 2.1.1 Route One: Based on the Official Java SDK

Protocol details (JSON-RPC, version negotiation, transport framing) are maintained by the official team, and the framework only does **thin wrapping + ecosystem integration**.

- **Pros**: Worry-free, easy to pass conformance tests, full-featured, fast to follow the spec.
- **Downside explanation**: JDK 17+, pulls in Jackson / Reactor / SLF4J, hard to embed into low-JDK / zero-dependency projects.

### 2.1.2 Route Two: In-House Protocol Stack

The framework implements `McpTransport` / `McpClient` / JSON-RPC routing itself.

- **Pros**: Full control over dependencies and API, can be compatible with lower JDKs, zero extra dependencies.
- **Downside explanation**: Must follow spec revisions on your own, and do dual-era compatibility and conformance verification.

> **Key point**: These two routes have no absolute superiority; it only depends on the framework's own **constraint orientation**—which is exactly the root cause of the three-way divergence and also the root cause of AgentForge's decision.

---

## 2.2 The Official SDK's Three-Layer Architecture (for Reference)

```text
Client/Server layer   Protocol operations (listTools / callTool / initialize)
        ↓
Session layer         Communication mode and connection state
        ↓
Transport layer       JSON-RPC send/receive and serialization (STDIO / HTTP / SSE)
```

<br/>

## 2.3 Three-Way Overview

| Framework | stdio | HTTP | Implementation | Official SDK |
|---|---|---|---|---|
| **LangChain4j** | ✅ | ✅ Streamable HTTP (+ WebSocket / Docker) | **In-house protocol stack** | ❌ Not used |
| **Spring AI** | ✅ | ✅ Streamable / Stateless / SSE (WebMVC / WebFlux) | **Based on official SDK** | ✅ Depends on 1.0+ |
| **AgentScope Java** | ✅ | ✅ Streamable HTTP / SSE | **Based on official SDK** | ✅ Depends |

---

## 3. Mainstream Framework Research

## 3.1 LangChain4j: In-House Protocol Stack

### 3.1.1 Did It Actually Use the Official SDK?

MCP capabilities are in the `dev.langchain4j:langchain4j-mcp` module. Checking its `pom.xml` dependencies: `langchain4j`, `langchain4j-core`, `jackson-databind`, `langchain4j-http-client(-jdk)`, `langchain4j-open-ai`. **There is no `io.modelcontextprotocol.sdk`**—meaning the protocol is self-implemented.

> **Key point**: It is the **only self-developed** one among the three (its own `McpTransport` / `DefaultMcpClient` / `McpOperationHandler`).

<br/>

### 3.1.2 stdio Support

- `StdioMcpTransport`: `ProcessBuilder` spawns a child process, reading and writing JSON-RPC line by line.
- A separate module `langchain4j-mcp-docker` provides `DockerMcpTransport` (containerized stdio server).
- The server side (building a stdio server) is placed in **LangChain4j Community**.

<br/>

### 3.1.3 HTTP Support

- `StreamableHttpMcpTransport`: based on the JDK `HttpClient`, POST to a single endpoint; optional `subsidiaryChannel(true)` opens a GET SSE subsidiary channel (legacy).
- `HttpMcpTransport`: old HTTP+SSE, already `@Deprecated(forRemoval)`.
- `WebSocketMcpTransport`: non-standard extra transport, compatible with the Quarkus MCP Server extension.

<br/>

### 3.1.4 Protocol Versions and Tool Governance

- Supports both **legacy (2025-11-25) and modern (2026-07-28)**, with automatic detection by default (`server/discover` → fall back to `initialize` on failure).
- Tool capabilities: `McpToolProvider` supports filtering by name, `toolNameMapper`, `toolSpecificationMapper`, multi-client aggregation; `DefaultMcpClient` has a built-in tool list cache; supports `x-mcp-header`, `_meta` injection, resource subscription.
- Also has a **MCP Registry** read-only client.

---

## 3.2 Spring AI: Based on the Official Java SDK

### 3.2.1 Did It Actually Use the Official SDK?

Spring AI MCP **explicitly adopts the official MCP Java SDK**, and its documentation directly presents the SDK's three-layer architecture. Since **Spring AI 2.0**, the official SDK **1.0.0+** is required.

> **Note (migration point)**: `mcp-spring-webflux` / `mcp-spring-webmvc` **migrated** from `io.modelcontextprotocol.sdk` **to** `org.springframework.ai`; the relevant transport class package names also changed from `io.modelcontextprotocol.*` to `org.springframework.ai.mcp.*`.

<br/>

### 3.2.2 stdio Support

- Client: `spring-ai-starter-mcp-client` provides STDIO.
- Server: `spring-ai-starter-mcp-server` (`spring.ai.mcp.server.stdio=true`).

<br/>

### 3.2.3 HTTP Support

- Client: `spring-ai-starter-mcp-client` (Servlet version Streamable-HTTP / Stateless / SSE), `spring-ai-starter-mcp-client-webflux` (WebFlux version).
- Server: `spring-ai-starter-mcp-server-webmvc` / `-webflux`, switched with `spring.ai.mcp.server.protocol = SSE | STREAMABLE | STATELESS`.

<br/>

### 3.2.4 Annotations and Governance

- Server annotations: `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`.
- Client annotations: `@McpLogging`, `@McpSampling`, `@McpElicitation`, `@McpProgress`.
- Tool adaptation: `SyncMcpToolCallback` / `AsyncMcpToolCallback`, `SyncMcpToolCallbackProvider`, `McpToolUtils`.

> **Key point**: Spring AI does not implement the protocol itself, but rather **thin-wraps the official SDK + fills out the Spring Boot experience** (Starter / annotations / WebMVC·WebFlux transports).

---

## 3.3 AgentScope Java: Based on the Official Java SDK

### 3.3.1 Did It Actually Use the Official SDK?

AgentScope Java's `McpClientBuilder` internally **reuses the transport and client classes of the official `io.modelcontextprotocol` SDK**, ultimately wrapping them into `McpAsyncClient` / `McpSyncClient`, and injects AgentScope's version and User-Agent.

<br/>

### 3.3.2 stdio Support

```java
McpClientWrapper client = McpClientBuilder.create("filesystem-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync().block();
```

<br/>

### 3.3.3 HTTP Support

- `sseTransport(url)`: SSE (stateful streaming).
- `streamableHttpTransport(url)`: Streamable HTTP (stateless streaming).
- Supports `.header(...)` / `.queryParam(...)`, `.timeout(...)`, `.initializationTimeout(...)`, `.protocolVersions(...)`, `elicitation` callback.

<br/>

### 3.3.4 Tool Governance

- `Toolkit.registerMcpClient(...)` for unified registration; `enableTools` / `disableTools` whitelist and blacklist; `group(...)` tool grouping; `removeMcpClient(...)` dynamic removal.
- Namespace convention: `mcp__{server}__{tool}`.
- Also has the Higress AI gateway extension (semantic tool retrieval).

> **Note**: By default it only declares `2024-11-05`; connecting to newer servers requires explicitly `protocolVersions(...)`, otherwise it reports "Unsupported protocol version".

---

## 3.4 Horizontal Comparison

### 3.4.1 stdio / HTTP Support Matrix

| Capability | LangChain4j | Spring AI | AgentScope Java |
|---|---|---|---|
| Client stdio | ✅ | ✅ | ✅ |
| Client Streamable HTTP | ✅ | ✅ (Servlet / WebFlux) | ✅ |
| Client SSE (legacy) | ✅ (being deprecated) | ✅ | ✅ |
| Server stdio | ⚠️ (Community) | ✅ | — (client-oriented) |
| Server Streamable HTTP | ⚠️ (Community) | ✅ (WebMVC / WebFlux / Stateless) | — |
| Extra transport | WebSocket / Docker | — | — |

### 3.4.2 Implementation and Constraints

| Dimension | LangChain4j | Spring AI | AgentScope Java |
|---|---|---|---|
| Protocol implementation | **In-house** | Official SDK | Official SDK |
| Official SDK version | Not depended on | 1.0.0+ | Depends on `io.modelcontextprotocol` |
| JDK/dependency constraint | Follows LangChain4j | Spring + SDK (JDK17+) | SDK (JDK17+) |
| Protocol version | legacy + modern auto-detection | Follows SDK | Requires explicit version declaration |
| Tool governance | Filter / rename / treat resources as tools | Starter + annotations | Filter / group / gateway retrieval |
| Registry | ✅ Read-only client | Partial | — |

---

## 3.5 Comparative Analysis and Selection Recommendations

### 3.5.1 Three Selection Scenarios

- **Option One: Already a Spring Boot application** → Spring AI. Official SDK + Starter is the least hassle, but you're bound to the Spring ecosystem.
- **Option Two: Multi-Agent orchestration / need tool governance and gateway** → AgentScope Java. Tool grouping, namespaces, and Higress gateway retrieval are the most complete.
- **Option Three: Already using LangChain4j or need to escape official SDK constraints** → LangChain4j. In-house protocol stack, controllable dependencies.

> **Downside explanation**: All three directly or indirectly **require JDK 17+ and pull in third-party libraries**. Any project that is "low JDK / zero dependencies" satisfies none of the three—the only option is in-house.

<br/>

### 3.5.2 Summary

- **At the transport level**: All three support stdio and HTTP (Streamable HTTP).
- **At the implementation level**: **LangChain4j is in-house, Spring AI and AgentScope are based on the official SDK**.
- **Inference**: The official SDK is the de facto standard, but it raises the JDK bar to 17—which is exactly where AgentForge needs to make a separate decision.

---

## 4. AgentForge Implementation Approach

## 4.1 AgentForge Current State and Constraints

### 4.1.1 Hard Constraints

| Constraint | Description |
|---|---|
| JDK | `maven.compiler.source/target = 8` (API oriented toward Java 8) |
| Dependencies | Core module **zero third-party dependencies**: no Jackson / OkHttp / SLF4J / Lombok / Reactor |
| JSON | Built-in `cloud.changlu.agentforge.model.internal.json.Json` |
| HTTP | Built-in `HttpTransport` / `HttpRequest` / `HttpResponse` / `JdkHttpTransport` |
| Logging | `java.util.logging` |

> **Key point**: The official MCP Java SDK requires **JDK 17+** and pulls in **Jackson + Reactor + SLF4J**, which **directly conflicts** with AgentForge's core constraints.

<br/>

### 4.1.2 Reusable Tool Abstractions

| Existing type | Role | Correspondence with MCP |
|---|---|---|
| `ToolExecutor` (`execute` / `executeWithResult`) | Tool execution interface | `tools/call` |
| `ToolExecutionRequest` | `name` + `arguments`(JSON) | `tools/call` input |
| `ToolExecutionResult` | `text` / `result` / `isError` | `content` / `structuredContent` / `isError` |
| `ToolSpecification` | Tool declaration | the tool of `tools/list` |
| `ToolParameters` | Raw JSON Schema (Map) | `inputSchema` |
| `ToolService` | Tool registration and loop | tool table |

> **Key point**: MCP and AgentForge's tool abstractions are **naturally isomorphic**, and the integration point is the implementation of `ToolExecutor`.

<br/>

### 4.1.3 Existing Mode Conventions

`agent-core` already has two tool modes, following the same "factory + executor + subpackage" pattern:

```text
tool/local/   LocalToolFactory + LocalToolExecutor + support/
tool/http/    HttpToolFactory  + HttpToolExecutor  + domain/ enums/ support/ parser/
tool/mcp/     (newly added in this article, aligned with the above conventions)
```

---

## 4.2 Implementation Approach: Official SDK vs In-House

### 4.2.1 Option Comparison

**Option One: Based on the official MCP Java SDK**

- Pros: Protocol maintained officially, easy to pass conformance tests, full-featured.
- Downside explanation: **JDK 17+**, pulls in Jackson / Reactor / SLF4J, breaking the core's zero dependencies; requires an extra bridge layer to adapt `ToolExecutor`.

**Option Two: In-house protocol stack**

- Pros: **Java 8 compatible, zero extra dependencies**, seamless with `ToolExecutor`, reuses the built-in `Json` and `HttpTransport`.
- Downside explanation: Must follow spec revisions on your own, and do dual-era compatibility and conformance verification.

**Option Three: Hybrid (in-house core + official bridge)**

- The core (`agentforge-agent-core`) uses an in-house protocol stack, maintaining zero dependencies (**already delivered**);
- Additionally, an **optional module** `agentforge-mcp-sdk-bridge` (JDK 17), wrapping the official SDK's `McpClient` into AgentForge's `ToolExecutor` (**P3 planned, not yet implemented**).

> **Conclusion**: The target form adopts **Option Three**—the main path is delivered in-house first, and the official SDK serves as an optional bridge to be filled in later, both safeguarding the core constraints and not locking users into the in-house protocol stack.

<br/>

### 4.2.2 Decision Basis

| Dimension | In-house | Official SDK |
|---|---|---|
| JDK | 8 | 17+ |
| Dependencies | None | Jackson/Reactor/SLF4J |
| With `ToolExecutor` | Direct implementation | Requires bridging |
| Spec follow-up | Follow it yourself | Official follows |
| Conformance tests | Do it yourself | Official does it |
| **AgentForge fit** | **High** | Low (requires isolation module) |

<br/>

### 4.2.3 How Bridging Resolves the JDK 17 Conflict

**Question**: The official SDK requires JDK 17, so doesn't setting up an "official bridge" module bring JDK 17 back again? Can JDK 8 still use MCP at all?

**First, let's clarify**: MCP is a JSON-RPC protocol, **independent of the JDK**; JDK 17 is the bar for "the official Java SDK implementation", not the bar for MCP. JDK 8 can fully implement MCP—`ProcessBuilder` (stdio) + `HttpURLConnection` (HTTP) + the built-in `Json` is enough, no Jackson / Reactor needed.

**Key principle: one-way dependencies, upward bytecode compatibility.** A module compiled with JDK 17 can depend on a module compiled with Java 8, but not the reverse. So let **bridge depend on core**, not core depend on bridge:

```text
agentforge-agent-core (release 8 / zero dependencies)
   ▲  defines ToolExecutor / McpClient / ToolSpecification
   │  ← only depended upon, contains no MCP implementation
agentforge-mcp-sdk-bridge (release 17)
    depends on core + official SDK + Jackson / Reactor / SLF4J
    wraps the official McpClient into core's ToolExecutor
```

Thus JDK 17 types, Jackson, Reactor, and SLF4J are all locked inside the bridge, and **will never appear on core's compile or runtime path**.

<br/>

**JDK Version × Implementation Path**

| Runtime environment | In-house protocol stack (built into core) | Official SDK bridge (separate module) |
|---|---|---|
| JDK 8 | ✅ The only choice | ❌ Cannot be used (SDK requires 17) |
| JDK 17+ | ✅ Still usable (Java 8 bytecode is upward compatible, zero-dependency advantage retained) | ✅ Optional, effective only when explicitly included |

> **Key point**: The in-house stack is **not JDK 8-exclusive**; it also runs on JDK 17 / 21; the official bridge is "opt-in", **not switched automatically by JDK version**. The two paths are a **deployment-time either/or choice**, decided by the user.

<br/>

**Isolation Techniques**

- **Build isolation**: Multi-module project, core uses `maven.compiler.release=8`, bridge uses `17`; **core's POM never declares bridge**, so Jackson / Reactor / SLF4J cannot enter core's dependency tree.
- **Runtime isolation**: The bridge is a separate artifact; JDK 8 users never load it unless they include it, avoiding `UnsupportedClassVersionError`.
- **Interface isolation**: Core only exposes Java 8's `ToolExecutor` / `McpClient`, the bridge internally is compatible with official types, and only implements core interfaces upward.
- **Optional lazy loading**: Use Java 8's built-in `java.util.ServiceLoader` to define the `McpClientFactory` SPI—if a bridge is present it is discovered, otherwise it falls back to the in-house `DefaultMcpClient`, keeping core zero-dependency throughout.

> **Conclusion**: Bridging is not "running the JDK 17 SDK on Java 8", but rather opening an **optional, isolated** door for users who "are willing to upgrade to JDK 17 and want official capabilities"; users who don't upgrade to JDK 17 go entirely with the in-house stack, and the two paths are each clean.

> **Current progress**: The in-house protocol stack is implemented; `agentforge-mcp-sdk-bridge` and the `McpClientFactory` SPI are both **P3 planned, not yet delivered**.

<br/>

---

## 4.3 Overall Architecture and Packaging

### 4.3.1 Directory Structure

```text
agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/tool/
├── mcp/
│   ├── McpToolFactory.java         # Build entry: listTools → Map<ToolSpecification, ToolExecutor>
│   ├── McpToolExecutor.java        # implements ToolExecutor: tools/call
│   ├── McpClient.java              # Client interface: serverAlias / listTools / callTool / close
│   ├── DefaultMcpClient.java       # JSON-RPC lifecycle (initialize handshake, lazy initialization)
│   ├── McpProtocolException.java   # JSON-RPC error → exception
│   ├── McpTransportException.java  # Transport failure exception
│   ├── domain/
│   │   ├── McpTool.java
│   │   ├── McpCallToolResult.java
│   │   ├── McpContent.java
│   │   ├── McpServerInfo.java
│   │   └── McpCapabilities.java
│   ├── transport/
│   │   ├── McpTransport.java       # SPI for send / request / close
│   │   ├── StdioMcpTransport.java
│   │   └── StreamableHttpMcpTransport.java
│   └── support/
│       ├── JsonRpcCodec.java
│       ├── McpJson.java
│       ├── McpToolSpecificationMapper.java
│       └── McpResultConverter.java
```

### 4.3.2 Layered Responsibilities

```text
┌──────────────────────────────────────────────┐
│ McpToolFactory / McpToolExecutor              │  Connects to Agent tool table
├──────────────────────────────────────────────┤
│ DefaultMcpClient (id increment, handshake, lazy init) │  Protocol layer
├──────────────────────────────────────────────┤
│ McpTransport (stdio / Streamable HTTP)        │  Transport layer
└──────────────────────────────────────────────┘
```

---

## 4.4 Core Design and Mapping

### 4.4.1 `McpToolExecutor implements ToolExecutor`

```java
public class McpToolExecutor implements ToolExecutor {

    private final McpClient client;        // Bound at construction: which MCP Server this tool belongs to
    private final String remoteToolName;   // Bound at construction: the original tool name on the Server (without alias prefix)

    public McpToolExecutor(McpClient client, String remoteToolName) {
        this.client = client;
        this.remoteToolName = remoteToolName;
    }

    /** Simplified interface: returns text only. */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        // Step ①: the arguments given by the model are a JSON string → restore to a Map
        Map<String, Object> args = McpJson.argumentsAsMap(request.arguments());
        // Step ②: use the bound original remote name to send tools/call, and flatten content into text
        return McpResultConverter.toText(client.callTool(remoteToolName, args));
    }

    /** Structured interface: additionally carries structuredContent and isError. */
    @Override
    public ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        try {
            // Step ①: JSON string → Map (nested objects/arrays kept as-is)
            Map<String, Object> args = McpJson.argumentsAsMap(request.arguments());
            // Step ②: determine "which Server to send to, which tool to call"—decided by the two fields bound at construction
            McpCallToolResult result = client.callTool(remoteToolName, args);
            // Step ③: lossless mapping back to AgentForge result
            return ToolExecutionResult.builder()
                    .isError(result.isError())                    // isError passed through as-is
                    .result(result.structuredContent())           // structuredContent → result
                    .text(McpResultConverter.toText(result))      // content text → text (fed back to model)
                    .build();
        } catch (McpProtocolException e) {                        // JSON-RPC error (e.g. -32601)
            return ToolExecutionResult.failure("MCP protocol error: " + e.getMessage(), e);
        } catch (McpTransportException e) {                       // transport failure / timeout
            return ToolExecutionResult.failure("MCP transport error: " + e.getMessage(), e);
        }
    }
}
```

<br/>

### 4.4.2 `McpToolFactory`: List → Spec + Executor

**Core steps**: ① Parse the alias (explicit takes priority, otherwise `client.serverAlias()`) → ② Fetch native tools (`tools/list`) → ③ White/blacklist filtering → ④ Concatenate alias to generate the model-visible name → ⑤ Bind `McpToolExecutor(client, original name)` → ⑥ Hand to `ToolService` to build the table by `spec.name()`.

```java
public final class McpToolFactory {

    private McpToolFactory() {}

    /** Use the client's own serverAlias as the prefix. */
    public static Map<ToolSpecification, ToolExecutor> buildTools(McpClient client) {
        return buildTools(client, null, null, null);
    }

    /** Explicit alias takes priority, falling back to client.serverAlias() when blank. */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client, String serverAlias) {
        return buildTools(client, serverAlias, null, null);
    }

    /** When enabledTools is non-empty, only the remote tool names within it are exposed; those matched by disabledTools are hidden. */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client, String serverAlias,
            Set<String> enabledTools, Set<String> disabledTools) {
        // Step ①: determine the prefix—explicit alias takes priority, otherwise read client.serverAlias()
        String alias = (serverAlias != null && !serverAlias.trim().isEmpty())
                ? serverAlias : client.serverAlias();

        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();

        // Step ②: fetch the Server's native tools (the first call triggers initialize → initialized → tools/list)
        for (McpTool tool : client.listTools()) {

            // Step ③: white/blacklist filtering (determined by "original tool name")
            if (enabledTools != null && !enabledTools.isEmpty()
                    && !enabledTools.contains(tool.name())) {
                continue;
            }
            if (disabledTools != null && disabledTools.contains(tool.name())) {
                continue;
            }

            // Step ④: concatenate alias to generate the model-visible name; inputSchema passed through as-is; write ownership metadata
            ToolSpecification spec = ToolSpecification.builder()
                    .name(qualify(alias, tool.name()))          // fs__read_file
                    .description(tool.description())
                    .parameters(McpToolSpecificationMapper.toToolParameters(tool.inputSchema()))
                    .addMetadata("mcp.server", alias == null ? "" : alias)
                    .addMetadata("mcp.tool", tool.name())
                    .build();

            // Step ⑤: bind (client + original remote name)—used at runtime to route back to the corresponding Server
            result.put(spec, new McpToolExecutor(client, tool.name()));
        }
        // Step ⑥: return the spec→executor mapping, handed to ToolService.tools(...) to build the table by spec.name()
        return result;
    }

    private static String qualify(String alias, String toolName) {
        return (alias == null || alias.trim().isEmpty()) ? toolName : alias + "__" + toolName;
    }
}
```

<br/>

### 4.4.3 Schema Mapping (Zero Loss)

```java
public static ToolParameters toToolParameters(Map<String, Object> inputSchema) {
    if (inputSchema == null || inputSchema.isEmpty()) {
        return ToolParameters.empty();      // {"type":"object","properties":{}}
    }
    return ToolParameters.from(inputSchema); // pass through JSON Schema as-is
}
```

<br/>

### 4.4.4 Error Mapping

| MCP | AgentForge |
|---|---|
| `result.isError = true` | `ToolExecutionResult.isError(true)` + text returned to the model for self-correction |
| JSON-RPC `error` (`-32601/-32602`…) | `McpProtocolException` → failure result / error handler |
| `-32022` version unsupported | Throws `McpProtocolException` (auto-reselect version is P1) |
| Transport exception / timeout | `McpTransportException` → failure result + alert |

---

## 5. AgentForge Hands-On Code

## 5.1 Minimal Integration: Two Transports

### 5.1.1 Integrating a Local stdio Server

```java
// 1) Start a stdio MCP Server (local child process, newline-framed JSON-RPC)
McpClient fs = DefaultMcpClient.builder()
        .serverAlias("fs")
        .transport(StdioMcpTransport.builder()
                .command("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
                .build())
        .build();

// 2) Fetch tools and register into ToolService (reads the client's serverAlias, auto-prefixes: fs__read_file)
ToolService toolService = new ToolService();
toolService.tools(McpToolFactory.buildTools(fs));

// 3) Coexist with local @Tool (same tool table)
toolService.tools(new WeatherTools());

// 4) Assemble the ReActAgent
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .toolService(toolService)
        .build();
```

> **Key point**: `DefaultMcpClient` is **lazy**—the `initialize` handshake and `notifications/initialized` are sent only on the first `listTools()` / `callTool()`, and `tools/list` is sent afterward. `StdioMcpTransport.of("npx", "-y", ...)` is an equivalent shorthand.

<br/>

### 5.1.2 Integrating a Remote Streamable HTTP Server

```java
McpClient remote = DefaultMcpClient.builder()
        .serverAlias("github")
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint("https://mcp.example.com/mcp")
                .header("Authorization", "Bearer " + token) // custom headers passed through with the request
                .build())
        .build();

toolService.tools(McpToolFactory.buildTools(remote)); // reads the client's serverAlias, tool names look like github__search_repos
```

**The difference from stdio is only at the transport layer**: `serverAlias="github"` → model-visible name `github__search_repos`; the positioning and parameter conversion of `buildTools` / `ToolService` / `ToolExecutor` are **completely identical**. On the HTTP side, each `tools/call` is a POST:

- Request body = the aforementioned JSON-RPC (`{"jsonrpc","id","method":"tools/call","params":{...}}`);
- After the handshake, `MCP-Protocol-Version` is automatically included, and custom headers (`Authorization`) are passed through as-is;
- The response is extracted by `id` (`application/json` or SSE `data:`), then goes through the same `content` / `isError` mapping.

<br/>

### 5.1.3 On-Demand Filtering and Closing

```java
// Only expose read_file, hide the rest of the tools (empty/null whitelist means all)
Map<ToolSpecification, ToolExecutor> tools =
        McpToolFactory.buildTools(
                client, "fs",
                Collections.singleton("read_file"), // whitelist
                null);                              // blacklist

client.close(); // stdio: close and reclaim the child process; HTTP: release the transport
```

<br/>

### 5.1.4 Underlying Core Principle: How a Single Query Is Closed Loop (Real function call Sequence)

Scenario: MCP `fs` (filesystem) and MCP `weather` are registered at the same time, and the user asks "read out the contents of /tmp/a.txt, and check the weather in Hangzhou". The following unfolds **in the order actually occurring**.

**① The MCP Server first returns the native `tools/list` (without prefix)**

AgentForge first calls `tools/list` on each Server and gets the **tool's own name** (at this point there is no `fs__` prefix yet):

```json
// ← fs server's tools/list response
{"jsonrpc":"2.0","id":1,"result":{"tools":[
  {"name":"read_file","description":"Read a file","inputSchema":
    {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}
]}}

// ← weather server's tools/list response
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"get_weather","description":"Query weather","inputSchema":
    {"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}}
]}}
```

**② The middleware assembles the alias (`McpToolFactory`)**

`buildTools(client)` transforms the above **original tools**: `alias__original name`, and at the same time binds the executor and writes it into `ToolService`:

```text
fs      + read_file    → spec.name = "fs__read_file"        + McpToolExecutor(fsClient,      "read_file")
weather + get_weather  → spec.name = "weather__get_weather" + McpToolExecutor(weatherClient, "get_weather")
```

> **Note**: **The original name is always retained in the executor** (`read_file` / `get_weather`), and the prefix is only added to the model-visible name—this is the basis for the subsequent "strip prefix, send original name".

**③ The `tools[]` seen by the large model (alias form)**

```json
[
  {"type": "function", "function": {"name": "fs__read_file",
    "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
  {"type": "function", "function": {"name": "weather__get_weather",
    "parameters": {"type": "object", "properties": {"location": {"type": "string"}}, "required": ["location"]}}}
]
```

**④ The combined function call returned by the model (OpenAI form, two at a time)**

```json
{
  "role": "assistant",
  "tool_calls": [
    {
      "id": "call_a1b2",
      "type": "function",
      "function": {
        "name": "fs__read_file",
        "arguments": "{\"path\":\"/tmp/a.txt\"}"
      }
    },
    {
      "id": "call_c3d4",
      "type": "function",
      "function": {
        "name": "weather__get_weather",
        "arguments": "{\"location\":\"杭州\"}"
      }
    }
  ]
}
```

> **Key**: `arguments` is an **escaped JSON string**, not an object (for Anthropic it is the `tool_use.input` object). AgentForge normalizes both into `ToolExecutionRequest(id, name, arguments)`—`arguments` is **always a string**.

**⑤ Normalization → ToolService locates the executor by alias**

```text
ToolExecutionRequest{id="call_a1b2", name="fs__read_file",       arguments="{\"path\":\"/tmp/a.txt\"}"}
ToolExecutionRequest{id="call_c3d4", name="weather__get_weather", arguments="{\"location\":\"杭州\"}"}
```

- `ToolService` uses `name` to hit `toolExecutors`: `fs__read_file` → the `McpToolExecutor` bound to `fsClient` + `read_file`; `weather__get_weather` → `weatherClient` + `get_weather`.

**⑥ Strip the prefix + restore parameters → send the original `tools/call`**

- `McpJson.argumentsAsMap(arguments)` **restores the string to an object**;
- Use the **original remote name** in the executor (strip the `fs__` / `weather__` prefix) to send:

```json
// → fs server
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"read_file","arguments":{"path":"/tmp/a.txt"}}}

// → weather server
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"get_weather","arguments":{"location":"杭州"}}}
```

**⑦ Response → fed back to the model → final answer**

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"hello"}],"isError":false}}
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"杭州 26℃，晴"}],"isError":false}}
```

```json
[
  {"role": "tool", "tool_call_id": "call_a1b2", "content": "hello"},
  {"role": "tool", "tool_call_id": "call_c3d4", "content": "杭州 26℃，晴"}
]
```

The final model gives: "The content of /tmp/a.txt is hello; Hangzhou is currently 26°C and sunny."

> **Complete chain**: MCP native tool name (`read_file`) → middleware concatenates alias (`fs__read_file`) → the model sees and returns the alias → `ToolService` locates the executor by alias → strip prefix and restore the original name → send to the corresponding Server's `tools/call`.

---

## 5.2 Transport Implementation and Protocol Versions

### 5.2.1 stdio (Newline Framing)

- `ProcessBuilder` spawns a child process, `stdin` / `stdout` framed by **newlines in UTF-8**: each JSON-RPC message on one line, with embedded newlines removed before sending;
- `stderr` is continuously drained to the log by a background daemon thread, to avoid the pipe filling up and blocking;
- Closing: close `stdin` → `destroy()` → wait 2s → `destroyForcibly()`.

### 5.2.2 Streamable HTTP (Reusing the Built-in `HttpTransport`)

- Single endpoint, POST only; reuses `JdkHttpTransport` and the built-in `Json`;
- Request headers: `Content-Type: application/json`, `Accept: application/json, text/event-stream`; after the handshake, `MCP-Protocol-Version` is appended, and other custom headers (such as `Authorization`) are passed through as-is;
- Response: `application/json` (a single JSON-RPC message) or request-level `text/event-stream` (parse `data:` events, select the matching response by `id`);
- **P0 boundary**: `HttpResponse` does not expose response headers, so `Mcp-Session-Id` is not returned for now (usable for stateless Servers; stateful sessions are P1).

> **Note**: `JdkHttpTransport` is based on `HttpURLConnection` and does not support `PATCH`; MCP itself does not use PATCH, so there is no impact.

### 5.2.3 Current State of Protocol Version Support

- **Implemented (P0)**: Legacy `initialize` handshake (default version `2025-06-18`, can be overridden with `protocolVersion(...)`), sending `notifications/initialized` after the handshake;
- **Not implemented (P1)**: Modern / `server/discover` auto-detection. The "dual-era detection" described in 4.2.3 is the target design; currently it works along the legacy path.

---

## 5.3 Official SDK Bridge Module (P3 Planned, Not Yet Implemented)

**Status**: `agentforge-mcp-sdk-bridge` is **not yet implemented**, and belongs to roadmap P3; its dependency isolation strategy is described in 4.2.3. After delivery, the exact Maven coordinates, assembly API, and configuration examples will be added, to avoid documentation not matching the code.

**Target form** (for alignment during implementation):

- A separate JDK 17 artifact, with one-way `bridge → core` dependency, still outputting `Map<ToolSpecification, ToolExecutor>`;
- The business side only replaces `McpToolFactory` with the official bridge factory, and everything else (`ToolService` / `ReActAgent`) stays unchanged;
- The **same Server should not** be registered with both the bridge and the non-bridge stack at the same time, to avoid tool name conflicts.

---

## 6. Verification Testing and Support Level

## 6.1 Verification and Testing

### 6.1.1 Quick Verification: stdio (Real Public Server)

The least hassle self-check—use the official filesystem Server to start a local child process, and run through "discovery → call → feed back":

```java
public class McpStdioQuickTest {

    @Test
    public void shouldListAndCallPublicFilesystemServer() throws Exception {
        Path dir = Files.createTempDirectory("mcp-demo");
        Path file = dir.resolve("a.txt");

        McpClient client = DefaultMcpClient.builder()
                .serverAlias("fs")
                .transport(StdioMcpTransport.of(
                        "npx", "-y", "@modelcontextprotocol/server-filesystem", dir.toString()))
                .build();
        try {
            ToolService toolService = new ToolService();
            toolService.tools(McpToolFactory.buildTools(client, "fs"));
            assertNotNull(toolService.toolExecutors().get("fs__write_file"));

            toolService.toolExecutors().get("fs__write_file").execute(
                    ToolExecutionRequest.builder().name("fs__write_file")
                            .arguments("{\"path\":" + Json.stringify(file.toString())
                                    + ",\"content\":\"hello mcp\"}")
                            .build(), null);
            String text = toolService.toolExecutors().get("fs__read_file").execute(
                    ToolExecutionRequest.builder().name("fs__read_file")
                            .arguments("{\"path\":" + Json.stringify(file.toString()) + "}")
                            .build(), null);
            assertTrue(text.contains("hello mcp"));
        } finally {
            client.close();
        }
    }
}
```

> This case has been delivered as a runnable test: `agentforge-agent-core/src/test/java/cloud/changlu/agentforge/agent/tool/mcp/real/McpRealStudioTest.java` (a real MCP Server + `ScriptedChatModel` driving the complete ReAct loop, runs by default, can be disabled with `-Dagentforge.mcp.live=false`).

### 6.1.2 Quick Verification: HTTP (Real Public Server)

Switch to the Streamable HTTP transport and the rest of the code is completely identical (the only difference is `transport`):

```java
public class McpHttpQuickTest {

    @Test
    public void shouldListPublicHttpServerTools() {
        McpClient client = DefaultMcpClient.builder()
                .serverAlias("wiki")
                .transport(StreamableHttpMcpTransport.builder()
                        .endpoint("https://mcp.deepwiki.com/mcp")
                        .build())
                .build();
        try {
            Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "wiki");
            assertFalse(tools.isEmpty()); // deepwiki actually exposes 3 tools
        } finally {
            client.close();
        }
    }
}
```

> **In one sentence**: stdio and HTTP **differ only by one `transport`**, and the usage of `McpToolFactory` / `ToolService` / `ReActAgent` is exactly the same.

> This case has been delivered as a runnable test: `agentforge-agent-core/src/test/java/cloud/changlu/agentforge/agent/tool/mcp/real/McpRealHttpTest.java` (real DeepWiki public service, discovery + real call to `read_wiki_structure`).

<br/>

### 6.1.3 Measured List of Public MCP Services

Measurement time: 2026-10-05, all are authentication-free public services:

| Form | Service | Start / Address | Measured tool count | Result |
|---|---|---|---|---|
| stdio | filesystem | `npx -y @modelcontextprotocol/server-filesystem <dir>` | 14 | ✅ |
| stdio | memory | `npx -y @modelcontextprotocol/server-memory` | 9 | ✅ |
| stdio | sequential-thinking | `npx -y @modelcontextprotocol/server-sequential-thinking` | 1 | ✅ |
| stdio | everything | `npx -y @modelcontextprotocol/server-everything` | 13 | ✅ |
| HTTP | deepwiki | `https://mcp.deepwiki.com/mcp` | 3 | ✅ |
| HTTP | context7 | `https://mcp.context7.com/mcp` | 2 | ✅ |
| HTTP | gitmcp | `https://gitmcp.io/{owner}/{repo}` | — | ⚠️ Requires `Mcp-Session-Id` (P1) |

- Typical tool names: filesystem → `read_file` / `write_file` / `list_directory`…; everything → `echo` / `get-sum` / `get-env`…; deepwiki → `ask_wiki_question` / `read_wiki_structure` / `read_wiki_contents`; context7 → `resolve-library-id` / `query-docs`.
- Reproduction commands:

```bash
# Public service scan (requires explicitly enabling live)
mvn test -Dagentforge.mcp.live=true \
  -Dtest=McpPublicServersLiveTest,McpStdioLiveTest,McpHttpLiveTest

# Real application tests of documentation cases (run by default, disable with -Dagentforge.mcp.live=false)
mvn test -Dtest='cloud.changlu.agentforge.agent.tool.mcp.real.**'
```

> **Note**: After `initialize`, `gitmcp` requires subsequent requests to return `Mcp-Session-Id`, and the current P0 stateless implementation reports `Bad Request: Mcp-Session-Id header is required`—this is exactly why stateful HTTP sessions are left to P1.

<br/>

### 6.1.4 Offline Unit Tests (No Real Server Needed)

Mock `McpClient` (`FakeMcpClient`) and inject fixed tools and results to run offline:

```java
FakeMcpClient client = new FakeMcpClient("demo",
        Collections.singletonList(new McpTool("get_weather", "查询天气", weatherSchema())));
Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "demo");
assertEquals("demo__get_weather", tools.keySet().iterator().next().name());
```

Covered: `JsonRpcCodec` encoding/decoding, `tools/list` mapping, name disambiguation, white/blacklist filtering, `tools/call` parameter pass-through, `isError` routing, protocol / transport error failure-ization, handshake timing, both HTTP JSON and SSE response forms; plus an end-to-end case with a real `JdkHttpTransport` + local HTTP Server.

<br/>

### 6.1.5 Risks and Conformance

- **Stateful HTTP**: requires returning `Mcp-Session-Id` (gitmcp's measured limitation is exactly this), to be filled in at P1.
- **Spec follow-up**: In-house needs to continuously follow the revisions and deprecations of `2024-11-05 → 2026-07-28`; self-test the message structure against the official **MCP Inspector** / **conformance**.
- **Dual-era complexity**: `initialize` coexists with per-request `_meta`, and detection and caching must be done correctly (P1).
- **HTTP streaming**: parsing and cancellation of request-level SSE (cancellation by closing the stream) must be implemented correctly.
- **Security**: Tool Poisoning, approval (human-in-the-loop), and credential management are indispensable.

---

## 6.2 Support Level Tiers and Roadmap

| Stage | Capability | Transport | Description |
|---|---|---|---|
| **P0** | `tools/list` + `tools/call` | stdio / Streamable HTTP | Minimum viable: discovery + execution |
| **P0** | Name disambiguation / tool filtering / metadata | — | `serverAlias__` prefix, whitelist |
| **P0** | Error routing (isError / protocol error) | — | Return to model for self-correction |
| **P1** | Dual-era compatibility + timeout/cancellation + close/restart | stdio / HTTP | Stability |
| **P1** | `notifications/tools/list_changed` hot update | stdio / HTTP | Tool table refresh |
| **P1** | Resources / Prompts (read) | stdio / HTTP | `resources/read`, `prompts/get` |
| **P2** | MRTR (`InputRequiredResult`) | stdio / HTTP | Interactive input |
| **P2** | `subscriptions/listen` | HTTP | Change subscription |
| **P2** | OAuth 2.1 / OIDC | HTTP | Remote authentication |
| **P2** | `x-mcp-header` mirroring | HTTP | Gateway routing |
| **P3** | Server side (exposing AgentForge `@Tool` as MCP) | stdio / HTTP | Reverse empowerment |
| **P3** | Official SDK bridge module (JDK17) | All | Optional conformance solution |

### 6.2.1 Consistency with `http` / `local` Modes

| Dimension | `local` | `http` | `mcp` (this article) |
|---|---|---|---|
| Factory | `LocalToolFactory` | `HttpToolFactory` | `McpToolFactory` |
| Executor | `LocalToolExecutor` | `HttpToolExecutor` | `McpToolExecutor` |
| Discovery | Scan `@Tool` | Read configuration | `tools/list` dynamic |
| Schema | Reflection-generated | Configuration-generated | Delivered by Server |
| Error | `ToolExecutionResult` | Text "HTTP request failed" | `isError` lossless mapping |
| Subpackage | `support/` | `domain/enums/support/parser/` | `domain/transport/support/` |

> **Key point**: The MCP mode is orthogonal to the two existing modes and can be registered into the same `ToolService` at the same time, so the model sees a unified tool list.

---

## 7. Summary and Outlook

## 7.1 Summary and Outlook

1. **Research conclusion**: All three support stdio / HTTP; **LangChain4j is in-house, Spring AI and AgentScope are based on the official MCP Java SDK**; the official SDK is the de facto standard but requires JDK 17+.
2. **AgentForge's choice**: **In-house core protocol stack (Java 8 / zero dependencies, delivered) + optional official SDK bridge (P3 planned)**.
3. **Integration point**: `McpToolExecutor implements ToolExecutor` + `McpToolFactory`, seamlessly merged into `ToolService` and the ReAct loop.
4. **Support cadence**: P0 connects stdio / Streamable HTTP's `tools/list` + `tools/call`; P1 adds hot update and resource prompts; P2 adds MRTR / subscriptions / OAuth; P3 reverse Server and official bridge.
5. **Outlook**: As the spec evolves, fill in MRTR, `subscriptions/listen`, and OAuth hardening, and explore reverse-exposing AgentForge's `@Tool` as an MCP Server.

---

## References

[1]. [MCP Official Specification (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28)

[2]. [MCP Versioning and Compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/lifecycle)

[3]. [MCP Streamable HTTP Transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)

[4]. [MCP stdio Transport](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)

[5]. [MCP Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)

[6]. [Official MCP Java SDK Documentation](https://java.sdk.modelcontextprotocol.io/latest/)

[7]. [MCP Java SDK (GitHub)](https://github.com/modelcontextprotocol/java-sdk)

[8]. [LangChain4j MCP Documentation](https://docs.langchain4j.dev/tutorials/mcp/)

[9]. [langchain4j-mcp Module (GitHub)](https://github.com/langchain4j/langchain4j/tree/main/langchain4j-mcp)

[10]. [Spring AI MCP Overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)

[11]. [Spring AI MCP Client Boot Starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)

[12]. [Spring AI MCP Server Boot Starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

[13]. [AgentScope Java MCP Documentation](https://java.agentscope.io/v1/en/docs/task/mcp)

[14]. [AgentScope Java (GitHub)](https://github.com/agentscope-ai/agentscope-java)

[15]. [MCP Conformance Testing conformance](https://github.com/modelcontextprotocol/conformance)

[16]. [MCP Inspector](https://github.com/modelcontextprotocol/inspector)

<br/>

Organizer: Changlu  Created: 2026.10.5  Updated: 2026.10.5
