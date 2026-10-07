---
title: "MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现"
date: 2026-10-10
tags: [AgentForge, 通用ReActagent层, Function Calling, MCP]
---

#  MCP 框架调研与 AgentForge 接入实现

> 本文按「**背景/问题引入 → 核心概念 → 主流框架调研 → 实现思路 → 实战代码 → 验证测试 → 总结**」递进展开，共七个部分。

{/* truncate */}


<br/>

一句话结论：三方中**只有 LangChain4j 自研协议栈，Spring AI 与 AgentScope Java 都基于官方 MCP Java SDK**；而 **AgentForge 选择"核心自研协议栈 + 官方 SDK 可选桥接"**。下面把这一结论的来龙去脉讲清楚。

---

## 一、背景与问题引入

## 1.1、场景驱动：一个真实的接入难题

在为 AgentForge 规划 MCP 生态接入时，我们就遇到了这样一个具体问题：

> 团队希望 AgentForge 的 `ReActAgent` 能直接吃下 MCP 生态里现成的工具（filesystem、git、fetch、数据库……）。但 AgentForge 有一条硬约束——**Java 8 兼容、核心零第三方依赖**。而放眼 Java 生态，Spring AI 与 AgentScope 都选了官方 MCP Java SDK，那个 SDK 却要求 **JDK 17+**，还会带进 Jackson、Reactor、SLF4J。  
> 于是问题来了：**我们到底该不该跟官方 SDK？**

这不是 AgentForge 一家的困惑。任何"低 JDK / 零依赖"取向的框架，在接入 MCP 时都会撞上同一堵墙。

---

## 1.2、问题引导：我们要回答什么？

1. **三个主流 Java 框架，stdio 与 HTTP 支不支持？支持到什么程度？**<br/>
2. **它们的协议是"自己手写"还是"基于官方 MCP Java SDK"？**<br/>
3. **AgentForge 应该怎么选？自研还是官方？支持到什么程度？**<br/>
4. **落地后，如何与 AgentForge 既有的 `http` / `local` 工具模式统一？**

---

## 1.3、调研范围与对照基线

调研对象：**LangChain4j**、**Spring AI**、**AgentScope Java**。对照基线是官方 **MCP Java SDK**。

| 项 | 官方 MCP Java SDK |
|---|---|
| 坐标 | `io.modelcontextprotocol.sdk:mcp`（BOM：`mcp-bom`） |
| 当前版本 | **2.0.1** |
| JDK | **17+** |
| 编程模型 | 同步 + 异步（Reactor） |
| JSON / 日志 | Jackson（可插拔）/ SLF4J |
| Client 传输 | **STDIO、Streamable HTTP、SSE（legacy）** |
| Server 传输 | **STDIO、Streamable-HTTP、Stateless Streamable-HTTP、SSE** |
| 架构分层 | Client/Server → Session → Transport 三层 |

---

## 二、核心概念

## 2.1、两条技术路线：官方 SDK vs 自研

### 2.1.1、路线一：基于官方 Java SDK

协议细节（JSON-RPC、版本协商、传输分帧）由官方维护，框架只做**薄封装 + 生态整合**。

- **优点**：省心、易过一致性测试、能力齐全、规范跟进快。
- **弊端说明**：JDK 17+、引入 Jackson / Reactor / SLF4J，难以嵌入低 JDK / 零依赖项目。

### 2.1.2、路线二：自研协议栈

框架自己实现 `McpTransport` / `McpClient` / JSON-RPC 路由。

- **优点**：完全掌控依赖与 API，可兼容更低 JDK、零额外依赖。
- **弊端说明**：需自行跟进规范修订、做双时代兼容与一致性验证。

> **重点**：这两条路线没有绝对优劣，只取决于框架自身的**约束取向**——这正是三方分野、也是 AgentForge 决策的根因。

---

## 2.2、官方 SDK 的三层架构（对照）

```text
Client/Server 层   协议操作（listTools / callTool / initialize）
        ↓
Session 层         通信模式与连接状态
        ↓
Transport 层        JSON-RPC 收发与序列化（STDIO / HTTP / SSE）
```

<br/>

## 2.3、三方速览

| 框架 | stdio | HTTP | 实现方式 | 官方 SDK |
|---|---|---|---|---|
| **LangChain4j** | ✅ | ✅ Streamable HTTP（+ WebSocket / Docker） | **自研协议栈** | ❌ 不使用 |
| **Spring AI** | ✅ | ✅ Streamable / Stateless / SSE（WebMVC / WebFlux） | **基于官方 SDK** | ✅ 依赖 1.0+ |
| **AgentScope Java** | ✅ | ✅ Streamable HTTP / SSE | **基于官方 SDK** | ✅ 依赖 |

---

## 三、主流框架调研

## 3.1、LangChain4j：自研协议栈

### 3.1.1、它到底用没用官方 SDK？

MCP 能力在 `dev.langchain4j:langchain4j-mcp` 模块。查其 `pom.xml` 依赖：`langchain4j`、`langchain4j-core`、`jackson-databind`、`langchain4j-http-client(-jdk)`、`langchain4j-open-ai`。**没有 `io.modelcontextprotocol.sdk`**——即协议是自己实现的。

> **重点**：它是三方中**唯一自研**的（自有 `McpTransport` / `DefaultMcpClient` / `McpOperationHandler`）。

<br/>

### 3.1.2、stdio 支持

- `StdioMcpTransport`：`ProcessBuilder` 起子进程，按行读写 JSON-RPC。
- 独立模块 `langchain4j-mcp-docker` 提供 `DockerMcpTransport`（容器形态的 stdio server）。
- Server 侧（构建 stdio server）放在 **LangChain4j Community**。

<br/>

