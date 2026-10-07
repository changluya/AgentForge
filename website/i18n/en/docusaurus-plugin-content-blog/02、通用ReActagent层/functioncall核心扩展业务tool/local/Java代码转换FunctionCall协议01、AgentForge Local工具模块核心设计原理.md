---
title: "Java Code to FunctionCall Protocol 01, Core Design Principles of the AgentForge Local Tool Module"
date: 2026-10-07
tags: [AgentForge, General ReAct Agent Layer, Function Calling, Local]
---

# Core Design Principles of the AgentForge Local Tool Module

> Updated: 2026-10-05  
> Applicable module: `agentforge-agent-core` (`cloud.changlu.agentforge.agent.tool.local`)  
> Dependent contracts: `agentforge-model-api` / `agentforge-model-core`  
> Maintainer: changlu

{/* truncate */}


One-sentence conclusion: **The Local tool module automatically scans methods annotated with `@Tool` on Java objects into `ToolSpecification`s, and uses reflection to bind the JSON arguments provided by the model to method parameters—implementing "let the model directly call your Java methods" with zero dependencies.**

---

## 1. Background and Problem Introduction

## 1.1 Scenario-driven: conveniently exposing internal methods to the model

While integrating AgentForge into internal CRM and ticketing systems, we encountered a very realistic scenario:

> The team already has a batch of ready-made Java methods such as `CrmService.queryCustomer(...)` and `TicketService.createTicket(...)`, and hopes to let the Agent call them with **almost zero changes**.

This brought three concrete pain points:

1. **Manual registration**: hand-writing a tool description and JSON Schema for every method—repetitive and error-prone;
2. **Hard argument binding**: the model supplies JSON, which must be manually parsed and type-converted (enums, arrays, numeric bounds, ...);
3. **No heavy dependencies allowed**: the core is Java 8 + zero third-party libraries, so Jackson / Gson cannot be used for deserialization.

<br/>

## 1.2 Problem-led: how to turn an "ordinary Java method" into a tool at zero cost?

> **Problem**: How can an already-written Java method become a model-callable tool just by adding an annotation?

Three things must be solved:

1. **Declaration**: automatically generate a `ToolSpecification` (name, description, parameter Schema) from the method signature;
2. **Binding**: bind the `arguments` (JSON string) returned by the model to the method parameters;
3. **Conversion**: safely convert `Long` / `String` etc. in JSON into the `int` / `Enum` / `List` / `BigDecimal` expected by the method.

<br/>

## 1.3 Design goals and constraints

| Goal | Description |
|---|---|
| Annotation-driven | Add only `@Tool` / `@P`; do not change business logic |
| Strongly typed binding | Support enums, numeric bounds, BigDecimal / BigInteger, UUID, collections / Map |
| Zero dependencies | Reuse the built-in `Json`; do not pull in a JSON library |
| Unified | The output is `Map<ToolSpecification, ToolExecutor>`, the same tool table as http / mcp |
| Observable | Optional execution logging decorator |

---

## 2. Core Concepts

## 2.1 Two annotations: `@Tool` and `@P`

```java
@Tool(name = "getWeather", value = "查询城市天气")
public String getWeather(@P(name = "city", description = "城市名") String city) {
    return weatherApi.query(city);
}
```

- `@Tool`: marks the method as a tool; `name` defaults to the method name, `value` is the description;
- `@P`: overrides the parameter name and description (when javac is not run with `-parameters`, reflection yields `arg0`, and this is the fallback).

> **Key point**: The parameter **name** determines the key in the model's JSON; if `@P` is absent and `-parameters` is off, the key becomes `arg0`, so always name parameters explicitly in production.

<br/>

## 2.2 Generating Schema via reflection

`ToolSpecifications.toolSpecificationFrom(method)` derives it from the method signature:

