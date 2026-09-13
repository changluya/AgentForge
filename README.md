# AgentForge

> **Forge Intelligence into Action.**  
> **将智能锻造成行动。**

AgentForge 是一个面向 Java 开发者、从 **LLM 最底层能力开始构建** 的开源 Agent Framework。

它不会从一个已经高度封装的 Agent API 起步，而是先建立稳定、统一、可扩展的模型抽象，再逐层向上构造 Context、Memory、Tool、Skill、MCP、Reasoning、Agent Runtime 与 Multi-Agent 等能力。

**AgentForge 的目标不是提供一个固定形态的 Agent，而是提供一套可以持续“锻造”不同 Agent 的底层能力。**

---

## 1. 名称与立意

**AgentForge = Agent + Forge**。

`Agent` 代表能够理解目标、进行推理、调用工具并完成任务的智能体；`Forge` 原意是“锻造、熔炉、工坊”，强调把原始材料经过持续加工、塑形和强化，最终打造为真正可用的产品。

AgentForge 想表达的是：

> **大模型提供原始智能，AgentForge 将这些智能能力逐层工程化，最终锻造成能够真正执行任务的 Agent。**

从模型到智能体，中间并不是简单增加一个循环，而是一整套工程体系：

```text
LLM
 ↓
Message / Request / Response
 ↓
Context / Memory
 ↓
Tool / Skill / MCP
 ↓
Reasoning / Planning
 ↓
Agent Runtime
 ↓
Multi-Agent / Sandbox / Observability
 ↓
Real Action
```

因此 AgentForge 的核心 Slogan 是：

> **Forge Intelligence into Action.**  
> **将智能锻造成行动。**

### 为什么从 LLM 层开始？

Agent 的上层能力最终都会落到模型调用上。如果最底层模型抽象不稳定，上层 Agent、Tool Calling、Memory、Context 乃至 Multi-Agent 都会被具体厂商协议绑住。

所以 AgentForge 选择 **Bottom-up** 的构建方式：

1. 先定义稳定、厂商无关的 `ChatModel` 核心接口；
2. 再实现 OpenAI、Anthropic 等 Provider Adapter；
3. 上层框架只依赖 AgentForge 自己的抽象，不直接依赖任何厂商 SDK；
4. 最终逐层构造完整 Agent Runtime。

这一设计思路参考了 LangChain4j 的“核心抽象 + Provider Integration”模块化方式，但 AgentForge 会从自己的 Agent Runtime 目标出发逐步演进 API。

---

## 2. 当前项目结构

当前第一阶段只实现 `agentforge-llm`，`agentforge-framework` 先创建模块骨架，为下一阶段保留边界。

```text
AgentForge
├── agentforge-llm
│   ├── agentforge-llm-core
│   ├── agentforge-llm-openai
│   └── agentforge-llm-anthropic
│
├── agentforge-framework
│   ├── agentforge-ai-core
│   └── agentforge-ai-agent
│
├── pom.xml
└── README.md
```

### `agentforge-llm-core`

第一阶段最重要的底层模块，不依赖 OpenAI / Anthropic SDK，也不依赖第三方 JSON/HTTP 库。

当前提供：

- `ChatModel`：统一同步模型调用入口；
- `StreamingChatModel`：统一流式模型调用入口；
- `StreamingChatResponseHandler`：统一流式增量 / 完成 / 异常回调；
- `ChatRequest`：统一请求对象；
- `ChatRequestParameters`：统一模型参数抽象；
- `ChatMessage`：System / User / AI / ToolExecutionResult / Custom Message；
- `ChatResponse`：统一响应；
- `TokenUsage` / `FinishReason`：统一结果元信息；
- `HttpTransport`：可替换 HTTP Transport SPI，同时支持同步与流式扩展；
- `JdkHttpTransport`：基于 JDK `HttpURLConnection` 的零依赖默认实现，流式请求通过后台守护线程持续消费响应。