### 3.1.3、HTTP 支持

- `StreamableHttpMcpTransport`：基于 JDK `HttpClient`，POST 单一 endpoint；可选 `subsidiaryChannel(true)` 打开 GET SSE 支流（legacy）。
- `HttpMcpTransport`：旧 HTTP+SSE，已 `@Deprecated(forRemoval)`。
- `WebSocketMcpTransport`：非标准额外传输，兼容 Quarkus MCP Server 扩展。

<br/>

### 3.1.4、协议版本与工具治理

- 同时支持 **legacy（2025-11-25）与 modern（2026-07-28）**，默认自动探测（`server/discover` → 失败回退 `initialize`）。
- 工具能力：`McpToolProvider` 支持按名过滤、`toolNameMapper`、`toolSpecificationMapper`、多 client 聚合；`DefaultMcpClient` 自带工具列表缓存；支持 `x-mcp-header`、`_meta` 注入、资源订阅。
- 另有 **MCP Registry** 只读客户端。

---

## 3.2、Spring AI：基于官方 Java SDK

### 3.2.1、它到底用没用官方 SDK？

Spring AI MCP **明确采用官方 MCP Java SDK**，其文档直接给出 SDK 的三层架构。自 **Spring AI 2.0** 起要求官方 SDK **1.0.0+**。

> **注意（迁移点）**：`mcp-spring-webflux` / `mcp-spring-webmvc` 从 `io.modelcontextprotocol.sdk` **迁到了** `org.springframework.ai`；相关传输类包名也从 `io.modelcontextprotocol.*` 变为 `org.springframework.ai.mcp.*`。

<br/>

### 3.2.2、stdio 支持

- Client：`spring-ai-starter-mcp-client` 提供 STDIO。
- Server：`spring-ai-starter-mcp-server`（`spring.ai.mcp.server.stdio=true`）。

<br/>

### 3.2.3、HTTP 支持

- Client：`spring-ai-starter-mcp-client`（Servlet 版 Streamable-HTTP / Stateless / SSE）、`spring-ai-starter-mcp-client-webflux`（WebFlux 版）。
- Server：`spring-ai-starter-mcp-server-webmvc` / `-webflux`，用 `spring.ai.mcp.server.protocol = SSE | STREAMABLE | STATELESS` 切换。

<br/>

### 3.2.4、注解与治理

- Server 注解：`@McpTool`、`@McpResource`、`@McpPrompt`、`@McpComplete`。
- Client 注解：`@McpLogging`、`@McpSampling`、`@McpElicitation`、`@McpProgress`。
- 工具适配：`SyncMcpToolCallback` / `AsyncMcpToolCallback`、`SyncMcpToolCallbackProvider`、`McpToolUtils`。

> **重点**：Spring AI 自己不实现协议，而是**薄封装官方 SDK + 补齐 Spring Boot 体验**（Starter / 注解 / WebMVC·WebFlux 传输）。

---

## 3.3、AgentScope Java：基于官方 Java SDK

### 3.3.1、它到底用没用官方 SDK？

AgentScope Java 的 `McpClientBuilder` 内部**复用官方 `io.modelcontextprotocol` SDK 的传输与客户端类**，最终包装成 `McpAsyncClient` / `McpSyncClient`，并注入 AgentScope 的版本与 User-Agent。

<br/>

### 3.3.2、stdio 支持

```java
McpClientWrapper client = McpClientBuilder.create("filesystem-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync().block();
```

<br/>

### 3.3.3、HTTP 支持

- `sseTransport(url)`：SSE（有状态流式）。
- `streamableHttpTransport(url)`：Streamable HTTP（无状态流式）。
- 支持 `.header(...)` / `.queryParam(...)`、`.timeout(...)`、`.initializationTimeout(...)`、`.protocolVersions(...)`、`elicitation` 回调。

<br/>

### 3.3.4、工具治理

- `Toolkit.registerMcpClient(...)` 统一注册；`enableTools` / `disableTools` 白黑名单；`group(...)` 工具分组；`removeMcpClient(...)` 动态摘除。
- 命名空间惯例：`mcp__{server}__{tool}`。
- 另有 Higress AI 网关扩展（语义化工具检索）。

> **注意**：默认只声明 `2024-11-05`，连较新 Server 需显式 `protocolVersions(...)`，否则报 "Unsupported protocol version"。

---

## 3.4、横向对比

### 3.4.1、stdio / HTTP 支持矩阵

| 能力 | LangChain4j | Spring AI | AgentScope Java |
|---|---|---|---|
| Client stdio | ✅ | ✅ | ✅ |
| Client Streamable HTTP | ✅ | ✅（Servlet / WebFlux） | ✅ |
| Client SSE（legacy） | ✅（弃用中） | ✅ | ✅ |
| Server stdio | ⚠️（Community） | ✅ | —（偏客户端） |
| Server Streamable HTTP | ⚠️（Community） | ✅（WebMVC / WebFlux / Stateless） | — |
| 额外传输 | WebSocket / Docker | — | — |

### 3.4.2、实现方式与约束

| 维度 | LangChain4j | Spring AI | AgentScope Java |
|---|---|---|---|
| 协议实现 | **自研** | 官方 SDK | 官方 SDK |
| 官方 SDK 版本 | 不依赖 | 1.0.0+ | 依赖 `io.modelcontextprotocol` |
| JDK/依赖约束 | 跟随 LangChain4j | Spring + SDK（JDK17+） | SDK（JDK17+） |
| 协议版本 | legacy + modern 自动探测 | 跟随 SDK | 需显式声明版本 |
| 工具治理 | 过滤 / 改名 / 资源当工具 | Starter + 注解 | 过滤 / 分组 / 网关检索 |
| Registry | ✅ 只读客户端 | 部分 | — |

