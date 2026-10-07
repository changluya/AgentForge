# AgentForge 模块划分与依赖规范

## 1. 目标

本文档只约束两件事：**模块怎么分**、**模块之间依赖什么方向**。版本、Parent、BOM 的维护方式见
《AgentForge BOM 与 Parent 维护规范》，本文档不重复。

核心原则：

1. 用两个维度组织模块：横向是**模型能力**，纵向是**厂商实现**。
2. `agentforge-model-api` 只放抽象契约与统一 req/vo，不放任何具体实现。
3. `agentforge-model-core` 放无厂商依赖的默认实现与执行引擎，实现 `model-api` 的契约。
4. 每个厂商一个实现模块，实现模块依赖 `model-api` 与 `model-core`。
5. `agentforge-model-registry` 是开箱即用的统一创建入口，负责把 Provider 实现装配到契约上。
6. 上层 `agentforge-agent-core` 面向 `model-api` / `model-core` 编程，不依赖具体 Provider。
7. 初期不按能力拆 Maven 模块。Chat / Embedding / Image 等能力先在 `model-api`（契约）与
   `model-core`（实现）内按包组织，只有某个能力域体量或依赖显著变大时，才考虑独立成模块。

## 2. 模块总览

```text
AgentForge
├── agentforge-ai-parent       第三方依赖与构建配置（构建基线）
├── agentforge-ai-bom          对外可发布模块的版本清单
│
├── agentforge-model
│   ├── agentforge-model-api          模型契约层（接口 + req/vo）
│   ├── agentforge-model-core         无厂商依赖的默认实现与引擎
│   ├── agentforge-model-openai       OpenAI 协议实现
│   ├── agentforge-model-anthropic    Anthropic Messages 协议实现
│   └── agentforge-model-registry     开箱即用的模型创建入口
│
├── agentforge-framework
│   ├── agentforge-agent-core         Agent 运行时（ReAct / Memory / Tool / Stream）
│   └── agentforge-harness-agent      Harness 运行时（权限 / 审批 / 沙箱 / 可恢复执行）
│
├── agentforge-service                开箱即用服务（聚合 POM）
│   ├── agentforge-service-ui         前端（Vue 2 + Vite）
│   └── agentforge-service-web        后端（Spring Boot + SSE）
│
└── agentforge-examples
```

`agentforge-model`、`agentforge-framework` 与 `agentforge-service` 都是聚合 POM，本身不产出 JAR。

## 3. 各模块职责

### 3.1 agentforge-model-api

Provider 无关的模型**契约层**，是整个模型的公开边界。只定义接口、枚举、异常与统一 req/vo，不包含任何
具体实现，也不依赖 OpenAI / Anthropic SDK 或第三方 JSON / HTTP 库。

```text
cloud.changlu.agentforge.model.chat             ChatModel / StreamingChatModel / Request / Response
cloud.changlu.agentforge.model.chat.message     消息体系（接口与消息对象）
cloud.changlu.agentforge.model.tool             工具契约（Tool / ToolExecutor / Spec / Result）
cloud.changlu.agentforge.model.exception        ModelException
cloud.changlu.agentforge.model.http             HttpTransport SPI 与 HttpRequest / HttpResponse
```

约束：

- 只放契约与数据对象，不放默认实现、引擎或工具类；
- 新能力（Embedding、Image 等）优先在此模块内**加包**，而不是新建 Maven 模块；
- 不放与模型能力无关的通用工具类，避免退化成杂物模块。

### 3.2 agentforge-model-core

无厂商依赖的**默认实现与执行引擎**，依赖 `agentforge-model-api` 并实现其契约。

```text
cloud.changlu.agentforge.model.internal.json    Json（内置 JSON 实现）
cloud.changlu.agentforge.model.http             JdkHttpTransport（零依赖默认 Transport）
cloud.changlu.agentforge.model.chat.request     DefaultChatRequestParameters
cloud.changlu.agentforge.model.tool.execution   DefaultToolExecutor / ToolService
cloud.changlu.agentforge.model.tool.spec        ToolSpecifications
```

约束：

- 只实现 `model-api` 的契约，不引入任何厂商 SDK；
- Provider 实现模块与 Agent 运行时都可以复用这里的能力。

### 3.3 agentforge-model-openai / agentforge-model-anthropic

厂商实现模块，只有两个职责：把 AgentForge 契约翻译成厂商 Wire Protocol，把厂商响应归一化回契约。
每个实现模块依赖 `agentforge-model-api` 与 `agentforge-model-core`。

```text
cloud.changlu.agentforge.model.openai       OpenAiChatModel / OpenAiStreamingChatModel
cloud.changlu.agentforge.model.anthropic    AnthropicChatModel / AnthropicStreamingChatModel
```

### 3.4 agentforge-model-registry

开箱即用的统一创建入口，依赖 `model-api`、`model-core` 与全部 Provider 实现，负责按 provider 选择并
装配对应的实现。业务侧只需要一个配置对象即可拿到 `ChatModel`：

```java
ChatModel chatModel = LlmFactory.buildChatModel(config);
StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);
```

当前包结构：

```text
cloud.changlu.agentforge.model.registry              LlmFactory
cloud.changlu.agentforge.model.registry.config       配置对象
cloud.changlu.agentforge.model.registry.constant     公共常量
cloud.changlu.agentforge.model.registry.enums        Provider 枚举
cloud.changlu.agentforge.model.registry.models       各 Provider 的装配实现
```

### 3.5 agentforge-agent-core