| Method element | Maps to |
|---|---|
| `@Tool.name` / method name | `ToolSpecification.name` |
| `@Tool.value` | `ToolSpecification.description` |
| Each parameter name (`@P.name` / reflected name) | JSON Schema `properties` key |
| Parameter type | JSON Schema `type` (string / integer / number / boolean / array / object) |
| Parameter | **Always required** (the current implementation fixes it to `true`) |

<br/>

## 2.3 `LocalToolExecutor`: the reflection invoker

It holds **an object instance + a method**, and on `execute` binds the arguments and then invokes reflectively:

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

## 2.4 Return value conventions (key)

| Method return type | Content returned to the model |
|---|---|
| `void` | The literal `"Success"` |
| `String` | Returned as-is |
| Other types | `Json.stringify(result)` |

<br/>

## 2.5 Exception strategy

`LocalToolExecutor` provides two switches (both default to `false`):

| Switch | Off (default) | On |
|---|---|---|
| `wrapToolArgumentsExceptions` | Argument errors are thrown as-is | Wrapped as `ToolArgumentsException` |
| `propagateToolExecutionExceptions` | Exceptions inside the method → return the exception message text | Throw `ToolExecutionException(cause)` |

> **Note**: By default, "method throws → the message is returned to the model as the result", which helps the model self-correct; enable `propagateToolExecutionExceptions` when strong failure semantics are needed.

---

## 3. Implementation Approach

## 3.1 Comparison of three approaches

**Approach 1: Hand-write a `ToolExecutor` for every method**

- Pros: Fully controllable.
- Drawbacks: A large amount of repetitive JSON parsing and type-conversion code, with high maintenance cost.

**Approach 2: Introduce Jackson / Gson to deserialize directly into DTOs**

- Pros: Great developer experience.
- Drawbacks: **Breaks the core's zero-dependency constraint** (Java 8, no third-party libraries), and is disconnected from AgentForge's built-in `Json` system.

**Approach 3: Annotations + reflection + self-built type conversion (adopted by this module)**

- Pros: Zero dependencies, annotation-driven, cohesive argument binding and conversion, pluggable proxying/logging.
- Drawbacks: Requires maintaining your own type-conversion matrix; complex generics (e.g. `List<CustomDto>`) need your own fallback.

> **Conclusion**: Adopt **Approach 3**—reuse the built-in `Json` and trade a testable type-conversion matrix for zero dependencies.

<br/>

## 3.2 Scanning and table-building flow

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

## 3.3 Argument binding pipeline

```text
参数为空? → 基本类型给默认值(0/false/'\0')，对象类型给 null
参数非空? → coerceArgument(值, 参数名, 目标Class, 泛型Type)
```

Parameter name resolution priority: `@P.name()` → `parameter.getName()`.  
**Proxy compatibility**: AOP proxy methods lose parameter names, so the constructor supports `(object, originalMethod, methodToInvoke)`—use the original method to get parameter names and the proxy method to actually invoke.

<br/>

## 3.4 Type-conversion matrix (core)

`coerceArgument` handles each target type in turn; out-of-range / invalid values error out directly:

| Target type | Conversion strategy |
|---|---|
| `String` | `argument.toString()` |
| Enum | `Enum.valueOf`, then try `toUpperCase()` on failure |
| `Boolean` / `boolean` | Only accepts `Boolean`; otherwise errors |
| `Double` / `Float` | String or number → double; Float performs bounds checking |
| `BigDecimal` | `BigDecimal.valueOf(double)` |
| `Integer` / `Long` / `Short` / `Byte` | Uses `getBoundedLongValue` (non-fractional + bounds checking) |
| `BigInteger` | Non-fractional double → `BigDecimal.toBigInteger()` |
| `UUID` | `UUID.fromString` |
| `Collection` / `Map` | Delegated to `LocalToolArgumentConverter` to parse JSON |

> **Key point**: All numeric conversions go through a **double intermediate state + bounds checking** to avoid silent overflow; assigning a non-integer to an integer type errors out explicitly.

<br/>

## 3.5 Complex arguments and "double-encoding" tolerance

