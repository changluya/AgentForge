---
title: 快速开始
sidebar_label: 快速开始
description: 获取源码并构建 AgentForge
---
# 快速开始

## 环境要求

- JDK 8 或 JDK 17
- Maven 3.8+
- Git

## 获取并构建

```bash
git clone https://github.com/changluya/AgentForge.git
cd AgentForge
mvn -B -ntp clean test
```

## 创建模型

```java
ChatModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-mini")
        .build();

String answer = model.chat("Hello AgentForge");
```

:::tip
请通过环境变量管理 API Key，不要将密钥提交到仓库。
:::