---

## 3.5、对比分析与选型建议

### 3.5.1、三种选型场景

- **方案一：已是 Spring Boot 应用** → Spring AI。官方 SDK + Starter 最省事，但被 Spring 生态绑定。
- **方案二：多 Agent 编排 / 要工具治理与网关** → AgentScope Java。工具分组、命名空间、Higress 网关检索最完整。
- **方案三：已用 LangChain4j 或要脱离官方 SDK 约束** → LangChain4j。自研协议栈、依赖可控。

> **弊端说明**：三者都直接或间接**要求 JDK 17+ 并引入第三方库**。凡是"低 JDK / 零依赖"的项目，三条都不满足——只能自研。

<br/>

### 3.5.2、小结

- **传输层面**：三者 stdio 与 HTTP（Streamable HTTP）都支持。
- **实现层面**：**LangChain4j 自研，Spring AI 与 AgentScope 基于官方 SDK**。
- **推论**：官方 SDK 是事实标准，但它把 JDK 门槛抬到 17——这正是 AgentForge 需要单独决策的地方。

---

## 四、AgentForge 实现思路

## 4.1、AgentForge 现状与约束

### 4.1.1、硬约束

| 约束 | 说明 |
|---|---|
| JDK | `maven.compiler.source/target = 8`（API 面向 Java 8） |
| 依赖 | 核心模块**零第三方依赖**：无 Jackson / OkHttp / SLF4J / Lombok / Reactor |
| JSON | 内置 `cloud.changlu.agentforge.model.internal.json.Json` |
| HTTP | 内置 `HttpTransport` / `HttpRequest` / `HttpResponse` / `JdkHttpTransport` |
| 日志 | `java.util.logging` |

> **重点**：官方 MCP Java SDK 要求 **JDK 17+** 并引入 **Jackson + Reactor + SLF4J**，与 AgentForge 核心约束**直接冲突**。

<br/>

### 4.1.2、可复用的工具抽象

| 已有类型 | 作用 | 与 MCP 的对应 |
|---|---|---|
| `ToolExecutor`（`execute` / `executeWithResult`） | 工具执行接口 | `tools/call` |
| `ToolExecutionRequest` | `name` + `arguments`(JSON) | `tools/call` 入参 |
| `ToolExecutionResult` | `text` / `result` / `isError` | `content` / `structuredContent` / `isError` |
| `ToolSpecification` | 工具声明 | `tools/list` 的 tool |
| `ToolParameters` | 原始 JSON Schema（Map） | `inputSchema` |
| `ToolService` | 工具注册与循环 | 工具表 |

> **重点**：MCP 与 AgentForge 工具抽象**天然同构**，接入落点就是 `ToolExecutor` 的实现。

<br/>

### 4.1.3、既有模式规范

`agent-core` 已有两种工具模式，遵循同一套「工厂 + 执行器 + 子包」：

```text
tool/local/   LocalToolFactory + LocalToolExecutor + support/
tool/http/    HttpToolFactory  + HttpToolExecutor  + domain/ enums/ support/ parser/
tool/mcp/     （本文新增，对齐上述规范）
```

---

## 4.2、实现思路：官方 SDK vs 自研

### 4.2.1、方案对比

**方案一：基于官方 MCP Java SDK**

- 优点：协议官方维护、易过一致性测试、能力齐全。
- 弊端说明：**JDK 17+**、引入 Jackson / Reactor / SLF4J，破坏核心零依赖；需额外桥接层适配 `ToolExecutor`。

**方案二：自研协议栈**

- 优点：**JDK 8 兼容、零额外依赖**、与 `ToolExecutor` 无缝、复用内置 `Json` 与 `HttpTransport`。
- 弊端说明：需自行跟进规范修订、做双时代兼容与一致性验证。

**方案三：混合（核心自研 + 官方桥接）**

- 核心（`agentforge-agent-core`）自研协议栈，保持零依赖（**已落地**）；
- 另设**可选模块** `agentforge-mcp-sdk-bridge`（JDK 17），把官方 SDK 的 `McpClient` 包装成 AgentForge 的 `ToolExecutor`（**P3 规划，尚未实现**）。

> **结论**：目标形态采用 **方案三**——主路径自研先行落地，官方 SDK 作为可选桥接后续补齐，既守住核心约束，又不把用户锁死在自研协议栈上。

<br/>

### 4.2.2、决策依据

| 维度 | 自研 | 官方 SDK |
|---|---|---|
| JDK | 8 | 17+ |
| 依赖 | 无 | Jackson/Reactor/SLF4J |
| 与 `ToolExecutor` | 直接实现 | 需桥接 |
| 规范跟进 | 自己跟 | 官方跟 |
| 一致性测试 | 自己做 | 官方做 |
| **AgentForge 适配度** | **高** | 低（需隔离模块） |

<br/>

### 4.2.3、桥接如何解决 JDK 17 冲突

**疑问**：官方 SDK 要求 JDK 17，那设一个"官方桥接"模块，岂不是把 JDK 17 又带回来了？JDK 8 到底还能不能用 MCP？

**先厘清**：MCP 是 JSON-RPC 协议，**与 JDK 无关**；JDK 17 是"官方 Java SDK 这个实现"的门槛，不是 MCP 的门槛。JDK 8 完全能实现 MCP——`ProcessBuilder`（stdio）+ `HttpURLConnection`（HTTP）+ 内置 `Json` 即可，无需 Jackson / Reactor。

**关键原则：依赖单向、字节码向上兼容。** JDK 17 编译的模块可以依赖 Java 8 编译的模块，反之不行。所以让 **bridge 依赖 core**，而不是 core 依赖 bridge：

