# AgentForge Local 工具模块核心设计原理

> 更新日期：2026-10-05  
> 适用模块：`agentforge-agent-core`（`cloud.changlu.agentforge.agent.tool.local`）  
> 依赖契约：`agentforge-model-api` / `agentforge-model-core`  
> 维护者：changlu

一句话结论：**Local 工具模块把 Java 对象上标注 `@Tool` 的方法自动扫描为 `ToolSpecification`，并用反射把模型给的 JSON 参数绑定到方法入参——零依赖地实现"让模型直接调用你的 Java 方法"。**

---

# 一、背景与问题引入

## 1.1、场景驱动：把内部方法"顺手"暴露给模型

在把 AgentForge 接入内部 CRM、工单系统时，我们遇到一个很现实的场景：

> 团队已经有 `CrmService.queryCustomer(...)`、`TicketService.createTicket(...)` 这样一批现成的 Java 方法，希望**几乎零改动**地让 Agent 调用它们。

由此带来三个具体痛点：

1. **手工登记**：为每个方法手写工具描述与 JSON Schema，重复且易错；
2. **参数难绑**：模型给的是 JSON，要手动解析、做类型转换（枚举、数组、数值边界……）；
3. **不能引重依赖**：核心是 Java 8 + 零第三方库，不能用 Jackson / Gson 做反序列化。

<br/>

## 1.2、问题引导：如何让"普通 Java 方法"零成本变工具？

> **问题**：如何让一个已经写好的 Java 方法，被打个注解就变成模型可调用的工具？

核心要解决三件事：

1. **声明**：从方法签名自动生成 `ToolSpecification`（名字、描述、参数 Schema）；
2. **绑定**：把模型返回的 `arguments`（JSON 字符串）绑定到方法入参；
3. **转换**：把 JSON 里的 `Long` / `String` 等，安全地转成方法期望的 `int` / `Enum` / `List` / `BigDecimal`。

<br/>

## 1.3、设计目标与约束

| 目标 | 说明 |
|---|---|
| 注解驱动 | 只加 `@Tool` / `@P`，不改业务逻辑 |
| 强类型绑定 | 支持枚举、数值边界、BigDecimal / BigInteger、UUID、集合 / Map |
| 零依赖 | 复用内置 `Json`，不引 JSON 库 |
| 统一 | 产物是 `Map<ToolSpecification, ToolExecutor>`，与 http / mcp 同一张工具表 |
| 可观测 | 可选的执行日志装饰器 |

---

# 二、核心概念讲解

## 2.1、两个注解：`@Tool` 与 `@P`

```java
@Tool(name = "getWeather", value = "查询城市天气")
public String getWeather(@P(name = "city", description = "城市名") String city) {
    return weatherApi.query(city);
}
```

- `@Tool`：标记方法为工具；`name` 缺省用方法名，`value` 为描述；
- `@P`：覆盖参数名与描述（当 javac 未开 `-parameters` 时，反射拿到的是 `arg0`，靠它兜底）。

> **重点**：参数**名称**决定模型 JSON 的键；若未加 `@P` 且未开 `-parameters`，键会变成 `arg0`，所以生产环境务必显式命名。

<br/>

## 2.2、反射生成 Schema

`ToolSpecifications.toolSpecificationFrom(method)` 从方法签名推导：

| 方法要素 | 映射到 |
|---|---|
| `@Tool.name` / 方法名 | `ToolSpecification.name` |
| `@Tool.value` | `ToolSpecification.description` |
| 每个参数名（`@P.name` / 反射名） | JSON Schema `properties` 键 |
| 参数类型 | JSON Schema `type`（string / integer / number / boolean / array / object） |
| 参数 | **一律 required**（当前实现固定 `true`） |

<br/>

## 2.3、`LocalToolExecutor`：反射调用器

它持有 **对象实例 + 方法**，`execute` 时把参数绑定后反射调用：

```text
arguments(JSON) ──► LocalToolExecutionRequestUtil.argumentsAsMap
                          │
                          ▼
               prepareArguments(originalMethod, map)
                          每个参数：@P 名 → 取值 → coerceArgument 类型转换
                          │
                          ▼
               methodToInvoke.invoke(object, args)   （代理场景用原始方法取参数名）
                          │
                          ▼
               返回值规范化 → String（回灌模型）
```

