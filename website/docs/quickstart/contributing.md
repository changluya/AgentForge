---
title: 贡献指南
sidebar_label: 贡献指南
description: 参与 AgentForge 开发的基本流程
---
# 贡献指南

提交改动前，请先搜索现有 [Issues](https://github.com/changluya/AgentForge/issues) 与 [Discussions](https://github.com/changluya/AgentForge/discussions)。

1. Fork 仓库并从最新主分支创建功能分支；
2. 保持改动聚焦，并为行为变更补充测试；
3. 运行 `mvn -B -ntp clean test`；
4. 提交 Pull Request，说明动机、实现与验证结果。

公开 API 应保持厂商无关，任何密钥都不能进入日志、测试或示例。