核心 API：

```java
public interface ChatModel {

    ChatResponse chat(ChatRequest chatRequest);

    default String chat(String userMessage) {
        // convenience API
    }
}
```

上层 Agent Framework 未来只面向 `ChatModel`，而不关心底层实际使用 OpenAI、Anthropic 或其它模型服务。

### `agentforge-llm-openai`

实现 OpenAI Chat Completions 协议，同时提供同步与流式模型：

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Hello AgentForge");
```

流式调用：

```java
StreamingChatModel model = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

model.chat("Hello AgentForge", new StreamingChatResponseHandler() {
    @Override
    public void onPartialResponse(String partialResponse) {
        System.out.print(partialResponse);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        System.out.println("\nfinishReason=" + completeResponse.finishReason());
    }

    @Override
    public void onError(Throwable error) {
        error.printStackTrace();
    }
});
```

`OpenAiStreamingChatModel` 基于 OpenAI SSE Chat Completions 流式协议实现：请求固定开启 `stream=true`，同时请求 `stream_options.include_usage=true`，逐个转发 `delta.content`，并在流结束后聚合出统一 `ChatResponse`。这一接口设计参考 LangChain4j 的 `StreamingChatModel + StreamingChatResponseHandler` 分层思路，但保持 AgentForge 自己的 JDK 8 兼容 API 与 HTTP Transport 抽象。

`baseUrl` 可配置，因此也可以作为 OpenAI-compatible Provider 的基础适配器：

```java
ChatModel model = OpenAiChatModel.builder()
        .baseUrl("https://your-openai-compatible-endpoint/v1")
        .apiKey(System.getenv("MODEL_API_KEY"))
        .modelName("your-model")
        .build();
```

### `agentforge-llm-anthropic`

实现 Anthropic Messages API：

```java
ChatModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("your-claude-model")
        .maxTokens(1024)
        .build();

String answer = model.chat("Hello AgentForge");
```

---

## 3. ChatModel 设计

AgentForge 第一版不会急着实现完整 Agent，而是先把模型调用边界稳定下来。

### 统一请求

```java
ChatRequest request = ChatRequest.builder()
        .message(SystemMessage.from("You are a helpful assistant."))
        .message(UserMessage.from("What is AgentForge?"))
        .parameters(DefaultChatRequestParameters.builder()
                .temperature(0.2)
                .maxTokens(1024)
                .build())
        .build();

ChatResponse response = model.chat(request);
```

### Provider 无关

业务与上层 Agent 只依赖：

```java
ChatModel
```

Provider 负责实现：

```text
ChatModel
   ├── OpenAiChatModel
   └── AnthropicChatModel
```

未来可以继续扩展：

```text
ChatModel
   ├── OpenAiChatModel
   ├── AnthropicChatModel
   ├── DashScopeChatModel
   ├── OllamaChatModel
   ├── XinferenceChatModel
   └── ...
```

### 请求级参数覆盖模型级默认参数

模型可以配置默认参数：

```java
OpenAiChatModel.builder()
        .modelName("gpt-4o-mini")
        .temperature(0.7)
        .build();
```

单次请求也可以覆盖：

```java
ChatRequest request = ChatRequest.builder()
        .message(UserMessage.from("Explain ReAct."))
        .parameters(DefaultChatRequestParameters.builder()
                .temperature(0.1)
                .build())
        .build();