<br/>

## 2.4、返回值约定（重点）

| 方法返回类型 | 回给模型的内容 |
|---|---|
| `void` | 字面量 `"Success"` |
| `String` | 原样返回 |
| 其它类型 | `Json.stringify(result)` |

<br/>

## 2.5、异常策略

`LocalToolExecutor` 提供两个开关（默认均 `false`）：

| 开关 | 关闭（默认） | 打开 |
|---|---|---|
| `wrapToolArgumentsExceptions` | 参数错误按原样抛出 | 包装为 `ToolArgumentsException` |
| `propagateToolExecutionExceptions` | 方法内部异常 → 返回异常消息文本 | 抛出 `ToolExecutionException(cause)` |

> **注意**：默认"方法抛异常 → 把消息当结果回给模型"，利于模型自纠；需要强失败语义时打开 `propagateToolExecutionExceptions`。

---

# 三、实现思路

## 3.1、三种方案对比

**方案一：为每个方法手写 `ToolExecutor`**

- 优点：完全可控。
- 弊端说明：大量重复的 JSON 解析与类型转换代码，维护成本高。

**方案二：引入 Jackson / Gson 直接反序列化到 DTO**

- 优点：开发体验好。
- 弊端说明：**破坏核心零依赖**（Java 8、无第三方库），且与 AgentForge 内置 `Json` 体系割裂。

**方案三：注解 + 反射 + 自研类型转换（本模块采用）**

- 优点：零依赖、注解驱动、参数绑定与转换内聚、代理/日志可插拔。
- 弊端说明：需自行维护类型转换矩阵；复杂泛型（如 `List<CustomDto>`）需自行兜底。

> **结论**：采用**方案三**——复用内置 `Json`，用一层可测的类型转换矩阵换取零依赖。

<br/>

## 3.2、扫描与建表流程

```text
LocalToolFactory.buildLocalTools(objects)
   for each object:
       拒绝 Class（必须是实例）
       for each declaredMethod with @Tool:
           spec = ToolSpecifications.toolSpecificationFrom(method)
           重名 → 抛 IllegalArgumentException("Duplicated definition for tool: x")
           executor = new LocalToolExecutor(object, method)
                       （hasLogging=true 时再包一层 LoggingToolExecutor）
           结果放入 Map<ToolSpecification, ToolExecutor>
   → 交给 ToolService 按 spec.name() 建表
```

<br/>

## 3.3、参数绑定流水线

```text
参数为空? → 基本类型给默认值(0/false/'\0')，对象类型给 null
参数非空? → coerceArgument(值, 参数名, 目标Class, 泛型Type)
```

参数名解析优先级：`@P.name()` → `parameter.getName()`。  
**代理兼容**：AOP 代理方法会丢失参数名，故构造器支持 `(object, originalMethod, methodToInvoke)`——用原始方法取参数名，用代理方法实际调用。

<br/>

## 3.4、类型转换矩阵（核心）

`coerceArgument` 按目标类型逐一处理，越界 / 非法值直接报错：

| 目标类型 | 转换策略 |
|---|---|
| `String` | `argument.toString()` |
| 枚举 | `Enum.valueOf`，失败再试 `toUpperCase()` |
| `Boolean` / `boolean` | 仅接受 `Boolean`，否则报错 |
| `Double` / `Float` | 字符串或数字 → double，Float 做边界检查 |
| `BigDecimal` | `BigDecimal.valueOf(double)` |
| `Integer` / `Long` / `Short` / `Byte` | 走 `getBoundedLongValue`（非小数 + 边界校验） |
| `BigInteger` | 非小数 double → `BigDecimal.toBigInteger()` |
| `UUID` | `UUID.fromString` |
| `Collection` / `Map` | 交给 `LocalToolArgumentConverter` 解析 JSON |

> **重点**：所有数值转换都走 **double 中间态 + 边界检查**，避免静默溢出；非整数赋给整型会明确报错。

<br/>

## 3.5、复杂参数与"双重编码"容错

模型有时会把数组 / 对象**再编码成字符串**传进来（例如 `"[1,2,3]"` 或 `"\"{...}\""`）。`LocalToolArgumentConverter` 的策略：