```text
agentforge-agent-core（release 8 / 零依赖）
   ▲  定义 ToolExecutor / McpClient / ToolSpecification
   │  ← 只被依赖，不含任何 MCP 实现
agentforge-mcp-sdk-bridge（release 17）
   依赖 core + 官方 SDK + Jackson / Reactor / SLF4J
   把官方 McpClient 包装成 core 的 ToolExecutor
```

于是 JDK 17 类型、Jackson、Reactor、SLF4J 全被关在 bridge 内部，**永远不会出现在 core 的编译或运行路径上**。

<br/>

**JDK 版本 × 实现路径**

| 运行环境 | 自研协议栈（core 内置） | 官方 SDK 桥接（独立模块） |
|---|---|---|
| JDK 8 | ✅ 唯一选择 | ❌ 用不了（SDK 需 17） |
| JDK 17+ | ✅ 仍可用（Java 8 字节码向上兼容，零依赖优势保留） | ✅ 可选，显式引入才生效 |

> **重点**：自研栈**不是 JDK 8 专用**，它在 JDK 17 / 21 上同样运行；官方桥接是"opt-in"，**不是按 JDK 版本自动切换**。两条路是**部署期二选一**，由用户决定。

<br/>

**隔离手段**

- **构建隔离**：多模块工程，core 用 `maven.compiler.release=8`，bridge 用 `17`；**core 的 POM 绝不声明 bridge**，故 Jackson / Reactor / SLF4J 进不了 core 的依赖树。
- **运行隔离**：bridge 是独立 artifact，JDK 8 用户不引入即永不加载，规避 `UnsupportedClassVersionError`。
- **接口隔离**：core 只暴露 Java 8 的 `ToolExecutor` / `McpClient`，bridge 内部兼容官方类型，向上只实现 core 接口。
- **可选懒加载**：用 Java 8 自带的 `java.util.ServiceLoader` 定义 `McpClientFactory` SPI——有 bridge 则发现，无则回落到自研 `DefaultMcpClient`，core 始终零依赖。

> **结论**：桥接并非"在 Java 8 上跑 JDK 17 SDK"，而是给"愿意升 JDK 17 且想要官方能力"的用户开的一扇**可选、受隔离**的门；不升 JDK 17 的用户完全走自研栈，两条路各自干净。

> **当前进度**：自研协议栈已实现；`agentforge-mcp-sdk-bridge` 与 `McpClientFactory` SPI 均为 **P3 规划，尚未落地**。

<br/>

---

## 4.3、总体架构与分包

### 4.3.1、目录结构

```text
agentforge-agent-core/src/main/java/cloud/changlu/agentforge/agent/tool/
├── mcp/
│   ├── McpToolFactory.java         # 构建入口：listTools → Map<ToolSpecification, ToolExecutor>
│   ├── McpToolExecutor.java        # implements ToolExecutor：tools/call
│   ├── McpClient.java              # 客户端接口：serverAlias / listTools / callTool / close
│   ├── DefaultMcpClient.java       # JSON-RPC 生命周期（initialize 握手、惰性初始化）
│   ├── McpProtocolException.java   # JSON-RPC error → 异常
│   ├── McpTransportException.java  # 传输失败异常
│   ├── domain/
│   │   ├── McpTool.java
│   │   ├── McpCallToolResult.java
│   │   ├── McpContent.java
│   │   ├── McpServerInfo.java
│   │   └── McpCapabilities.java
│   ├── transport/
│   │   ├── McpTransport.java       # send / request / close 的 SPI
│   │   ├── StdioMcpTransport.java
│   │   └── StreamableHttpMcpTransport.java
│   └── support/
│       ├── JsonRpcCodec.java
│       ├── McpJson.java
│       ├── McpToolSpecificationMapper.java
│       └── McpResultConverter.java
```

### 4.3.2、分层职责

```text
┌──────────────────────────────────────────────┐
│ McpToolFactory / McpToolExecutor              │  对接 Agent 工具表
├──────────────────────────────────────────────┤
│ DefaultMcpClient（id 递增、握手、惰性初始化）  │  协议层
├──────────────────────────────────────────────┤
│ McpTransport（stdio / Streamable HTTP）        │  传输层
└──────────────────────────────────────────────┘
```

---

## 4.4、核心设计与映射

### 4.4.1、`McpToolExecutor implements ToolExecutor`

```java
public class McpToolExecutor implements ToolExecutor {

    private final McpClient client;        // 构造期绑定：这个工具属于哪个 MCP Server
    private final String remoteToolName;   // 构造期绑定：Server 上的原始工具名（不带 alias 前缀）

    public McpToolExecutor(McpClient client, String remoteToolName) {
        this.client = client;
        this.remoteToolName = remoteToolName;
    }

    /** 简化接口：只回文本。 */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        // 步骤①：模型给的 arguments 是 JSON 字符串 → 还原为 Map
        Map<String, Object> args = McpJson.argumentsAsMap(request.arguments());
        // 步骤②：用绑定的原始远程名发 tools/call，并把 content 拍成文本
        return McpResultConverter.toText(client.callTool(remoteToolName, args));
    }

    /** 结构化接口：额外携带 structuredContent 与 isError。 */
    @Override
    public ToolExecutionResult executeWithResult(ToolExecutionRequest request, Object memoryId) {
        try {
            // 步骤①：JSON 字符串 → Map（嵌套对象/数组保持原样）
            Map<String, Object> args = McpJson.argumentsAsMap(request.arguments());
            // 步骤②：定位"发往哪个 Server、调哪个工具"——由构造期绑定的两个字段决定
            McpCallToolResult result = client.callTool(remoteToolName, args);
            // 步骤③：无损映射回 AgentForge 结果
            return ToolExecutionResult.builder()
                    .isError(result.isError())                    // isError 原样透传
                    .result(result.structuredContent())           // structuredContent → result
                    .text(McpResultConverter.toText(result))      // content 文本 → text（回灌模型）
                    .build();
        } catch (McpProtocolException e) {                        // JSON-RPC error（如 -32601）
            return ToolExecutionResult.failure("MCP protocol error: " + e.getMessage(), e);
        } catch (McpTransportException e) {                       // 传输失败 / 超时
            return ToolExecutionResult.failure("MCP transport error: " + e.getMessage(), e);
        }
    }
}
```

