import React, {useMemo} from 'react';
import Link from '@docusaurus/Link';
import {useLocation} from '@docusaurus/router';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {useWindowSize, NavbarSecondaryMenuFiller} from '@docusaurus/theme-common';
import {useVisibleBlogSidebarItems} from '@docusaurus/plugin-content-blog/client';
import clsx from 'clsx';

import styles from './styles.module.css';

const COPY = {
  'zh-Hans': {
    title: 'AgentForge 核心模块专栏',
    model: '一、模型协议层',
    chatModel: 'ChatModel（LLM 对话协议）',
    unified: '统一协议与 Function Calling',
    providers: '主流模型协议实现',
    react: '二、通用 ReAct Agent 层',
    functionCalling: 'Function Calling 工具扩展',
    harness: '三、Harness Agent 层',
    service: '四、Agent 应用服务层',
    extras: '番外篇：工程化与生态',
    planned: '规划中',
    other: '其他文章',
    aria: 'AgentForge 博客专栏导航',
  },
  en: {
    title: 'AgentForge Core Modules',
    model: 'I. Model Protocol Layer',
    chatModel: 'ChatModel (LLM Protocol)',
    unified: 'Unified Protocol & Function Calling',
    providers: 'Model Provider Protocols',
    react: 'II. General ReAct Agent Layer',
    functionCalling: 'Function Calling Tool Extensions',
    harness: 'III. Harness Agent Layer',
    service: 'IV. Agent Application Service Layer',
    extras: 'Extras: Engineering & Ecosystem',
    planned: 'Planned',
    other: 'Other Posts',
    aria: 'AgentForge blog series navigation',
  },
};

function byDate(items) {
  return [...items].sort((a, b) => new Date(a.date) - new Date(b.date));
}

function buildTree(items, copy) {
  const buckets = {
    unified: [],
    openai: [],
    anthropic: [],
    local: [],
    http: [],
    mcp: [],
    extras: [],
    other: [],
  };

  items.forEach((item) => {
    const title = item.title.toLowerCase();
    if (title.includes('openai')) buckets.openai.push(item);
    else if (title.includes('anthropic')) buckets.anthropic.push(item);
    else if (title.includes('chatmodel')) buckets.unified.push(item);
    else if (title.includes('mcp')) buckets.mcp.push(item);
    else if (title.includes('http')) buckets.http.push(item);
    else if (title.includes('local')) buckets.local.push(item);
    else if (title.includes('maven central')) buckets.extras.push(item);
    else buckets.other.push(item);
  });

  Object.keys(buckets).forEach((key) => {
    buckets[key] = byDate(buckets[key]);
  });

  return [
    {
      label: copy.model,
      children: [
        {
          label: copy.chatModel,
          children: [
            {label: copy.unified, items: buckets.unified},
            {
              label: copy.providers,
              children: [
                {label: 'OpenAI', items: buckets.openai},
                {label: 'Anthropic', items: buckets.anthropic},
              ],
            },
          ],
        },
      ],
    },
    {
      label: copy.react,
      children: [
        {
          label: copy.functionCalling,
          children: [
            {label: 'Local Tool', items: buckets.local},
            {label: 'HTTP Tool', items: buckets.http},
            {label: 'MCP Tool', items: buckets.mcp},
          ],
        },
      ],
    },
    {label: copy.harness, planned: true},
    {label: copy.service, planned: true},
    {label: copy.extras, items: buckets.extras},
    ...(buckets.other.length ? [{label: copy.other, items: buckets.other}] : []),
  ];
}

function samePath(left, right) {
  const normalize = (value) => decodeURI(value).replace(/\/$/, '');
  return normalize(left) === normalize(right);
}

function ArticleList({items, pathname}) {
  if (!items?.length) return null;
  return (
    <ul className={styles.articleList}>
      {items.map((item) => (
        <li key={item.permalink}>
          <Link
            to={item.permalink}
            className={clsx(styles.articleLink, {
              [styles.articleLinkActive]: samePath(item.permalink, pathname),
            })}>
            {item.title}
          </Link>
        </li>
      ))}
    </ul>
  );
}

function TreeNode({node, pathname, depth = 0, plannedLabel}) {
  if (node.planned) {
    return (
      <div className={clsx(styles.plannedNode, styles[`depth${Math.min(depth, 3)}`])}>
        <span>{node.label}</span>
        <span className={styles.badge}>{plannedLabel}</span>
      </div>
    );
  }

  const hasActiveArticle = node.items?.some((item) =>
    samePath(item.permalink, pathname),
  );
  const hasActiveChild = node.children?.some((child) =>
    JSON.stringify(child).includes(decodeURI(pathname)),
  );

  return (
    <details className={styles.group} open={depth < 2 || hasActiveArticle || hasActiveChild}>
      <summary className={clsx(styles.groupLabel, styles[`depth${Math.min(depth, 3)}`])}>
        {node.label}
      </summary>
      <div className={styles.groupContent}>
        <ArticleList items={node.items} pathname={pathname} />
        {node.children?.map((child) => (
          <TreeNode
            key={child.label}
            node={child}
            pathname={pathname}
            depth={depth + 1}
            plannedLabel={plannedLabel}
          />
        ))}
      </div>
    </details>
  );
}

function SidebarTree({sidebar, mobile = false}) {
  const {pathname} = useLocation();
  const {i18n} = useDocusaurusContext();
  const copy = COPY[i18n.currentLocale] ?? COPY.en;
  const items = useVisibleBlogSidebarItems(sidebar.items);
  const tree = useMemo(() => buildTree(items, copy), [items, copy]);

  return (
    <nav
      className={clsx(styles.sidebar, 'thin-scrollbar', {[styles.mobile]: mobile})}
      aria-label={copy.aria}>
      <Link className={styles.title} to="/blog">
        {copy.title}
      </Link>
      <div className={styles.tree}>
        {tree.map((node) => (
          <TreeNode
            key={node.label}
            node={node}
            pathname={pathname}
            plannedLabel={copy.planned}
          />
        ))}
      </div>
    </nav>
  );
}

function MobileSidebar(props) {
  return <SidebarTree sidebar={props.sidebar} mobile />;
}

export default function BlogSidebar({sidebar}) {
  const windowSize = useWindowSize();
  if (!sidebar?.items.length) return null;

  if (windowSize === 'mobile') {
    return <NavbarSecondaryMenuFiller component={MobileSidebar} props={{sidebar}} />;
  }

  return (
    <aside className="col col--3">
      <SidebarTree sidebar={sidebar} />
    </aside>
  );
}
