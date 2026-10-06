# AgentForge 博客专栏规划

## 一、愿景

> 极简核心实现 ReAct 核心 loop 框架 & Harness Agent 设计实现。

**Forge Intelligence into Action. 将智能锻造成行动。**

专栏主线采用 **Bottom-up（自底向上）** 的叙事方式，与 AgentForge 的构建顺序一致：

```text
模型协议层 (agentforge-model)
        ↓
通用 ReAct Agent 层 (agentforge-agent-core)
        ↓
Harness Agent 层 (运行环境 / 权限 / 审批)
        ↓
应用平台层 (AgentForge-Platform)
```

每一层拆成两类内容：

- **原理课**：讲设计动机、协议原理、源码级实现，适合“想搞懂 Agent 框架怎么造”的读者；
- **实战课**：快速上手的 Demo / 案例，适合“想马上用起来”的读者。

命名统一格式（文章名称需带上**模型类别**）：

```text
AgentForge <层> <模型类别> <类型> <序号>、<标题>

示例：
AgentForge模型协议层 ChatModel 原理01、从零手撸 ChatModel 协议层
```

> 说明：文章名称需带上**模型类别**；模型协议层初步为 **ChatModel**，
> 后续新增 Embedding / Image / Audio 等类别时按类别分别成篇。

状态标记：`[已完成]` 表示当前代码已支撑，`[规划中]` 表示随框架演进补齐。

---

## 二、第一季：模型协议层（agentforge-model）

> 所属模块：`agentforge-model-api` / `agentforge-model-core` /
> `agentforge-model-openai` / `agentforge-model-anthropic` / `agentforge-model-registry`
>
> 设计目标：稳定、厂商无关、Java 8 兼容的最底层模型抽象。

### 模型类别划分

模型协议层按**模型类别**组织，不同类别有各自独立的协议与抽象，互不耦合：

| 模型类别 | 职责 | 当前状态 |
|---|---|---|
| **ChatModel** | LLM 对话（同步 / 流式 / 工具调用） | ✅ 初步聚焦 |
| EmbeddingModel | 文本向量化 | ⏳ 规划中 |
| ImageModel | 图像生成 / 编辑 | ⏳ 规划中 |
| AudioModel | 语音合成 / 识别 | ⏳ 规划中 |

> 初步专栏只覆盖 **ChatModel**，其余类别后续随框架演进单独开季。

### 一、ChatModel（LLM 对话协议）

#### 原理课

```shell
AgentForge模型协议层 ChatModel 原理01、[已完成] ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现
AgentForge模型协议层 ChatModel 原理02、[已完成] Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环
AgentForge模型协议层 ChatModel 原理03、[已完成] 同步与流式：StreamingChatModel + 零依赖 HTTP Transport / SSE
AgentForge模型协议层 ChatModel 原理04、[已完成] Provider 适配与模型注册：OpenAI / Anthropic + LlmFactory
```

#### 实战课

```shell
AgentForge模型协议层 ChatModel 实战01、[已完成] 快速接入任意大模型：同步 / 流式 / 多轮工具调用
AgentForge模型协议层 ChatModel 实战02、[已完成] 从零实现自己的 Provider 适配器
```

---

## 三、第二季：通用 ReAct Agent 层（agentforge-agent-core）

> 所属模块：`agentforge-agent-core`
>
> 设计目标：包含最底层原生 ReAct Agent 的非流式 & 流式核心实现规范。

### 原理课

```shell
AgentForge通用ReActAgent原理01、[已完成] 全局理解核心 ReAct 设计思想 & Harness 设计蓝图
AgentForge通用ReActAgent原理02、[已完成] 从 IAgent 到 ReActAgent：分层主循环设计
AgentForge通用ReActAgent原理03、[已完成] Think-Act 核心循环：finishReason 驱动的收敛机制
AgentForge通用ReActAgent原理04、[已完成] AgentChatContext：单次运行上下文与 extensions 扩展
AgentForge通用ReActAgent原理05、[已完成] Memory 记忆体系：ChatMemory / WindowChatMemory / Provider
AgentForge通用ReActAgent原理06、[已完成] TokenStream：流式文本 / 思考 / 工具事件回调设计
AgentForge通用ReActAgent原理07、[已完成] AgentMiddleware：像 AOP 一样横切 Agent 主循环
AgentForge通用ReActAgent原理08、[已完成] 运行态治理：AgentRunState / 异常收敛 / 重试
AgentForge通用ReActAgent原理09、[规划中] Step 编排与 Planning / Reasoning 抽象
```

