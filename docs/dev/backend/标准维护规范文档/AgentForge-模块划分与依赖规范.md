# AgentForge 模块划分与依赖规范

## 1. 目标

本文档只约束两件事：**模块怎么分**、**模块之间依赖什么方向**。版本、Parent、BOM 的维护方式见
《AgentForge BOM 与 Parent 维护规范》，本文档不重复。

核心原则：

1. 用两个维度组织模块：横向是**模型能力**，纵向是**厂商实现**。
2. `agentforge-model-api` 只放能力契约，不放任何厂商实现和通用工具。
3. 每个厂商一个实现模块，实现模块只依赖 `agentforge-model-api`。
4. `agentforge-model-registry` 是开箱即用的统一创建入口，负责把 Provider 实现装配到契约上。
5. 上层 `agentforge-agent-core` 只依赖 `agentforge-model-api`，面向抽象而非具体 Provider。
6. 初期不按能力拆 Maven 模块。Chat / Embedding / Image 等能力先在 `model-api` 内按包组织，只有某个能力域
   体量或依赖显著变大时，才考虑独立成模块。

## 2. 模块总览

```text
AgentForge
├── agentforge-ai-parent       第三方依赖与构建配置（构建基线）
├── agentforge-ai-bom          对外可发布模块的版本清单
│
├── agentforge-model
│   ├── agentforge-model-api          模型能力契约层（Provider 无关）
│   ├── agentforge-model-openai       OpenAI 协议实现
│   ├── agentforge-model-anthropic    Anthropic Messages 协议实现
│   └── agentforge-model-registry     开箱即用的模型创建入口
│
├── agentforge-framework
│   └── agentforge-agent-core         Agent 运行时（ReAct / Memory / Tool / Stream）
│
└── agentforge-examples
    └── agentforge-studio
        ├── agentforge-studio-ui
        └── agentforge-studio-web
```

`agentforge-model` 与 `agentforge-framework` 都是聚合 POM，本身不产出 JAR。

## 3. 各模块职责

### 3.1 agentforge-model-api

Provider 无关的模型能力契约层，是整个模型的**公开边界**。它不依赖 OpenAI / Anthropic SDK，也不依赖
第三方 JSON / HTTP 库。

当前包含：

```text
com.changlu.agentforge.model.chat             ChatModel / StreamingChatModel / Request / Response
com.changlu.agentforge.model.chat.message     消息体系
com.changlu.agentforge.model.tool             工具契约与执行
com.changlu.agentforge.model.exception        统一异常
com.changlu.agentforge.model.http             可替换 HTTP Transport
com.changlu.agentforge.model.internal.json    内置 JSON 实现
```

约束：

- 只定义契约、请求 / 响应对象、消息对象、枚举与异常，不包含任何 Provider 实现；
- 新能力（Embedding、Image 等）优先在此模块内**加包**，而不是新建 Maven 模块；
- 不放与模型能力无关的通用工具类，避免退化成杂物模块。

### 3.2 agentforge-model-openai / agentforge-model-anthropic

厂商实现模块，只有两个职责：把 AgentForge 契约翻译成厂商 Wire Protocol，把厂商响应归一化回契约。
每个实现模块只依赖 `agentforge-model-api`。

```text
com.changlu.agentforge.model.openai       OpenAiChatModel / OpenAiStreamingChatModel
com.changlu.agentforge.model.anthropic    AnthropicChatModel / AnthropicStreamingChatModel
```

### 3.3 agentforge-model-registry

开箱即用的统一创建入口，依赖全部 Provider 实现，负责按 provider 选择并装配对应的实现。业务侧只需要一个
配置对象即可拿到 `ChatModel`：

```java
ChatModel chatModel = LlmFactory.buildChatModel(config);
StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(config);
```

当前包结构：