The model sometimes **re-encodes an array / object as a string** before passing it in (e.g. `"[1,2,3]"` or `"\"{...}\""`). `LocalToolArgumentConverter`'s strategy:

1. If the argument is already an object → return as-is;
2. If it is a string → `Json.parse`; return if the type matches;
3. If it does not match → `cleanJsonString` (strip outer quotes, restore escapes) and retry;
4. If it still fails → throw `IllegalArgumentException`.

`LocalToolExecutionRequestUtil.argumentsAsMap` additionally tolerates: **trailing commas** and **JSON wholly wrapped in quotes**.

<br/>

## 3.6 Logging decoration

`LoggingToolExecutor` is a decorator of `ToolExecutor` that prints the tool name, input arguments, output and elapsed time via `java.util.logging` before and after execution. `buildLocalToolsWithLogging(objects, true)` enables it.

---

## 4. Practical Code

## 4.1 Defining a tool

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

## 4.2 Registering with ToolService

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalToolsWithLogging(
                Collections.singletonList(new CrmTools()), true);

ToolService toolService = new ToolService();
toolService.tools(tools);

ReActAgent agent =
        ReActAgent.builder().chatModel(chatModel).toolService(toolService).build();
```

**Run output (simulated terminal)**

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

## 4.3 How a single model invocation is closed out

> **Corresponding unit test**: `LocalToolLoopTest#shouldCloseTheCrmLoopThroughToolService`—runs the full offline path "scan → register → hit → bind → reflective invoke → feed back".

Using `queryCustomer` from 4.1/4.2 as an example, breaking it down step by step:

**① Scan and registration (build time)**

```java
Map<ToolSpecification, ToolExecutor> tools =
        LocalToolFactory.buildLocalTools(Collections.singletonList(new CrmTools()));
ToolService toolService = new ToolService();
toolService.tools(tools);   // toolExecutors["queryCustomer"] = LocalToolExecutor(crmTools, method)
```

- `ToolSpecifications.toolSpecificationFrom(method)` generates `ToolSpecification{ name=queryCustomer, parameters={ name:string(required), limit:integer(required) } }`;
- `LocalToolExecutor` binds **the object instance + method** (for AOP proxies: use the original method for parameter names and the proxy method for the actual invocation).

**② Model-side function call**

```json
{"name": "queryCustomer", "arguments": "{\"name\":\"长路\",\"limit\":5}"}
```

**③ Normalized into a request**

```text
ToolExecutionRequest{ id="call_1", name="queryCustomer", arguments="{\"name\":\"长路\",\"limit\":5}" }
```

**④ ToolService locates the executor**

`toolExecutors.get("queryCustomer")` → hits `LocalToolExecutor`.

**⑤ Argument binding (prepareArguments)**

| Method parameter | Parameter name source | Value | `coerceArgument` |
|---|---|---|---|
| `String name` | `@P.name() = "name"` | `"长路"` | STRING → `"长路"` |
| `int limit` | `@P.name() = "limit"` | `5` (Json parses as a number) | double intermediate + non-fractional + bounds check → `(int) 5` |

> **Note**: When an argument is missing, primitive types get their defaults (`int → 0`, `boolean → false`) and object types get `null`; the parameter name is taken from `@P.name()` first, otherwise from the reflected parameter name.

**⑥ Reflective invocation and return normalization**

`methodToInvoke.invoke(crmTools, ["长路", 5])`; the return value is handled by convention: `String` as-is, `void` → `"Success"`, others → `Json.stringify`.

**⑦ Feed back to the model**

The tool return value is fed back as the result → the model answers accordingly.

**Run output (simulated terminal)**

```shell
[user]  查一下客户"长路"的信息
[think] 调用 queryCustomer
[act]   queryCustomer {"name":"长路","limit":5}
[reflect] CrmTools.queryCustomer("长路", 5) → customer:长路,limit:5
[think] 已查询到客户长路。
[final] 已查询到客户长路。
```

---

