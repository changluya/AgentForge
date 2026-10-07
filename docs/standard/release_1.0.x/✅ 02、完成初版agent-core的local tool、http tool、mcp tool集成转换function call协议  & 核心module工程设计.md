# 背景
支持Agent核心模块中对接local tool、http tool 以及 mcp tool的设计实现。





# 开发分支
agentforge：feat_1.0.x_2





# 核心设计思路
## 各类场景转换function call协议
### local tool
对接场景：即用于快速对接本地tool的快速封装对接来完成agent配置tool。



### http tool
对接场景：支持能够快速的将http请求来转换为agent配置tool。



### mcp tool
**对接场景：**支持能够将mcp协议tool转换为agent配置tool。

**背景：**由于AgentForge兼容jdk8，而官方的mcp的java sdk包是jdk17，所以我们采用的是**<font style="color:rgb(51, 51, 51);">混合（核心自研 + 官方桥接）。</font>**

<img src="https://cdn.nlark.com/yuque/0/2026/png/2464036/1791166709206-914ff91a-4c06-430f-bdb7-109272b628b1.png" width="1464" alt="" title="" crop="0,0,1,1" id="u983f0507" class="ne-image">

也就是说我们自研一套jdk8的兼容支持mcp协议，然后**通过桥接可以来提供官方的mcp协议的选项**。