```text
com.changlu.agentforge.model.registry              LlmFactory
com.changlu.agentforge.model.registry.config       配置对象
com.changlu.agentforge.model.registry.constant     公共常量
com.changlu.agentforge.model.registry.enums        Provider 枚举
com.changlu.agentforge.model.registry.models       各 Provider 的装配实现
```

### 3.4 agentforge-agent-core

Agent 运行时，提供 ReAct 主循环、流式、Memory、Tool 回合、Middleware 与重试。它面向 `model-api`
编程，可以接收调用方手工传入的任意 `ChatModel` / `StreamingChatModel` 实现。

```text
com.changlu.agentforge.agent     Agent / ReAct / Memory / Tool / Stream / Middleware
```

## 4. 依赖关系

```text
                         agentforge-model-api
                          ↑        ↑        ↑
                          │        │        │
              agentforge-model-openai   agentforge-model-anthropic
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
                         agentforge-model-api
```

依赖方向规则：

1. `model-openai` / `model-anthropic` → `model-api`。
2. `model-registry` → `model-api` + 全部 Provider 实现。
3. `agent-core` → `model-api`；**不得**在编译期依赖 `model-registry`。
4. 任何模块都不允许反向依赖 `agent-core`。
5. Provider 实现之间不互相依赖。

`agent-core` 的 `ReActAgentDiyLiveTest` 通过 `LlmFactory` 做真实 Endpoint 联通验证，因此
`model-registry` 以 `test` scope 引入 agent-core。这是测试便利，不是生产依赖：主代码只面向
`model-api`，用户也可以手工构造 `ChatModel` 传入 Agent。

## 5. 包名约定

AgentForge 统一根包：

```text
com.changlu.agentforge
```

| 模块 | 包前缀 |
| --- | --- |
| `agentforge-model-api` | `com.changlu.agentforge.model` |
| `agentforge-model-openai` | `com.changlu.agentforge.model.openai` |
| `agentforge-model-anthropic` | `com.changlu.agentforge.model.anthropic` |
| `agentforge-model-registry` | `com.changlu.agentforge.model.registry` |
| `agentforge-agent-core` | `com.changlu.agentforge.agent` |

包名必须与模块职责一致：包名发生语义变化，说明模块边界可能需要调整。

## 6. 扩展指引

### 6.1 新增 Provider

1. 新建 `agentforge-model-<provider>`，依赖 `agentforge-model-api`；
2. 在实现模块内提供 `ChatModel` / `StreamingChatModel`（以及后续能力）实现；
3. 在 `agentforge-model-registry` 中注册该 Provider 的装配实现；
4. 将新模块登记进 `agentforge-model` 聚合 POM 与 `agentforge-ai-bom`。

### 6.2 新增模型能力（Embedding / Image 等）

1. 优先在 `agentforge-model-api` 内新增领域包，例如 `model.embedding`；
2. 在已有 Provider 模块内补充对应实现；
3. 只有当该能力域形成独立依赖、体量或发布节奏时，才拆成独立 Maven 模块。

### 6.3 禁止事项

- 不按 `model-message`、`model-tool`、`model-streaming` 这种粒度继续拆模块；
- 不在 `model-api` 中引入任何厂商 SDK 或与本层无关的工具类；
- 不让 `agent-core` 在编译期依赖 `model-registry` 或具体 Provider 模块。

## 7. 变更验证清单

调整模块结构、依赖或包名后，至少执行：

```bash
# 全量编译与测试
mvn clean verify

# 检查关键模块的依赖收敛
mvn -pl agentforge-framework/agentforge-agent-core dependency:tree
mvn -pl agentforge-model/agentforge-model-registry dependency:tree
```

验收标准：

- Reactor 中所有模块构建成功；
- `agent-core` 的编译期依赖仅包含 `agentforge-model-api`；
- `model-registry` 编译期依赖包含全部 Provider 实现；
- Provider 模块仅依赖 `agentforge-model-api`；
- 全量单测通过。