<br/>

### 4.4.2、`McpToolFactory`：List → Spec + Executor

**核心步骤**：① 解析 alias（显式优先，否则 `client.serverAlias()`）→ ② 拉取原生工具（`tools/list`）→ ③ 白/黑名单过滤 → ④ 拼 alias 生成模型可见名 → ⑤ 绑定 `McpToolExecutor(client, 原始名)` → ⑥ 交 `ToolService` 按 `spec.name()` 建表。

```java
public final class McpToolFactory {

    private McpToolFactory() {}

    /** 用 client 自身的 serverAlias 作为前缀。 */
    public static Map<ToolSpecification, ToolExecutor> buildTools(McpClient client) {
        return buildTools(client, null, null, null);
    }

    /** 显式 alias 优先，空白时回落到 client.serverAlias()。 */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client, String serverAlias) {
        return buildTools(client, serverAlias, null, null);
    }

    /** enabledTools 非空时只暴露其中的远程工具名；disabledTools 命中的被隐藏。 */
    public static Map<ToolSpecification, ToolExecutor> buildTools(
            McpClient client, String serverAlias,
            Set<String> enabledTools, Set<String> disabledTools) {
        // 步骤①：确定前缀——显式 alias 优先，否则读 client.serverAlias()
        String alias = (serverAlias != null && !serverAlias.trim().isEmpty())
                ? serverAlias : client.serverAlias();

        Map<ToolSpecification, ToolExecutor> result = new LinkedHashMap<>();

        // 步骤②：拉取 Server 原生工具（首次会触发 initialize → initialized → tools/list）
        for (McpTool tool : client.listTools()) {

            // 步骤③：白/黑名单过滤（按"原始工具名"判定）
            if (enabledTools != null && !enabledTools.isEmpty()
                    && !enabledTools.contains(tool.name())) {
                continue;
            }
            if (disabledTools != null && disabledTools.contains(tool.name())) {
                continue;
            }

            // 步骤④：拼 alias 生成模型可见名；inputSchema 原样透传；写入归属元数据
            ToolSpecification spec = ToolSpecification.builder()
                    .name(qualify(alias, tool.name()))          // fs__read_file
                    .description(tool.description())
                    .parameters(McpToolSpecificationMapper.toToolParameters(tool.inputSchema()))
                    .addMetadata("mcp.server", alias == null ? "" : alias)
                    .addMetadata("mcp.tool", tool.name())
                    .build();

            // 步骤⑤：绑定 (client + 原始远程名)——运行期据此路由回对应 Server
            result.put(spec, new McpToolExecutor(client, tool.name()));
        }
        // 步骤⑥：返回 spec→executor 映射，交给 ToolService.tools(...) 按 spec.name() 建表
        return result;
    }

    private static String qualify(String alias, String toolName) {
        return (alias == null || alias.trim().isEmpty()) ? toolName : alias + "__" + toolName;
    }
}
```

<br/>

### 4.4.3、Schema 映射（零损耗）

```java
public static ToolParameters toToolParameters(Map<String, Object> inputSchema) {
    if (inputSchema == null || inputSchema.isEmpty()) {
        return ToolParameters.empty();      // {"type":"object","properties":{}}
    }
    return ToolParameters.from(inputSchema); // 原样透传 JSON Schema
}
```

<br/>

### 4.4.4、错误映射

| MCP | AgentForge |
|---|---|
| `result.isError = true` | `ToolExecutionResult.isError(true)` + 文本回给模型自纠 |
| JSON-RPC `error`（`-32601/-32602`…） | `McpProtocolException` → 失败结果 / 错误处理器 |
| `-32022` 版本不支持 | 抛 `McpProtocolException`（自动重选版本为 P1） |
| 传输异常 / 超时 | `McpTransportException` → 失败结果 + 告警 |

---

## 五、AgentForge 实战代码

## 5.1、最小接入：两种传输

### 5.1.1、接入本地 stdio Server

```java
// 1) 启动 stdio MCP Server（本地子进程，换行分帧的 JSON-RPC）
McpClient fs = DefaultMcpClient.builder()
        .serverAlias("fs")
        .transport(StdioMcpTransport.builder()
                .command("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
                .build())
        .build();

// 2) 拉取工具并注册进 ToolService（读取 client 的 serverAlias，自动加前缀：fs__read_file）
ToolService toolService = new ToolService();
toolService.tools(McpToolFactory.buildTools(fs));

// 3) 与本地 @Tool 共存（同一张工具表）
toolService.tools(new WeatherTools());

// 4) 装配 ReActAgent
ReActAgent agent = ReActAgent.builder()
        .chatModel(chatModel)
        .toolService(toolService)
        .build();
```

> **重点**：`DefaultMcpClient` 是**惰性**的——首次 `listTools()` / `callTool()` 时才发 `initialize` 握手与 `notifications/initialized`，随后才发 `tools/list`。`StdioMcpTransport.of("npx", "-y", ...)` 是等价的简写。

<br/>

### 5.1.2、接入远程 Streamable HTTP Server