```

这样可以保持核心接口稳定，同时给不同调用场景保留足够灵活性。

---

## 4. Java 版本策略

AgentForge 的版本策略是：

> **推荐 JDK 17，兼容 JDK 8。**

具体策略：

- 日常开发、CI 和新用户默认推荐 **JDK 17**；
- 第一阶段公共模块编译目标为 **Java 8 bytecode**；
- JDK 8 用户可以直接依赖和运行；
- JDK 17 用户无需额外配置，可以直接使用；
- Maven 编译级别固定为 `source/target 8`，并通过 JDK 8 / JDK 17 双版本 CI 持续验证兼容性；
- 核心 LLM 层当前不依赖 Spring，也不依赖高版本 JDK HTTP Client。

这意味着：

```text
JDK 8   ✅ Compatible
JDK 17  ✅ Recommended
```

构建：

```bash
mvn clean test
```

### 包名与 Maven 坐标约定

AgentForge 统一使用以下根包名：

```text
com.changlu.agentforge.xxx
```

例如：

```text
com.changlu.agentforge.llm.chat
com.changlu.agentforge.llm.openai
com.changlu.agentforge.llm.anthropic
com.changlu.agentforge.ai.core
com.changlu.agentforge.ai.agent
```

Maven `groupId` 同样统一为：

```text
com.changlu.agentforge
```

### 单元测试

当前 LLM 第一阶段三个实现模块均已补充单元测试：

```text
agentforge-llm-core       -> Core API / Request / Parameters / JSON / HTTP
agentforge-llm-openai     -> 请求映射 / 响应归一化 / 异常 / OpenAI-compatible
agentforge-llm-anthropic  -> System Message / Messages API / 响应归一化 / 异常
```

单测默认不访问真实模型服务，而是通过可替换的 `HttpTransport` 使用 Fake/Capturing Transport 验证请求与响应，因此 CI 中无需配置 OpenAI 或 Anthropic API Key。

当前共包含 **39 个单元测试用例**，并持续通过 JDK 8 / JDK 17 CI 执行：

```bash
mvn clean test
```

---

## 5. Roadmap

AgentForge 将按照“从底层模型能力逐层锻造 Agent”的顺序演进。

### Phase 1 — LLM Foundation（当前）

```text
agentforge-llm-core
agentforge-llm-openai
agentforge-llm-anthropic
```

目标：稳定 `ChatModel`、`StreamingChatModel`、Message、Request、Response、Provider Adapter 等最底层模型抽象。当前消息层已补齐 `ToolExecutionResultMessage` 与 `CustomMessage`。

当前 OpenAI Provider 已同时具备 `OpenAiChatModel` 与 `OpenAiStreamingChatModel`。

### Phase 2 — LLM Capability

计划逐步增加：

```text
Anthropic StreamingChatModel
Tool Calling
Structured Output
Multimodal Message
Embedding Model
Image Model
Retry / Listener / Observability
More Providers
```

### Phase 3 — Agent Foundation

开始实现：

```text
agentforge-ai-core
agentforge-ai-agent
```

逐步加入：

```text
Context
Memory
Tool
Skill
MCP
Prompt
Reasoning
Planning
ReAct
Agent Runtime
```

### Phase 4 — Production Agent Runtime

最终目标：

```text
SubAgent
Multi-Agent
Sandbox
State / Snapshot
Human-in-the-loop
Tracing
Observability
Persistence
Production Runtime
```

---

## Design Principles

AgentForge 会长期坚持几个原则：

**1. Bottom-up**  
先把 LLM、Message、Request、Response 等基础抽象做稳定，再构建 Agent。

**2. Provider-neutral**  
上层框架不应该被某一家模型厂商协议绑定。

**3. Modular**  
核心抽象与 Provider、Framework、Agent Runtime 分模块演进。

**4. Lightweight**  
底层尽量减少不必要依赖，让 AgentForge 可以被 Spring Boot、普通 Java、桌面端甚至嵌入式 Java 工程复用。

**5. Production-oriented**  
最终目标不是 Demo Agent，而是可以真正进入生产环境的 Agent Runtime。

---

## License

AgentForge is released under the [MIT License](LICENSE).

---

## AgentForge

> **Models provide intelligence. AgentForge turns intelligence into action.**

模型提供智能，AgentForge 负责将它一步步锻造成真正能够行动的 Agent。