## 5. Verification and Testing

## 5.1 Unit test overview

`LocalToolFactoryTest` covers factory scanning, logging decoration, and argument-tool tolerance; `LocalToolExecutorTest` covers argument binding and the type-conversion matrix. All run offline.

## 5.2 Test list (current implementation, all passing)

| Case | Coverage |
|---|---|
| `shouldBuildToolsFromAnnotatedObject` | Scans `@Tool` to build the mapping |
| `shouldDecorateExecutorWithLogging` | Logging decorator |
| `shouldReturnEmptyMapForNullOrEmptyInput` | Empty input boundary |
| `shouldBindStringArgumentUsingPAnnotation` | `@P` parameter name binding |
| `shouldCoerceNumericArguments` | Numeric type conversion |
| `shouldUsePrimitiveDefaultWhenArgumentMissing` | Primitive defaults |
| `shouldCoerceEnumNameIgnoringCase` | Enum case-insensitivity |
| `shouldConvertJsonStringIntoListCollection` | String → List |
| `shouldPassThroughNativeList` | Native List pass-through |
| `shouldConvertJsonStringIntoMap` | String → Map |
| `shouldReturnSuccessForVoidMethod` | void → `"Success"` |
| `shouldSerializeNonStringReturnValueAsJson` | Non-String return → JSON |
| `shouldReturnThrownMessageByDefault` | Default exception-degradation text |
| `shouldThrowWrappedExecutionExceptionWhenPropagating` | Exception propagation switch |
| `shouldWrapArgumentsExceptionWhenConfigured` | Argument exception wrapping switch |
| `shouldTolerateTrailingCommaWhenParsingArguments` | Trailing comma tolerance |
| `shouldConvertDoubleEncodedJsonString` | Double-encoded JSON tolerance |
| `LocalToolLoopTest#shouldCloseTheCrmLoopThroughToolService` | **End-to-end closure**: `@Tool` scan → ToolService → function call → argument binding → reflective invoke → feed back (aligned with 4.3) |

<br/>

## 5.3 Boundaries and risks

- **Parameter name dependency**: Without `-parameters` and without `@P`, the model sees `arg0`; always name explicitly.
- **required fixed to true**: All parameters are currently mandatory; optional parameters need upper-layer fallback or a default value.
- **Generic erasure**: `List<CustomDto>` can only be parsed into a `Map` / `List` structure and cannot be automatically instantiated into a concrete type.
- **Strict booleans**: A `Boolean` field accepts only boolean values; the string `"true"` is not implicitly converted.

---

## 6. Summary and Outlook

1. **Essence**: `@Tool` annotation + reflection + type conversion turn ordinary Java methods into model-callable tools.
2. **Core**: `ToolSpecifications` generates the Schema; `LocalToolExecutor` binds arguments and invokes reflectively.
3. **Zero dependencies**: Reuses the built-in `Json`, consistent with the Java 8 / zero-third-party-library constraint.
4. **Engineering**: Configurable exception strategy, decoratable logging, proxy compatible.
5. **Outlook**: Optional parameters (controllable required), generic DTO deserialization, SpEL / expression default values.

---

## References

[1]. [Java Reflection Tutorial (Oracle)](https://docs.oracle.com/javase/tutorial/reflect/)

[2]. [JSON Schema Official Site](https://json-schema.org/)

[3]. [Java Annotations Tutorial (Oracle)](https://docs.oracle.com/javase/tutorial/java/annotations/)

[4]. Related internal documents: [Core Design Principles of the HTTP Tool Module](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/http/适配HTTP协议转换FunctionCall协议01、AgentForge HTTP工具模块核心设计原理), [MCP Framework Research and AgentForge Integration Implementation](/blog/02、通用ReActagent层/functioncall核心扩展业务tool/mcp/MCP协议转换FunctionCall协议02、MCP框架调研与AgentForge接入实现)

<br/>

Compiler: changlu Created: 2026.10.5 Updated: 2026.10.5