```java
McpClient remote = DefaultMcpClient.builder()
        .serverAlias("github")
        .transport(StreamableHttpMcpTransport.builder()
                .endpoint("https://mcp.example.com/mcp")
                .header("Authorization", "Bearer " + token) // 自定义头随请求透传
                .build())
        .build();

toolService.tools(McpToolFactory.buildTools(remote)); // 读取 client 的 serverAlias，工具名形如 github__search_repos
```

**与 stdio 的差异只在传输层**：`serverAlias="github"` → 模型可见名 `github__search_repos`；`buildTools` / `ToolService` / `ToolExecutor` 的定位与参数转换**完全一致**。HTTP 侧每次 `tools/call` 就是一次 POST：

- 请求体 = 上述 JSON-RPC（`{"jsonrpc","id","method":"tools/call","params":{...}}`）；
- 握手后自动带 `MCP-Protocol-Version`，自定义头（`Authorization`）原样透传；
- 响应按 `id` 取出（`application/json` 或 SSE `data:`），再走同一套 `content` / `isError` 映射。

<br/>

### 5.1.3、按需过滤与关闭

```java
// 只暴露 read_file，屏蔽其余工具（白名单为空/null 表示全部）
Map<ToolSpecification, ToolExecutor> tools =
        McpToolFactory.buildTools(
                client, "fs",
                Collections.singleton("read_file"), // 白名单
                null);                              // 黑名单

client.close(); // stdio：关闭并回收子进程；HTTP：释放传输
```

<br/>

### 5.1.4、底层核心原理：一句提问如何被闭环（真实 function call 串）

场景：MCP `fs`（filesystem）与 MCP `weather` 同时注册，用户问「把 /tmp/a.txt 内容读出来，并查一下杭州天气」。下面**按真实发生顺序**展开。

**① MCP Server 先返回原生 `tools/list`（无前缀）**

AgentForge 先对每个 Server 调 `tools/list`，拿到的是**工具本名**（此时还没有 `fs__` 前缀）：

```json
// ← fs server 的 tools/list 响应
{"jsonrpc":"2.0","id":1,"result":{"tools":[
  {"name":"read_file","description":"Read a file","inputSchema":
    {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}
]}}

// ← weather server 的 tools/list 响应
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"get_weather","description":"Query weather","inputSchema":
    {"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}}
]}}
```

**② 中间层拼装 alias（`McpToolFactory`）**

`buildTools(client)` 对上述**原始工具**做转换：`alias__原始名`，同时绑定 executor 并写入 `ToolService`：

```text
fs      + read_file    → spec.name = "fs__read_file"        + McpToolExecutor(fsClient,      "read_file")
weather + get_weather  → spec.name = "weather__get_weather" + McpToolExecutor(weatherClient, "get_weather")
```

> **注意**：**原始名始终保留在 executor 里**（`read_file` / `get_weather`），前缀只加在模型可见名上——这是后续"剥前缀、发原始名"的依据。

**③ 大模型看到的 `tools[]`（alias 形态）**

```json
[
  {"type": "function", "function": {"name": "fs__read_file",
    "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
  {"type": "function", "function": {"name": "weather__get_weather",
    "parameters": {"type": "object", "properties": {"location": {"type": "string"}}, "required": ["location"]}}}
]
```

**④ 模型返回的组合 function call（OpenAI 形态，一次两条）**

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

> **关键**：`arguments` 是**带转义的 JSON 字符串**，不是对象（Anthropic 则是 `tool_use.input` 对象）。AgentForge 把两者统一归一化为 `ToolExecutionRequest(id, name, arguments)`——`arguments` **始终是字符串**。

**⑤ 归一化 → ToolService 按 alias 定位 executor**

```text
ToolExecutionRequest{id="call_a1b2", name="fs__read_file",       arguments="{\"path\":\"/tmp/a.txt\"}"}
ToolExecutionRequest{id="call_c3d4", name="weather__get_weather", arguments="{\"location\":\"杭州\"}"}
```

- `ToolService` 用 `name` 命中 `toolExecutors`：`fs__read_file` → 绑定了 `fsClient` + `read_file` 的 `McpToolExecutor`；`weather__get_weather` → `weatherClient` + `get_weather`。

**⑥ 剥前缀 + 参数还原 → 发原始 `tools/call`**

- `McpJson.argumentsAsMap(arguments)` 把**字符串还原为对象**；
- 用 executor 里的**原始远程名**（剥掉 `fs__` / `weather__` 前缀）发出：

```json
// → fs server
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"read_file","arguments":{"path":"/tmp/a.txt"}}}

// → weather server
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"get_weather","arguments":{"location":"杭州"}}}
```

**⑦ 响应 → 回灌模型 → 最终答案**

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

最终模型给出：「/tmp/a.txt 内容是 hello；杭州当前 26℃、晴。」

> **完整链路**：MCP 原生工具名（`read_file`）→ 中间层拼 alias（`fs__read_file`）→ 模型看到并回传 alias → `ToolService` 按 alias 定位 executor → 剥前缀还原原始名 → 发到对应 Server 的 `tools/call`。

---

## 5.2、传输实现与协议版本

### 5.2.1、stdio（换行分帧）

- `ProcessBuilder` 起子进程，`stdin` / `stdout` 按 UTF-8 **换行分帧**：每条 JSON-RPC 一行，发送前剔除内嵌换行；
- `stderr` 由后台守护线程持续抽取到日志，避免管道写满而阻塞；
- 关闭：关 `stdin` → `destroy()` → 等 2s → `destroyForcibly()`。

### 5.2.2、Streamable HTTP（复用内置 `HttpTransport`）