1. 参数本就是对象 → 原样返回；
2. 是字符串 → `Json.parse`，类型匹配则返回；
3. 不匹配 → `cleanJsonString`（去外层引号、还原转义）后再试；
4. 仍失败 → 抛 `IllegalArgumentException`。

`LocalToolExecutionRequestUtil.argumentsAsMap` 还额外容忍：**尾逗号**、**整体被引号包裹的 JSON**。

<br/>

## 3.6、日志装饰

`LoggingToolExecutor` 是 `ToolExecutor` 的装饰器，在执行前后用 `java.util.logging` 打印工具名、入参、出参与耗时。`buildLocalToolsWithLogging(objects, true)` 即启用。

---

# 四、实战代码

## 4.1、定义工具

```java
public class CrmTools {

    @Tool(name = "queryCustomer", value = "按客户名查询客户信息")
    public String queryCustomer(
            @P(name = "name", description = "客户名称") String name,
            @P(name = "limit", description = "返回条数") int limit) {
        return crmService.query(name, limit);
    }
}
```

<br/>

## 4.2、注册到 ToolService

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalToolsWithLogging(
                Collections.singletonList(new CrmTools()), true);

ToolService toolService = new ToolService();
toolService.tools(tools);

ReActAgent agent =
        ReActAgent.builder().chatModel(chatModel).toolService(toolService).build();
```

**运行输出（模拟终端）**

```shell
# 1) 注册后模型可见的工具（tools[] 摘要）
[queryCustomer] 按客户名查询客户信息
  parameters: { name: string (required), limit: integer (required) }

# 2) 打开 FINE 日志后，一次工具执行（LoggingToolExecutor 装饰）
=== 工具执行请求 ===
工具名称: queryCustomer
参数: {"name":"长路","limit":5}
记忆ID: session-1
===================
=== 工具执行响应 ===
结果: {"name":"长路","level":"VIP"}
耗时: 3 毫秒
===================
```

<br/>

## 4.3、一次模型调用如何被闭环

> **对应单测**：`LocalToolLoopTest#shouldCloseTheCrmLoopThroughToolService`——离线完整走通"扫描 → 注册 → 命中 → 绑定 → 反射调用 → 回灌"。

以 4.1/4.2 的 `queryCustomer` 为例，逐环节拆解：