Agent 运行时，提供 ReAct 主循环、流式、Memory、Tool 回合、Middleware 与重试。它面向 `model-api` /
`model-core` 编程，可以接收调用方手工传入的任意 `ChatModel` / `StreamingChatModel` 实现。

```text
cloud.changlu.agentforge.agent     Agent / ReAct / Memory / Tool / Stream / Middleware
```

### 3.6 agentforge-service

开箱即用的服务层，聚合前端与后端两个子模块，直接把 `agent-core` 与模型 Provider 组装为可运行的
Spring Boot + Vue 应用：

```text
agentforge-service
├── agentforge-service-ui   Vue 2 + Vite 前端
└── agentforge-service-web  Spring Boot 后端 + 统一 SSE 协议
```

```text
cloud.changlu.agentforge.service   Service / Stream / Protocol / Web
```

约束：

- `agentforge-service` 只是聚合 POM，不产出 JAR；`service-web` 依赖 `agent-core` 与具体 Provider；
- 前端 `service-ui` 为占位 POM，通过 `npm run dev` 独立启动，Maven 不参与其构建；
- `service-ui` / `service-web` 属于可运行产品，不发布到 Maven Central。

## 4. 依赖关系

```text
                     agentforge-model-api        （契约层）
                              ↑            ↑
                              │            │
                  agentforge-model-core      │
                        ↑        ↑           │
                        │        │           │
        agentforge-model-openai  agentforge-model-anthropic
                        ↑        ↑
                        └───┬────┘
                            │
                 agentforge-model-registry
                            │
                            │ (仅编译期不依赖，测试期使用)
                            ▼
        agentforge-framework/agentforge-agent-core
                            │
                            ▼
                    agentforge-model-api + agentforge-model-core
```

依赖方向规则：

1. `model-core` → `model-api`。
2. `model-openai` / `model-anthropic` → `model-api` + `model-core`。
3. `model-registry` → `model-api` + `model-core` + 全部 Provider 实现。
4. `agent-core` → `model-api` + `model-core`；**不得**在编译期依赖 `model-registry` 或具体 Provider。
5. 任何模块都不允许反向依赖 `agent-core`。
6. Provider 实现之间不互相依赖。

`agent-core` 的 `ReActAgentDiyLiveTest` 通过 `LlmFactory` 做真实 Endpoint 联通验证，因此
`model-registry` 以 `test` scope 引入 agent-core。这是测试便利，不是生产依赖：主代码只面向
`model-api` / `model-core`，用户也可以手工构造 `ChatModel` 传入 Agent。

## 5. 包名约定

AgentForge 统一根包：

```text
cloud.changlu.agentforge
```

| 模块 | 包前缀 |
| --- | --- |
| `agentforge-model-api` | `cloud.changlu.agentforge.model` |
| `agentforge-model-core` | `cloud.changlu.agentforge.model` |
| `agentforge-model-openai` | `cloud.changlu.agentforge.model.openai` |
| `agentforge-model-anthropic` | `cloud.changlu.agentforge.model.anthropic` |
| `agentforge-model-registry` | `cloud.changlu.agentforge.model.registry` |
| `agentforge-agent-core` | `cloud.changlu.agentforge.agent` |
| `agentforge-harness-agent` | `cloud.changlu.agentforge.harness` |
| `agentforge-service-web` | `cloud.changlu.agentforge.service` |

`model-api` 与 `model-core` 共用同一个根包：以包表达领域，以模块表达「契约 vs 实现」。二者对同一领域包
形成「接口 / req/vo 在 api，默认实现在 core」的切分。

包名必须与模块职责一致：包名发生语义变化，说明模块边界可能需要调整。

## 6. 扩展指引

### 6.1 新增 Provider

1. 新建 `agentforge-model-<provider>`，依赖 `agentforge-model-api` 与 `agentforge-model-core`；
2. 在实现模块内提供 `ChatModel` / `StreamingChatModel`（以及后续能力）实现；
3. 在 `agentforge-model-registry` 中注册该 Provider 的装配实现；
4. 将新模块登记进 `agentforge-model` 聚合 POM 与 `agentforge-ai-bom`。

### 6.2 新增模型能力（Embedding / Image 等）

1. 在 `agentforge-model-api` 内新增契约包，例如 `model.embedding`；
2. 在 `agentforge-model-core` 内补充无厂商依赖的默认实现；
3. 在已有 Provider 模块内补充对应实现；
4. 只有当该能力域形成独立依赖、体量或发布节奏时，才拆成独立 Maven 模块。

### 6.3 禁止事项

- 不按 `model-message`、`model-tool`、`model-streaming` 这种粒度继续拆模块；
- 不在 `model-api` 中放默认实现、引擎、工具类，或引入厂商 SDK；
- 不让 `agent-core` 在编译期依赖 `model-registry` 或具体 Provider 模块。

## 7. 变更验证清单

调整模块结构、依赖或包名后，至少执行：

```bash
# 全量编译与测试
mvn clean verify

# 检查关键模块的依赖收敛
mvn -pl agentforge-framework/agentforge-agent-core dependency:tree
mvn -pl agentforge-model/agentforge-model-core dependency:tree
mvn -pl agentforge-model/agentforge-model-registry dependency:tree
```

验收标准：

- Reactor 中所有模块构建成功；
- `model-api` 不依赖 `model-core` 或任何 Provider；
- `agent-core` 的编译期依赖只包含 `model-api` / `model-core`（Provider 仅 test scope）；
- `model-registry` 编译期依赖包含 `model-core` 与全部 Provider 实现；
- Provider 模块依赖 `model-api` + `model-core`；
- 全量单测通过。