- 单一 endpoint，仅 POST；复用 `JdkHttpTransport` 与内置 `Json`；
- 请求头：`Content-Type: application/json`、`Accept: application/json, text/event-stream`；握手后追加 `MCP-Protocol-Version`，其余自定义头（如 `Authorization`）原样透传；
- 响应：`application/json`（单条 JSON-RPC）或请求级 `text/event-stream`（解析 `data:` 事件，按 `id` 选出匹配响应）；
- **P0 边界**：`HttpResponse` 未暴露响应头，暂不回传 `Mcp-Session-Id`（无状态 Server 可用；有状态会话为 P1）。

> **注意**：`JdkHttpTransport` 基于 `HttpURLConnection`，不支持 `PATCH`；MCP 本身不用 PATCH，无影响。

### 5.2.3、协议版本支持现状

- **已实现（P0）**：Legacy `initialize` 握手（默认版本 `2025-06-18`，可用 `protocolVersion(...)` 覆盖），握手后发送 `notifications/initialized`；
- **未实现（P1）**：Modern / `server/discover` 自动探测。4.2.3 所述"双时代探测"为目标设计，当前按 legacy 路径工作。

---

## 5.3、官方 SDK 桥接模块（P3 规划，尚未实现）

**状态**：`agentforge-mcp-sdk-bridge` **当前尚未实现**，属路线图 P3；其依赖隔离策略见 4.2.3。落地后再补充确切的 Maven 坐标、装配 API 与配置样例，避免文档与代码不符。

**目标形态**（供实现时对齐）：

- 独立 JDK 17 artifact，`bridge → core` 单向依赖，输出仍是 `Map<ToolSpecification, ToolExecutor>`；
- 业务侧只把 `McpToolFactory` 替换为官方桥接工厂，其余（`ToolService` / `ReActAgent`）不变；
- 同一 Server **不要**与非桥接栈同时注册，避免工具名冲突。

---

## 六、验证测试与支持度

## 6.1、验证与测试

### 6.1.1、快速验证：stdio（真实公开 Server）

最省事的自检——用官方 filesystem Server 起本地子进程，跑通「发现 → 调用 → 回灌」：

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

> 该用例已落为可运行测试：`agentforge-agent-core/src/test/java/cloud/changlu/agentforge/agent/tool/mcp/real/McpRealStudioTest.java`（真实 MCP Server + `ScriptedChatModel` 驱动完整 ReAct 循环，默认运行，可用 `-Dagentforge.mcp.live=false` 关闭）。

### 6.1.2、快速验证：HTTP（真实公开 Server）

换成 Streamable HTTP 传输，其余代码完全一致（唯一差别是 `transport`）：

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
            assertFalse(tools.isEmpty()); // deepwiki 实测暴露 3 个工具
        } finally {
            client.close();
        }
    }
}
```

> **一句话**：stdio 与 HTTP **只差一个 `transport`**，`McpToolFactory` / `ToolService` / `ReActAgent` 用法完全相同。

> 该用例已落为可运行测试：`agentforge-agent-core/src/test/java/cloud/changlu/agentforge/agent/tool/mcp/real/McpRealHttpTest.java`（真实 DeepWiki 公开服务，发现 + 真实调用 `read_wiki_structure`）。

<br/>

### 6.1.3、公开 MCP 服务实测清单

实测时间：2026-10-05，均为免鉴权公开服务：

| 形式 | 服务 | 启动 / 地址 | 实测工具数 | 结果 |
|---|---|---|---|---|
| stdio | filesystem | `npx -y @modelcontextprotocol/server-filesystem <dir>` | 14 | ✅ |
| stdio | memory | `npx -y @modelcontextprotocol/server-memory` | 9 | ✅ |
| stdio | sequential-thinking | `npx -y @modelcontextprotocol/server-sequential-thinking` | 1 | ✅ |
| stdio | everything | `npx -y @modelcontextprotocol/server-everything` | 13 | ✅ |
| HTTP | deepwiki | `https://mcp.deepwiki.com/mcp` | 3 | ✅ |
| HTTP | context7 | `https://mcp.context7.com/mcp` | 2 | ✅ |
| HTTP | gitmcp | `https://gitmcp.io/{owner}/{repo}` | — | ⚠️ 需 `Mcp-Session-Id`（P1） |

- 典型工具名：filesystem → `read_file` / `write_file` / `list_directory`…；everything → `echo` / `get-sum` / `get-env`…；deepwiki → `ask_wiki_question` / `read_wiki_structure` / `read_wiki_contents`；context7 → `resolve-library-id` / `query-docs`。
- 复现命令：

```bash
# 公开服务扫描（需显式开启 live）
mvn test -Dagentforge.mcp.live=true \
  -Dtest=McpPublicServersLiveTest,McpStdioLiveTest,McpHttpLiveTest

# 文档案例的真实应用测试（默认运行，-Dagentforge.mcp.live=false 关闭）
mvn test -Dtest='cloud.changlu.agentforge.agent.tool.mcp.real.**'
```

> **注意**：`gitmcp` 在 `initialize` 后要求后续请求回传 `Mcp-Session-Id`，当前 P0 无状态实现会报 `Bad Request: Mcp-Session-Id header is required`——这正是有状态 HTTP 会话要留到 P1 的原因。

<br/>

### 6.1.4、离线单元测试（不依赖真实 Server）

Mock `McpClient`（`FakeMcpClient`）注入固定工具与结果，即可离线跑：

```java
FakeMcpClient client = new FakeMcpClient("demo",
        Collections.singletonList(new McpTool("get_weather", "查询天气", weatherSchema())));
Map<ToolSpecification, ToolExecutor> tools = McpToolFactory.buildTools(client, "demo");
assertEquals("demo__get_weather", tools.keySet().iterator().next().name());
```

