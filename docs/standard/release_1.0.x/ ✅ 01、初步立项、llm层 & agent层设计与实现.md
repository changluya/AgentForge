
# 背景
开始进行AgentForge开源AI Agent工程维护，之前有构建Agent Platform经验，内部进行真实构建并进行投入使用。

```shell
AgentForge
  1、基础第一版规划设计，后期核心功能迭代维护 ✅
  2、推送第一版studio模块，支持快速进行配置交互对话 ✅
```



# 开发分支
agentforge：feat_1.0.x_1





# 开发目标
## ✅ 模块构建
全局模型模块结构：

```shell
# 框架层
agentforge-framework
  agentforge-ai-agent
  agentforge-ai-core

# 模型
agentforge-model
  agentforge-modle-core
  agentforge-modle-anthropic
  agentforge-modle-openai
```





初始化构建：

1、初步实现llm层：openai协议以及anthropic协议 ✅

2、初步实现核心agent层设计与实现，包含流式 & 非流式【进度：✅】





## ✅ 统一模块维护规范
1、统一bom包维护【进度：✅】

+ 参考：[https://www.yuque.com/changlu-azwmm/ib7lmr/pzkplezcw3fp7rxo](https://www.yuque.com/changlu-azwmm/ib7lmr/pzkplezcw3fp7rxo) 即可

2、开源协议MIT ✅

3、maven支持代码格式化【进度：✅】





## ✅ 统一文档站
> 参考复刻使用 **Docusaurus文档站结构脚手架**：[https://www.yuque.com/changlu-azwmm/pq0tll/fglcgfh3mq76dtyi](https://www.yuque.com/changlu-azwmm/pq0tll/fglcgfh3mq76dtyi)
>

website：

**初步的文档站参考这个：**

**1、**初始化文档项目目录结构

| **导航项** | **含义** | **典型内容** |
| --- | --- | --- |
| **<font style="color:rgb(15, 17, 21);">Home</font>** | <font style="color:rgb(15, 17, 21);">首页</font> | <font style="color:rgb(15, 17, 21);">文档站入口、产品介绍、快速开始链接</font> |
| **<font style="color:rgb(15, 17, 21);">Document</font>** | <font style="color:rgb(15, 17, 21);">文档</font> | **<font style="color:rgb(15, 17, 21);">完整目录结构如下</font>**   <font style="color:rgb(15, 17, 21);">关于AgentForge</font>   <font style="color:rgb(15, 17, 21);">   AgentForge介绍</font>   <font style="color:rgb(15, 17, 21);">快速开始</font>   <font style="color:rgb(15, 17, 21);"> 贡献指南</font> |
| **<font style="color:rgb(15, 17, 21);">Community</font>** | <font style="color:rgb(15, 17, 21);">社区</font> | <font style="color:rgb(15, 17, 21);">论坛、Discord/Slack、GitHub、问答</font> |


2、初步支持中英文切换

3、支持版本切换，初步版本为v1.0

4、支持开源项目的快速推送到github pages，编写ci脚本到agentforge的.github下



**已发布**：

配置部分如上文档同样：

<img src="https://cdn.nlark.com/yuque/0/2026/png/2464036/1790733927371-2c30b31f-0e7a-463a-ae44-6edd6c97af4d.png" width="1220" alt="" title="" crop="0,0,1,1" id="ucc521183" class="ne-image">



## ✅ 后续统一维护平台
github：[https://github.com/changluya/AgentForge](https://github.com/changluya/AgentForge)

gitee：[https://gitee.com/changluJava/AgentForge](https://gitee.com/changluJava/AgentForge)












