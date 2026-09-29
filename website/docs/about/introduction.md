---
title: AgentForge 介绍
sidebar_label: AgentForge 介绍
description: 了解 AgentForge 的定位、设计理念与模块结构
---
# AgentForge 介绍

AgentForge 是一个面向 Java 开发者、从 **LLM 最底层能力开始构建** 的开源 Agent Framework。

它先建立稳定、统一、可扩展的模型抽象，再逐层构造 Context、Memory、Tool、Reasoning、Agent Runtime 与 Multi-Agent 能力。

> **Forge Intelligence into Action. 将智能锻造成行动。**

## 为什么选择自底向上

Agent 的上层能力最终都会落到模型调用上。AgentForge 让框架上层只依赖统一的 `ChatModel` 与 `StreamingChatModel`，避免与特定厂商 SDK 紧密绑定。

```text
LLM → Message / Request / Response → Context / Memory
    → Tool / Reasoning → Agent Runtime → Real Action
```

## 当前模块

- `agentforge-llm-core`：统一消息、请求、响应、模型与工具抽象；
- `agentforge-llm-openai` / `agentforge-llm-anthropic`：Provider Adapter；
- `agentforge-ai-core`：模型工厂与核心配置；
- `agentforge-ai-agent`：ReAct Agent、上下文、记忆、中间件与重试；
- `agentforge-examples`：示例与 AgentForge Studio。

接下来可以从[快速开始](../quickstart/getting-started.md)运行项目。
