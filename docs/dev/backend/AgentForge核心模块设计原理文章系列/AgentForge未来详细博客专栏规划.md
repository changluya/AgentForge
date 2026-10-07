# AgentForge 博客专栏规划

## 一、愿景

> 极简核心实现 ReAct 核心 loop 框架 & Harness Agent 设计实现。

**Forge Intelligence into Action. 将智能锻造成行动。**

AgentForge 的博客专栏不是一套照本宣科的课程，而是一次**从零构建 Agent 框架**的完整推演记录：把工程落地中的设计取舍、协议原理与源码实现如实铺开，既回答 Agent 框架“为什么这样造”，也给出可直接复用、按需扩展的工程答案。每一篇都对应仓库里真实存在的一行行代码，读者可以边读边跑、边改边验。

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

- **原理篇**：讲设计动机、协议原理、源码级实现，适合“想搞懂 Agent 框架怎么造”的读者；
- **实战篇**：快速上手的 Demo / 案例，适合“想马上用起来”的读者。

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

## 二、第一层：模型协议层（agentforge-model）

> 所属模块：`agentforge-model-api` / `agentforge-model-core` /
> `agentforge-model-openai` / `agentforge-model-anthropic` / `agentforge-model-registry`
>
> 设计目标：稳定、厂商无关、Java 8 兼容的最底层模型抽象。

模型协议层按**模型类别**组织，不同类别有各自独立的协议与抽象，互不耦合：

| 模型类别 | 职责 | 当前状态 |
|---|---|---|
| **ChatModel** | LLM 对话（同步 / 流式 / 工具调用） | ✅ 初步聚焦 |
| EmbeddingModel | 文本向量化 | ⏳ 规划中 |
| ImageModel | 图像生成 / 编辑 | ⏳ 规划中 |
| AudioModel | 语音合成 / 识别 | ⏳ 规划中 |

> 初步专栏只覆盖 **ChatModel**，其余类别后续随框架演进单独开季。

### 2.1、ChatModel（LLM 对话协议）

#### ✅ 标准Chat Model协议扩展设计

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | 统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现 | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/统一ChatModel协议层01、ChatModel 核心协议层设计：统一 API 契约封装与多协议扩展实现.md | ✅        |

#### ✅ 标准function call工具调用协议设计

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | 统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环 | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/统一ChatModel协议层02、Function Calling 工具调用转换与执行扩展实现：Tool 契约 + ToolService 执行循环.md | ✅        |

#### 扩展主流chat模型协议实现

##### ✅ OpenAI协议

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | OpenAI协议01、OpenAI底层协议快速理解                         | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/openai/OpenAI协议01、OpenAI底层协议快速理解.md | ✅        |
| 2    | OpenAI协议02、AgentForge 适配OpenAI接入核心实践              | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/openai/OpenAI协议02、AgentForge 适配OpenAI接入核心实践.md | ✅        |

##### ✅ Anthropic协议

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | Anthropic协议01、Anthropic底层协议快速理解                   | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/anthropic/Anthropic协议01、Anthropic底层协议快速理解.md | ✅        |
| 2    | Anthropic协议02、AgentForge 适配Anthropic接入核心实践        | docs/dev/backend/AgentForge核心模块设计原理文章系列/01、model模型协议层/chatmodel/anthropic/Anthropic协议02、AgentForge 适配Anthropic接入核心实践.md | ✅        |







### 2.2、EmbeddingModel（Embedding向量层）







---

## 三、第二层：通用 ReAct Agent 层（agentforge-agent-core）

> 所属模块：`agentforge-agent-core`
>
> 设计目标：包含最底层原生 ReAct Agent 的非流式 & 流式核心实现规范。

### 3.1、兼容function call协议扩展（local、http、mcp）

![image-20261007124932133](https://pictured-bed.oss-cn-beijing.aliyuncs.com/img/2024/202610071249414.png)  

> 标准function call协议设计定义

参考2.1中的标准function call协议设计定义

#### ✅ local本地tool扩展

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理 | docs/dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/local/Java代码转换FunctionCall协议01、AgentForge Local工具模块核心设计原理.md | ✅        |

#### ✅ http协议tool扩展

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | 适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理 | docs/dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/http/适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理.md | ✅        |

#### ✅ mcp协议tool扩展

| 序号 | 文章名称                                                     | 文章位置                                                     | 完成进度 |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | -------- |
| 1    | MCP协议转换FunctionCall协议01、MCP协议详解-历史演进与底层原理        | docs/dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议01、MCP协议详解-历史演进与底层原理.md | ✅        |
| 2    | MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现       | docs/dev/backend/AgentForge核心模块设计原理文章系列/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现.md | ✅        |











---

## 四、第三层：Harness Agent 层

> 所属模块：`agentforge-agent-core`（Harness 组件）
>
> 设计目标：补齐 Agent 运行环境 & 权限工具审核策略机制 & 相关 Harness 基础组件。

### 核心技术博客文章

```shell

```

---

## 五、第四层：Agent应用服务层（AgentForge-Service）

> 竞品参考：Agent-core、Coze 等
>
> 后续会独立一个工程立项。

```shell

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