### 实战课

```shell
AgentForge通用ReActAgent实战01、[已完成] 30 行代码构建你的第一个 ReAct Agent
AgentForge通用ReActAgent实战02、[已完成] 给 Agent 接入自定义工具
AgentForge通用ReActAgent实战03、[已完成] 实现流式对话 + 思考过程展示
AgentForge通用ReActAgent实战04、[已完成] 编写自定义中间件（日志 / 埋点 / 鉴权）
AgentForge通用ReActAgent实战05、[已完成] 为 Agent 增加多轮会话记忆
AgentForge通用ReActAgent实战06、[已完成] Studio 实战：Spring Boot + Vue 打造 Agent 调试台
```

---

## 四、第三季：Harness Agent 层

> 所属模块：`agentforge-agent-core`（Harness 组件）
>
> 设计目标：补齐 Agent 运行环境 & 权限工具审核策略机制 & 相关 Harness 基础组件。

### 原理课

```shell
AgentForgeHarnessAgent原理01、[规划中] Harness 是什么：Agent 从 Demo 走向生产的关键一环
AgentForgeHarnessAgent原理02、[规划中] 工具权限与审核策略机制设计
AgentForgeHarnessAgent原理03、[规划中] Human-in-the-loop 审批与 Resume 机制
AgentForgeHarnessAgent原理04、[规划中] External Tool / Stop-Tool 模式
AgentForgeHarnessAgent原理05、[规划中] 子 Agent（SubAgent）与任务委派
AgentForgeHarnessAgent原理06、[规划中] Trace 与运行可观测性
AgentForgeHarnessAgent原理07、[规划中] Sandbox 运行环境与状态快照（State / Snapshot）
```

### 实战课

```shell
AgentForgeHarnessAgent实战01、[规划中] 给 Agent 加上工具调用审批
AgentForgeHarnessAgent实战02、[规划中] 实现可中断、可恢复的 Agent 工作流
AgentForgeHarnessAgent实战03、[规划中] 用子 Agent 完成复杂任务拆解
AgentForgeHarnessAgent实战04、[规划中] 接入 Trace 做 Agent 运行诊断
```

---

## 五、第四季：应用平台层（AgentForge-Platform）

> 竞品参考：Agent-core、Coze 等
>
> 后续会独立一个工程立项。

```shell
AgentForge平台层规划01、[规划中] Agent 可视化编排设计
AgentForge平台层规划02、[规划中] 工作流 / 多 Agent 协同
AgentForge平台层规划03、[规划中] 插件市场与能力开放
AgentForge平台层规划04、[规划中] 平台化部署与多租户
AgentForge平台层规划05、[规划中] 从 Studio 到 Platform：产品化演进
```

> TODO：待独立立项后细化专栏目录。

---

## 六、番外篇：工程化 & 生态

> 围绕框架本身的演进经验、规范与生态扩展。

```shell
AgentForge番外01、[已完成] 模块划分与依赖规范：api / core / provider 分层
AgentForge番外02、[已完成] BOM 与 Parent 维护规范：如何统一管理版本
AgentForge番外03、[已完成] JDK 8 / JDK 17 双版本兼容与 CI 实践
AgentForge番外04、[已完成] 140+ 单测如何做到不依赖真实 API Key
AgentForge番外05、[已完成] Spotless 代码格式化与提交规范
AgentForge番外06、[规划中] 为什么从 LLM 最底层开始：AgentForge 设计哲学
AgentForge番外07、[规划中] 对标 LangChain4j：AgentForge 的取舍与差异
AgentForge番外08、[规划中] MCP / Skill 生态接入实践
```

---

## 七、发布节奏与优先级建议

| 阶段 | 优先专栏 | 说明 |
|------|----------|------|
| P0 | 模型协议层原理 + 实战 | 代码已完备，可直接沉淀为教程 |
| P0 | 通用 ReAct Agent 原理 + 实战 | 当前核心能力，重点打造 |
| P1 | 番外篇工程化 | 建立框架技术公信力 |
| P2 | Harness Agent 层 | 随代码补齐同步输出 |
| P3 | 应用平台层 | 独立立项后再启动 |

> 选题原则：**代码先落地，文章再沉淀**；每篇文章尽量对应一个可运行 Demo，保证读者能跑通。
