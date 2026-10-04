# AgentForge BOM 与 Parent 维护规范

## 1. 目标

AgentForge 将 Maven 工程拆分为 Aggregator、Parent 和 BOM 三种职责，避免模块聚合、构建配置和依赖版本混在同一个 POM 中。

```text
agentforge-aggregator (repository root pom.xml)
├── agentforge-ai-parent   统一第三方依赖与构建配置
├── agentforge-ai-bom      统一 AgentForge 可发布模块版本
├── agentforge-model
├── agentforge-framework
└── agentforge-examples
```

核心原则：

1. 根 `pom.xml` 只负责 Reactor 模块聚合和全局 `${revision}` 声明。
2. `agentforge-ai-parent` 管理第三方依赖版本、Maven 插件版本和通用构建行为。
3. `agentforge-ai-bom` 管理对外可用的 AgentForge 模块及兼容版本组合。
4. 所有工程模块使用同一个 `${revision}`，不在各模块分散维护当前工程版本。
5. Parent 和 BOM 都是 `packaging=pom`，二者不承担模块聚合职责。

## 2. 各层职责

### 2.1 Aggregator

位置：仓库根目录 `pom.xml`。

Aggregator 用于声明 `<modules>`，使一条 Maven 命令可以按 Reactor 顺序构建整个仓库。它不是其他模块的 Parent，也不应放置第三方依赖或通用插件配置。

新增一级聚合模块时，将它加入根 `<modules>`。模块顺序应保持 Parent、BOM、基础库、上层框架、示例应用的逻辑顺序。

### 2.2 Parent

位置：`agentforge-ai-parent/pom.xml`。

Parent 统一管理：

- Java 编译目标、字符集等通用属性；
- JUnit 等第三方依赖的版本；
- Compiler、Surefire、Source、Flatten 等 Maven 插件的版本与通用配置；
- 许可证、项目地址等可继承的项目元数据。

第三方依赖版本必须首先定义为有语义的属性，再放入 `<dependencyManagement>`：

```xml
<properties>
    <example-library.version>1.2.3</example-library.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>example-library</artifactId>
            <version>${example-library.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

业务模块引入该依赖时不再写 `<version>`。只需统一版本而不应强制所有子模块引入的依赖，必须放在 `<dependencyManagement>`，不得直接放入 Parent 的 `<dependencies>`。

### 2.3 BOM

位置：`agentforge-ai-bom/pom.xml`。

BOM 继承 `agentforge-ai-parent`，通过 `<dependencyManagement>` 公布一组经过测试的 AgentForge 模块版本。当前管理：

- `agentforge-model-api`
- `agentforge-model-core`
- `agentforge-model-openai`
- `agentforge-model-anthropic`
- `agentforge-model-registry`
- `agentforge-agent-core`

聚合 POM 和示例应用默认不加入 BOM，因为它们不是面向使用者的可复用依赖。

## 3. 版本管理规范

### 3.1 工程统一版本

当前工程版本由根 POM 和 Parent 中的 `revision` 表达：

```xml
<properties>
    <revision>1.0.0-SNAPSHOT</revision>
