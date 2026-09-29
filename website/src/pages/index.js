import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import Translate from '@docusaurus/Translate';
import styles from './index.module.css';

const capabilityCards = [
  {index: '01', title: 'Unified LLM', text: 'OpenAI、Anthropic 等模型共享统一接口，上层代码不被厂商协议锁定。', id: 'home.card.llm', accent: 'MODEL'},
  {index: '02', title: 'Agent Runtime', text: '围绕 ReAct 循环组织推理、行动、观察与停止，让执行过程清晰可控。', id: 'home.card.runtime', accent: 'RUNTIME'},
  {index: '03', title: 'Tool Calling', text: '类型化工具描述、参数解析和执行结果，将模型判断可靠地转化为真实行动。', id: 'home.card.tool', accent: 'ACTION'},
  {index: '04', title: 'Memory & Context', text: '把对话、运行状态与长期记忆组合为可扩展的 Agent 上下文。', id: 'home.card.memory', accent: 'CONTEXT'},
];

function RuntimePanel() {
  return <div className={styles.runtimePanel} aria-label="AgentForge runtime example">
    <div className={styles.panelBar}><div className={styles.windowDots}><i/><i/><i/></div><span>agent-run.trace</span><b>LIVE</b></div>
    <div className={styles.runtimeBody}>
      <div className={styles.runMeta}><span>RUN / AF-1042</span><span>32ms</span></div>
      <div className={styles.prompt}><span>USER</span><p><Translate id="home.demo.prompt">分析项目结构，并给出下一步实现计划。</Translate></p></div>
      <ol className={styles.trace}>
        <li><span className={styles.stepIcon}>01</span><div><b>THINK</b><p><Translate id="home.demo.think">识别目标与当前上下文</Translate></p></div><em>done</em></li>
        <li><span className={styles.stepIcon}>02</span><div><b>TOOL</b><p>repository.inspect()</p></div><em>done</em></li>
        <li className={styles.activeStep}><span className={styles.stepIcon}>03</span><div><b>ACT</b><p><Translate id="home.demo.act">生成可执行的任务计划</Translate></p></div><em>running</em></li>
      </ol>
      <div className={styles.signal}><span/><span/><span/><span/><span/><span/><span/><span/></div>
    </div>
  </div>;
}

export default function Home() {
  return <Layout title="AgentForge" description="Forge Intelligence into Action.">
    <main className={styles.page}>
      <section className={styles.hero}>
        <div className={styles.ambientGrid}/><div className={styles.glow}/>
        <div className={styles.heroInner}>
          <div className={styles.heroCopy}>
            <div className={styles.release}><span/><Translate id="home.release">AgentForge v1.0 · Java Agent Framework</Translate></div>
            <h1><Translate id="home.title.line1">让智能不止于回答</Translate><br/><span><Translate id="home.title.line2">而是真正行动。</Translate></span></h1>
            <p><Translate id="home.description">从统一 LLM 抽象到 Agent Runtime，AgentForge 为 Java 开发者提供透明、可控、可扩展的智能体工程底座。</Translate></p>
            <div className={styles.actions}>
              <Link className={styles.primaryAction} to="/docs/quickstart/getting-started"><Translate id="home.start">开始构建</Translate><span>→</span></Link>
              <Link className={styles.secondaryAction} href="https://github.com/changluya/AgentForge"><span className={styles.githubMark}>⌘</span> GitHub</Link>
            </div>
            <div className={styles.heroFacts}><span><b>Java</b> native</span><span><b>0</b> vendor lock-in</span><span><b>MIT</b> open source</span></div>
          </div>
          <RuntimePanel/>
        </div>
      </section>

      <section className={styles.flowSection}>
        <div className={styles.sectionIntro}><span>HOW IT WORKS</span><h2><Translate id="home.flow.title">一条清晰的智能执行链路</Translate></h2><p><Translate id="home.flow.desc">每一层都有明确边界，每一步都可以观察、替换与扩展。</Translate></p></div>
        <div className={styles.flow}>
          <div><small>INPUT</small><b>Goal</b><span><Translate id="home.flow.goal">自然语言目标</Translate></span></div><i>→</i>
          <div><small>REASON</small><b>Think</b><span><Translate id="home.flow.think">上下文与规划</Translate></span></div><i>→</i>
          <div><small>EXECUTE</small><b>Tool</b><span><Translate id="home.flow.tool">调用真实能力</Translate></span></div><i>→</i>
          <div className={styles.flowResult}><small>OUTPUT</small><b>Action</b><span><Translate id="home.flow.action">完成任务闭环</Translate></span></div>
        </div>
      </section>

      <section className={styles.capabilities}>
        <div className={styles.sectionIntro}><span>BUILDING BLOCKS</span><h2><Translate id="home.capabilities.title">为 Agent 工程而生</Translate></h2></div>
        <div className={styles.cardGrid}>{capabilityCards.map(card => <article key={card.index}>
          <div className={styles.cardTop}><small>{card.index}</small><span>{card.accent}</span></div><h3>{card.title}</h3><p><Translate id={card.id}>{card.text}</Translate></p><div className={styles.cardLine}/>
        </article>)}</div>
      </section>

      <section className={styles.finalCta}>
        <div><span>READY TO FORGE?</span><h2><Translate id="home.cta.title">从第一个 Agent 开始。</Translate></h2></div>
        <Link to="/docs/quickstart/getting-started"><Translate id="home.cta.action">阅读快速开始</Translate><b>↗</b></Link>
      </section>
    </main>
  </Layout>;
}