已覆盖：`JsonRpcCodec` 编解码、`tools/list` 映射、名称消歧、白/黑名单过滤、`tools/call` 参数透传、`isError` 分流、协议 / 传输错误失败化、握手时序、HTTP JSON 与 SSE 两种响应形态；另含真实 `JdkHttpTransport` + 本地 HTTP Server 的端到端用例。

<br/>

### 6.1.5、风险与一致性

- **有状态 HTTP**：需回传 `Mcp-Session-Id`（gitmcp 实测即此限制），P1 补齐。
- **规范跟进**：自研需持续跟进 `2024-11-05 → 2026-07-28` 的修订与弃用；对照官方 **MCP Inspector** / **conformance** 自测消息结构。
- **双时代复杂**：`initialize` 与逐请求 `_meta` 并存，探测与缓存要做对（P1）。
- **HTTP 流式**：请求级 SSE 的解析与取消（关流即取消）需正确实现。
- **安全**：Tool Poisoning、审批（human-in-the-loop）、凭据管理不可省。

---

## 6.2、支持度分级与路线图

| 阶段 | 能力 | 传输 | 说明 |
|---|---|---|---|
| **P0** | `tools/list` + `tools/call` | stdio / Streamable HTTP | 最小可用：发现 + 执行 |
| **P0** | 名称消歧 / 工具过滤 / 元数据 | — | `serverAlias__` 前缀、白名单 |
| **P0** | 错误分流（isError / 协议错误） | — | 回模型自纠 |
| **P1** | 双时代兼容 + 超时/取消 + 关闭重启 | stdio / HTTP | 稳定性 |
| **P1** | `notifications/tools/list_changed` 热更新 | stdio / HTTP | 工具表刷新 |
| **P1** | Resources / Prompts（读） | stdio / HTTP | `resources/read`、`prompts/get` |
| **P2** | MRTR（`InputRequiredResult`） | stdio / HTTP | 交互式输入 |
| **P2** | `subscriptions/listen` | HTTP | 变更订阅 |
| **P2** | OAuth 2.1 / OIDC | HTTP | 远程鉴权 |
| **P2** | `x-mcp-header` 镜像 | HTTP | 网关路由 |
| **P3** | Server 侧（暴露 AgentForge `@Tool` 为 MCP） | stdio / HTTP | 反向赋能 |
| **P3** | 官方 SDK 桥接模块（JDK17） | 全 | 可选一致性方案 |

### 6.2.1、与 `http` / `local` 模式的一致性

| 维度 | `local` | `http` | `mcp`（本文） |
|---|---|---|---|
| 工厂 | `LocalToolFactory` | `HttpToolFactory` | `McpToolFactory` |
| 执行器 | `LocalToolExecutor` | `HttpToolExecutor` | `McpToolExecutor` |
| 发现 | 扫 `@Tool` | 读配置 | `tools/list` 动态 |
| Schema | 反射生成 | 配置生成 | Server 下发 |
| 错误 | `ToolExecutionResult` | 文本「HTTP请求失败」 | `isError` 无损映射 |
| 子包 | `support/` | `domain/enums/support/parser/` | `domain/transport/support/` |

> **重点**：MCP 模式与既有两种模式正交，可同时注册进同一个 `ToolService`，模型看到统一的工具清单。

---

## 七、总结与展望

## 7.1、总结与展望

1. **调研结论**：三方 stdio / HTTP 都支持；**LangChain4j 自研，Spring AI 与 AgentScope 基于官方 MCP Java SDK**；官方 SDK 是事实标准但要求 JDK 17+。
2. **AgentForge 选择**：**核心自研协议栈（Java 8 / 零依赖，已落地）+ 官方 SDK 可选桥接（P3 规划）**。
3. **接入落点**：`McpToolExecutor implements ToolExecutor` + `McpToolFactory`，无缝并入 `ToolService` 与 ReAct 循环。
4. **支持节奏**：P0 打通 stdio / Streamable HTTP 的 `tools/list` + `tools/call`；P1 补热更新与资源提示词；P2 补 MRTR / 订阅 / OAuth；P3 反向 Server 与官方桥接。
5. **展望**：随规范演进补齐 MRTR、`subscriptions/listen`、OAuth 加固，并探索把 AgentForge 的 `@Tool` 反向暴露为 MCP Server。

---

## 参考资料

[1]. [MCP 官方规范（2026-07-28）](https://modelcontextprotocol.io/specification/2026-07-28)

[2]. [MCP 版本与兼容性](https://modelcontextprotocol.io/specification/2026-07-28/basic/lifecycle)

[3]. [MCP Streamable HTTP 传输](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)

[4]. [MCP stdio 传输](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio)

[5]. [MCP Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)

[6]. [官方 MCP Java SDK 文档](https://java.sdk.modelcontextprotocol.io/latest/)

[7]. [MCP Java SDK（GitHub）](https://github.com/modelcontextprotocol/java-sdk)

[8]. [LangChain4j MCP 文档](https://docs.langchain4j.dev/tutorials/mcp/)

[9]. [langchain4j-mcp 模块（GitHub）](https://github.com/langchain4j/langchain4j/tree/main/langchain4j-mcp)

[10]. [Spring AI MCP 总览](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)

[11]. [Spring AI MCP Client Boot Starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)

[12]. [Spring AI MCP Server Boot Starters](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

[13]. [AgentScope Java MCP 文档](https://java.agentscope.io/v1/en/docs/task/mcp)

[14]. [AgentScope Java（GitHub）](https://github.com/agentscope-ai/agentscope-java)

[15]. [MCP 一致性测试 conformance](https://github.com/modelcontextprotocol/conformance)

[16]. [MCP Inspector](https://github.com/modelcontextprotocol/inspector)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
