<p align="center">
  <img src="assets/agentforge-banner.png" alt="AgentForge — Open Source AI Agent Framework" width="460">
</p>

# AgentForge

> **Forge Intelligence into Action.**

**English** | [简体中文](README.zh-CN.md)

AgentForge is an open-source Agent framework for Java developers, built **from the lowest-level LLM capabilities upward**.

Rather than starting from a highly abstracted Agent API, it first establishes a stable, unified, and extensible model abstraction, then builds Tool, Memory, Middleware, Reasoning, and Agent Runtime capabilities layer by layer.

> **LLMs provide raw intelligence. AgentForge engineers that intelligence layer by layer and forges it into Agents that can actually get things done.**

`AgentForge = Agent + Forge`: `Agent` stands for an entity that understands goals, reasons, calls tools, and completes tasks; `Forge` emphasizes shaping raw material through continuous processing, forming, and strengthening until it becomes a truly usable product.

- Documentation: <https://changluya.github.io/AgentForge/>
- GitHub: <https://github.com/changluya/AgentForge>
- Gitee: <https://gitee.com/changluJava/agent-forge>

---

## Architecture

AgentForge is built **bottom-up**: stabilize the lowest-level model abstraction first, then forge Agent capabilities layer by layer.

```text
LLM
 ↓  Message / Request / Response
 ↓  Context / Memory
 ↓  Tool / Skill / MCP
 ↓  Reasoning / Planning
 ↓  Agent Runtime
 ↓  Multi-Agent / Sandbox / Observability
Real Action
```

### Project Structure

```text
AgentForge
├── agentforge-ai-parent          # Centralized dependency & plugin version management
├── agentforge-ai-bom             # Unified BOM coordinates
├── agentforge-model              # Model layer
│   ├── agentforge-model-api      # Provider-neutral model contracts & unified req/vo
│   ├── agentforge-model-core     # Vendor-free default implementations & execution engine
│   ├── agentforge-model-openai   # OpenAI / OpenAI-compatible provider
│   ├── agentforge-model-anthropic# Anthropic provider
│   └── agentforge-model-registry # Out-of-the-box ChatModel factory
├── agentforge-framework
│   ├── agentforge-agent-core     # ReAct Agent runtime
│   └── agentforge-harness-agent  # Harness runtime (permission / approval / sandbox / resumable execution)
├── agentforge-service            # Out-of-the-box service (Vue frontend + Spring Boot backend)
│   ├── agentforge-service-ui     # Vue 2 + Vite frontend
│   └── agentforge-service-web    # Spring Boot backend
├── agentforge-examples
├── pom.xml
└── README.md
```

### Module Capabilities

| Module | Capabilities |
| --- | --- |
| `agentforge-model-api` | Unified `ChatModel` / `StreamingChatModel` contracts, Message / Request / Response, tool contracts, and HTTP Transport SPI |
| `agentforge-model-core` | Zero-dependency HTTP Transport, built-in JSON, `@Tool` reflective execution, and the inference-tool loop engine |
| `agentforge-model-openai` | OpenAI Chat Completions sync / streaming, Function Calling, OpenAI-compatible endpoints |
| `agentforge-model-anthropic` | Anthropic Messages API sync / streaming, Tool Use |
| `agentforge-model-registry` | `LlmFactory` builds ChatModel / StreamingChatModel per provider |
| `agentforge-agent-core` | ReAct main loop (sync / streaming), window memory, tool-calling rounds, Middleware chain, retry & cancellation |
| `agentforge-harness-agent` | Harness runtime on top of the core Agent: permission, tool approval, human-in-the-loop, sandbox, trace, resumable execution |
| `agentforge-service` | Out-of-the-box service over the unified SSE protocol (Vue frontend + Spring Boot backend) |

### Design Principles

1. **Bottom-up** — Stabilize foundational abstractions (LLM / Message / Request / Response) before building the Agent.
2. **Provider-neutral** — The upper framework is never bound to a single model vendor's protocol.
3. **Modular** — Core abstractions evolve separately from providers, framework, and Agent runtime.
4. **Lightweight** — Minimize dependencies at the bottom so it can be reused by Spring Boot, plain Java, desktop, or even embedded projects.
5. **Production-oriented** — The goal is not a demo Agent, but an Agent runtime that can genuinely reach production.