**① 扫描与注册（build 期）**

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalTools(Collections.singletonList(new CrmTools()));
ToolService toolService = new ToolService();
toolService.tools(tools);   // toolExecutors["queryCustomer"] = LocalToolExecutor(crmTools, method)
```

- `ToolSpecifications.toolSpecificationFrom(method)` 生成 `ToolSpecification{ name=queryCustomer, parameters={ name:string(required), limit:integer(required) } }`；
- `LocalToolExecutor` 绑定**对象实例 + 方法**（AOP 代理时：用原方法取参数名、用代理方法实际调用）。

**② 模型侧 function call**

```json
{"name": "queryCustomer", "arguments": "{\"name\":\"长路\",\"limit\":5}"}
```

**③ 归一化为请求**

```text
ToolExecutionRequest{ id="call_1", name="queryCustomer", arguments="{\"name\":\"长路\",\"limit\":5}" }
```

**④ ToolService 定位执行器**

`toolExecutors.get("queryCustomer")` → 命中 `LocalToolExecutor`。

**⑤ 参数绑定（prepareArguments）**

| 方法参数 | 参数名来源 | 取值 | `coerceArgument` |
|---|---|---|---|
| `String name` | `@P.name() = "name"` | `"长路"` | STRING → `"长路"` |
| `int limit` | `@P.name() = "limit"` | `5`（Json 解析为数值） | double 中间态 + 非小数 + 边界校验 → `(int) 5` |

> **注意**：参数缺失时，基本类型给默认值（`int → 0`、`boolean → false`），对象类型给 `null`；参数名优先取 `@P.name()`，否则取反射参数名。

**⑥ 反射调用与返回规范化**

`methodToInvoke.invoke(crmTools, ["长路", 5])`，返回值按约定处理：`String` 原样、`void` → `"Success"`、其它 → `Json.stringify`。

**⑦ 回灌模型**

工具返回值作为结果回灌 → 模型据此回答。

**运行输出（模拟终端）**

```shell
[user]  查一下客户"长路"的信息
[think] 调用 queryCustomer
[act]   queryCustomer {"name":"长路","limit":5}
[reflect] CrmTools.queryCustomer("长路", 5) → customer:长路,limit:5
[think] 已查询到客户长路。
[final] 已查询到客户长路。
```

---

# 五、验证测试

## 5.1、单元测试概览

`LocalToolFactoryTest` 覆盖工厂扫描、日志装饰、参数工具容错；`LocalToolExecutorTest` 覆盖参数绑定与类型转换矩阵，全部离线执行。

## 5.2、测试清单（当前实现，全部通过）

| 用例 | 覆盖点 |
|---|---|
| `shouldBuildToolsFromAnnotatedObject` | 扫描 `@Tool` 构建映射 |
| `shouldDecorateExecutorWithLogging` | 日志装饰器 |
| `shouldReturnEmptyMapForNullOrEmptyInput` | 空输入边界 |
| `shouldBindStringArgumentUsingPAnnotation` | `@P` 参数名绑定 |
| `shouldCoerceNumericArguments` | 数值类型转换 |
| `shouldUsePrimitiveDefaultWhenArgumentMissing` | 基本类型默认值 |
| `shouldCoerceEnumNameIgnoringCase` | 枚举忽略大小写 |
| `shouldConvertJsonStringIntoListCollection` | 字符串 → List |
| `shouldPassThroughNativeList` | 原生 List 透传 |
| `shouldConvertJsonStringIntoMap` | 字符串 → Map |
| `shouldReturnSuccessForVoidMethod` | void → `"Success"` |
| `shouldSerializeNonStringReturnValueAsJson` | 非 String 返回 → JSON |
| `shouldReturnThrownMessageByDefault` | 默认异常降级文本 |
| `shouldThrowWrappedExecutionExceptionWhenPropagating` | 传播异常开关 |
| `shouldWrapArgumentsExceptionWhenConfigured` | 参数异常包装开关 |
| `shouldTolerateTrailingCommaWhenParsingArguments` | 尾逗号容错 |
| `shouldConvertDoubleEncodedJsonString` | 双重编码 JSON 容错 |
| `LocalToolLoopTest#shouldCloseTheCrmLoopThroughToolService` | **端到端闭环**：`@Tool` 扫描 → ToolService → function call → 参数绑定 → 反射调用 → 回灌（对齐 4.3） |

<br/>

## 5.3、边界与风险

- **参数名依赖**：未开 `-parameters` 且未用 `@P` 时，模型看到 `arg0`，务必显式命名。
- **required 固定为 true**：当前所有参数都必填，可选参数需上层兜底或默认值。
- **泛型擦除**：`List<CustomDto>` 只能解析为 `Map` / `List` 结构，无法自动实例化为具体类型。
- **布尔严格**：`Boolean` 字段只接受布尔值，字符串 `"true"` 不做隐式转换。

---

# 六、总结与展望

1. **本质**：`@Tool` 注解 + 反射 + 类型转换，把普通 Java 方法变成模型可调用的工具。
2. **核心**：`ToolSpecifications` 生成 Schema；`LocalToolExecutor` 绑定参数并反射调用。
3. **零依赖**：复用内置 `Json`，与 Java 8 / 零第三方库约束一致。
4. **工程性**：异常策略可配、日志可装饰、代理可兼容。
5. **展望**：可选参数（required 可控）、泛型 DTO 反序列化、SpEL / 表达式默认值。

---

# 参考资料

[1]. [Java 反射教程（Oracle）](https://docs.oracle.com/javase/tutorial/reflect/)

[2]. [JSON Schema 官方站点](https://json-schema.org/)

[3]. [Java 注解教程（Oracle）](https://docs.oracle.com/javase/tutorial/java/annotations/)

[4]. 相关内部文档：[HTTP 工具模块核心设计原理](../http/适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理)、[MCP 框架调研与 AgentForge 接入实现](../mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现)

<br/>

整理者:长路 创建时间:2026.10.5 更新时间:2026.10.5