</properties>
```

子模块 Parent 版本和工程内依赖版本统一使用 `${revision}`。修改项目版本时，必须同步修改根 POM 和 `agentforge-ai-parent/pom.xml` 中的默认值，或在 CI 中通过 `-Drevision=...` 覆盖：

```bash
mvn -Drevision=1.1.0-SNAPSHOT clean verify
```

### 3.2 Flatten Maven Plugin

Parent 启用 `flatten-maven-plugin` 的 `resolveCiFriendliesOnly` 模式，发布时将项目和 Parent 坐标中的 `${revision}` 解析为真实版本。BOM 覆盖为 `bom` 模式，保留有效的依赖管理清单，同时保留 `revision` 属性以解析 BOM 内部的统一模块版本。

不要手工修改构建产生的 `.flattened-pom.xml`；应始终修改源 `pom.xml`。

## 4. BOM 使用方式

外部 Maven 项目在 `<dependencyManagement>` 中导入 BOM：

```xml
<properties>
    <agentforge.version>1.0.0-SNAPSHOT</agentforge.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cloud.changlu.agentforge</groupId>
            <artifactId>agentforge-ai-bom</artifactId>
            <version>${agentforge.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

之后引入 AgentForge 模块无需声明版本：

```xml
<dependencies>
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-agent-core</artifactId>
    </dependency>
    <dependency>
        <groupId>cloud.changlu.agentforge</groupId>
        <artifactId>agentforge-model-openai</artifactId>
    </dependency>
</dependencies>
```

Parent 是给 AgentForge 仓库内部模块继承的构建基线；外部业务项目通常应导入 BOM，而不是继承 Parent，以免影响自身已有的 Parent 体系。

## 5. 新增 AgentForge 模块

新增一个可发布的 JAR 模块时，按以下顺序操作：

1. 在对应聚合 POM 的 `<modules>` 中注册模块。
2. 使模块直接或间接继承 `agentforge-ai-parent`。
3. Parent 版本使用 `${revision}`，并设置正确的 `<relativePath>`。
4. 将新模块的 GAV 加入 `agentforge-ai-bom` 的 `<dependencyManagement>`。
5. 工程内对其他 AgentForge 模块的依赖使用 `${revision}`。
6. 若新增第三方依赖，先在 Parent 定义版本属性和 dependency management，再在业务模块无版本引入。
7. 更新 README 的工程结构和本文档的 BOM 模块清单。
8. 执行第 8 节的验证清单。

示例、Demo、测试工具或纯聚合模块不应自动进入 BOM；只有当其成为对外承诺兼容性的可复用产物时才加入。

## 6. 新增或升级第三方依赖

1. 在 Parent `<properties>` 中新增或修改 `*.version`。
2. 在 Parent `<dependencyManagement>` 维护完整 GAV。
3. 业务模块不写第三方依赖版本。
4. 优先导入成熟生态的官方 BOM，不要手工拆分维护一组本应一致的版本。
5. 执行全量测试和 `dependency:tree`，检查版本收敛、传递依赖及排除项。
6. 记录不兼容变更、Java 版本要求和迁移方式。

不得在业务模块中为同一第三方库长期保留不同版本。如果确实需要特例，必须在 POM 旁说明原因、影响范围和移除条件。

## 7. 发布与后续迭代

### 7.1 Snapshot 迭代

- 开发阶段使用 `x.y.z-SNAPSHOT`。
- Parent、BOM 和所有可发布模块必须使用同一 revision。
- CI 至少执行 `mvn clean verify`。

### 7.2 Release 发布

1. 确认工作区只包含本次发布内容。
2. 以 `-Drevision=x.y.z` 运行全量 `clean verify`。
3. 检查 Flatten 后的 Parent 和 BOM POM：项目坐标必须已解析为真实版本；BOM 依赖中如果保留 `${revision}`，必须同时保留可解析的 `revision` 属性。
4. 先发布 Parent 和 BOM，再发布其余模块；Reactor 构建应依据 `<modules>` 顺序完成。
5. 使用一个独立的最小 Maven 项目导入已发布 BOM，验证无版本的 AgentForge 依赖可正常解析。
6. 打标签并将下一个开发版本切换回 `-SNAPSHOT`。

### 7.3 版本兼容策略

- Patch：兼容性修复，不主动改变公共 API。
- Minor：向后兼容的新能力，可增加新模块。
- Major：允许不兼容变更，必须提供迁移说明。

即使仍处于 API 快速迭代阶段，BOM 中的每一组版本也必须经过全量 Reactor 测试，不得发布未经验证的交叉版本组合。

## 8. 变更验证清单

每次修改 Parent、BOM、版本或模块结构后，至少执行：

```bash
# POM 模型与 Reactor 结构
mvn validate

# 全量编译与测试
mvn clean verify

# 检查关键模块的最终依赖版本
mvn -pl agentforge-framework/agentforge-agent-core dependency:tree

# 检查有效 POM 中的继承、版本与插件配置
mvn -pl agentforge-framework/agentforge-agent-core help:effective-pom
```

验收标准：

- Reactor 包含 Parent、BOM 和全部已注册模块；
- 所有模块版本与指定 revision 一致；
- 对外 JAR 都已被 BOM 管理；
- 业务模块中没有可由 Parent 管理的第三方硬编码版本；
- 全量测试通过；
- Flatten 后的发布 POM 可被独立消费项目正常解析。