### Roadmap

| Phase | Status | Content |
| --- | --- | --- |
| Phase 1 — LLM Foundation | ✅ Done | Model contracts and OpenAI / Anthropic providers; stable Message / Request / Response |
| Phase 2 — LLM Capability | 🚧 In progress | Streaming and Tool Calling shipped; Structured Output / Multimodal / Embedding / More Providers planned |
| Phase 3 — Agent Foundation | 🚧 Current | `model-registry`, `agent-core`; ReAct loop, window memory, tool-calling rounds, Middleware |
| Phase 4 — Production Agent Runtime | ⏳ Planned | SubAgent / Multi-Agent / Sandbox / Human-in-the-loop / Tracing / Persistence |

---

## Quick Start

### Requirements

> **JDK 17 recommended, JDK 8 compatible.**

- Day-to-day development and CI default to **JDK 17**; public modules compile to **Java 8 bytecode** and can be depended on and run directly under JDK 8.
- The root package name and `groupId` are both `cloud.changlu.agentforge`.

### Build & Dependency

```bash
git clone https://github.com/changluya/AgentForge.git
cd AgentForge
mvn clean install -DskipTests
```

```xml
<dependency>
    <groupId>cloud.changlu.agentforge</groupId>
    <artifactId>agentforge-agent-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Call a Model

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Hello AgentForge");
```

`baseUrl` is configurable, so it also works as an OpenAI-compatible provider (DashScope / Ollama / Xinference, etc.). For streaming output, use `StreamingChatModel` with `StreamingChatResponseHandler`.

### Build an Agent

```java
ToolService toolService = new ToolService();
toolService.tools(new WeatherTools());   // scan all @Tool methods on the object

ReActAgent agent = ReActAgent.builder()
        .agentName("weather-react-agent")
        .systemPrompt("You are a weather assistant.")
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemoryProvider(ChatMemoryProvider.windowChatMemoryProvider(50))
        .toolService(toolService)
        .build();

ChatResult result = agent.run(AgentRequest.builder()
        .memoryId("demo")
        .question("What is the weather in Beijing today?")
        .build());
```

For streaming, `agent.runStream(request)` returns a `TokenStream` that lets you subscribe to text / thinking deltas, tool executions, and completion events; `agent.cancel(memoryId)` cancels a task. Models, memory, tools, and middleware are all injected through the builder — see the documentation site for details.

### Run AgentForge Service

```bash
# Backend (Spring Boot, default 8080)
export AGENTFORGE_MODEL_API_KEY=your-api-key
mvn package -pl agentforge-service/agentforge-service-web -am -DskipTests
java -jar agentforge-service/agentforge-service-web/target/agentforge-service-web-1.0.0-SNAPSHOT.jar

# Frontend (Vue 2 + Vite, default 5173, proxies /api to 8080)
cd agentforge-service/agentforge-service-ui
npm install && npm run dev
```

See [agentforge-service/README.md](agentforge-service/README.md) for details.

### Testing

```bash
mvn clean test
```

Unit tests do not hit real model services by default; they verify requests and responses through a replaceable `HttpTransport` and scripted models, so CI needs no API key.

---

## Questions

If you run into any problem, please reach out via:

- GitHub Issues: <https://github.com/changluya/AgentForge/issues>
- Gitee Issues: <https://gitee.com/changluJava/agent-forge/issues>
- Documentation: <https://changluya.github.io/AgentForge/>

When filing an issue, please include environment info (JDK / Maven versions), reproduction steps, and the full error log to help us locate the problem quickly.

---

## Contribution

Contributions of code, documentation, and feedback are all welcome.

1. Fork this repository and branch off from the development branch;
2. Follow the project code style (Spotless formatting runs before commit);
3. Add or update unit tests when adding or changing capabilities, and make sure `mvn clean test` passes;
4. Open a Pull Request describing the background, approach, and impact.

> Never hard-code real API keys in source code or commit them to the repository.

---

## Contributor

| Contributor | Role |
| --- | --- |
| [changlu](https://github.com/changluya) | Author / Maintainer |

Your name could be here.

---

## License

AgentForge is released under the [MIT License](LICENSE).

---

> **Models provide intelligence. AgentForge turns intelligence into action.**
