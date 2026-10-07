<template>
  <div class="af-models">
    <!-- ------------------------------ 左侧导航 ------------------------------ -->
    <aside class="af-sidebar">
      <div class="af-sidebar__head">
        <div class="af-brand">
          <span class="af-brand__logo"><img src="/logo.png" alt="AgentForge" /></span>
          <div>
            <div class="af-brand__name">AgentForge</div>
            <div class="af-brand__sub">AgentForge Service</div>
          </div>
        </div>
      </div>

      <nav class="af-sidebar__scroll">
        <div class="af-nav-group">
          <button class="af-nav-group__head" @click="groupOpen = !groupOpen">
            <span>我的资源库</span>
            <svg
              class="af-nav-group__chevron"
              :class="{ 'af-nav-group__chevron--collapsed': !groupOpen }"
              viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round"
            >
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>

          <transition name="af-fade">
            <div v-show="groupOpen" class="af-nav-group__body">
              <button class="af-nav-item af-nav-item--active" @click="toast('当前已在「我的模型」')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z" />
                  <path d="M12 11L4 6.5M12 11l8-4.5M12 11v9" />
                </svg>
                <span class="af-nav-item__label">我的模型</span>
                <span class="af-nav-item__badge">{{ models.length }}</span>
              </button>
            </div>
          </transition>
        </div>
      </nav>

      <div class="af-sidebar__footer">
        <div class="af-user">
          <span class="af-user__avatar">A</span>
          <div class="af-user__meta">
            <div class="af-user__name">Agent 开发者</div>
            <div class="af-user__plan">专业版 · Pro</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- ------------------------------ 主工作区 ------------------------------ -->
    <main class="af-workspace">
      <header class="af-topbar">
        <div class="af-crumb">
          <span>我的资源库</span>
          <span class="af-crumb__sep">/</span>
          <span class="af-crumb__current">我的模型</span>
        </div>
        <div class="af-topbar__actions">
          <button class="af-btn af-btn--icon af-btn--ghost" title="刷新" @click="toast('已刷新模型列表')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 12a9 9 0 1 1-2.64-6.36" />
              <path d="M21 3v6h-6" />
            </svg>
          </button>
          <button class="af-btn af-btn--primary af-btn--sm" @click="toast('打开新建模型向导')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
            新建模型
          </button>
        </div>
      </header>

      <section class="af-content">
        <div class="af-content__inner">
          <div class="af-page-head">
            <div class="af-page-head__text">
              <h1 class="af-page-head__title">我的模型</h1>
              <p class="af-page-head__desc">
                集中管理已接入的大语言模型，可随时启用、停用或将其设为默认对话模型。
              </p>
            </div>
          </div>

          <!-- 工具栏 -->
          <div class="af-toolbar">
            <div class="af-search">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="7" />
                <path d="M21 21l-4.3-4.3" />
              </svg>
              <input v-model="keyword" type="text" placeholder="搜索模型名称、厂商或能力..." />
            </div>
            <div class="af-chips">
              <button
                v-for="f in filters"
                :key="f.key"
                class="af-chip"
                :class="{ 'af-chip--active': filter === f.key }"
                @click="filter = f.key"
              >
                {{ f.label }}
              </button>
            </div>
          </div>

          <!-- 统计 -->
          <div class="af-stats">
            <div class="af-stat">
              <span class="af-stat__icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z" />
                  <path d="M12 11L4 6.5M12 11l8-4.5M12 11v9" />
                </svg>
              </span>
              <div>
                <div class="af-stat__value">{{ models.length }}</div>
                <div class="af-stat__label">模型总数</div>
              </div>
            </div>
            <div class="af-stat">
              <span class="af-stat__icon af-stat__icon--green">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M20 6L9 17l-5-5" />
                </svg>
              </span>
              <div>
                <div class="af-stat__value">{{ enabledCount }}</div>
                <div class="af-stat__label">已启用</div>
              </div>
            </div>
            <div class="af-stat">
              <span class="af-stat__icon af-stat__icon--amber">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="4" width="18" height="16" rx="2" />
                  <path d="M8 4v16M3 9h5M3 15h5" />
                </svg>
              </span>
              <div>
                <div class="af-stat__value">{{ providerCount }}</div>
                <div class="af-stat__label">接入供应商</div>
              </div>
            </div>
          </div>

          <!-- 模型网格 -->
          <div v-if="filteredModels.length" class="af-grid">
            <article
              v-for="m in filteredModels"
              :key="m.id"
              class="af-model-card"
              :class="{ 'af-model-card--disabled': !m.enabled }"
            >
              <span class="af-model-card__badge">
                <span class="af-status" :class="m.enabled ? 'af-status--on' : 'af-status--off'">
                  <span class="af-status__dot"></span>{{ m.enabled ? '已启用' : '已停用' }}
                </span>
              </span>

              <div class="af-model-card__head">
                <span class="af-model-card__avatar" :style="{ '--provider': m.color }">{{ m.initial }}</span>
                <div class="af-model-card__heading">
                  <div class="af-model-card__title">
                    <span class="af-model-card__name">{{ m.name }}</span>
                    <svg v-if="m.starred" class="af-model-card__star" viewBox="0 0 24 24" fill="currentColor">
                      <path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z" />
                    </svg>
                  </div>
                  <div class="af-model-card__provider">{{ m.provider }}</div>
                </div>
              </div>

              <p class="af-model-card__desc">{{ m.desc }}</p>

              <div class="af-model-card__tags">
                <span v-for="t in m.tags" :key="t" class="af-tag">{{ t }}</span>
              </div>

              <div class="af-model-card__footer">
                <div class="af-model-card__meta">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2M4 7h16M4 7l2 12a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1l2-12" />
                  </svg>
                  <span>{{ m.ctx }} 上下文</span>
                  <span class="af-model-card__dot">·</span>
                  <span>{{ m.updated }}</span>
                </div>
                <div class="af-model-card__ops">
                  <button
                    class="af-btn af-btn--icon af-btn--ghost af-btn--sm"
                    :title="m.starred ? '取消收藏' : '收藏'"
                    @click="toggleStar(m)"
                  >
                    <svg viewBox="0 0 24 24" :fill="m.starred ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14l-5-4.87 6.91-1.01L12 2z" />
                    </svg>
                  </button>
                  <button class="af-btn af-btn--ghost af-btn--sm" @click="toast('编辑「' + m.name + '」')">编辑</button>
                </div>
              </div>
            </article>
          </div>

          <!-- 空状态 -->
          <div v-else class="af-empty">
            <span class="af-empty__icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="11" cy="11" r="7" />
                <path d="M21 21l-4.3-4.3" />
              </svg>
            </span>
            <div class="af-empty__title">没有找到匹配的模型</div>
            <div>试试更换关键词，或切换上方的筛选条件。</div>
          </div>
        </div>
      </section>
    </main>

    <transition name="af-fade">
      <div v-if="tip" class="af-toast af-toast--show">{{ tip }}</div>
    </transition>
  </div>
</template>

<script>
const MODELS = [
  {
    id: 'deepseek-v3',
    name: 'DeepSeek-V3.1',
    provider: 'DeepSeek',
    initial: 'D',
    color: '#4d6bfe',
    desc: '通用对话与工具调用兼备，性价比出色，适合作为默认对话模型。',
    ctx: '128K',
    updated: '2 天前',
    tags: ['对话', '工具调用', '中文'],
    enabled: true,
    starred: true
  },
  {
    id: 'deepseek-r1',
    name: 'DeepSeek-R1',
    provider: 'DeepSeek',
    initial: 'R',
    color: '#4d6bfe',
    desc: '面向复杂问题的深度推理模型，支持长链思考与逐步求解。',
    ctx: '64K',
    updated: '5 天前',
    tags: ['推理', '思考链'],
    enabled: true,
    starred: false
  },
  {
    id: 'gpt-4o',
    name: 'GPT-4o',
    provider: 'OpenAI',
    initial: 'G',
    color: '#10a37f',
    desc: '原生多模态模型，文本、图像与语音统一理解，响应迅速。',
    ctx: '128K',
    updated: '1 天前',
    tags: ['多模态', '视觉', '对话'],
    enabled: true,
    starred: true
  },
  {
    id: 'claude-35',
    name: 'Claude 3.5 Sonnet',
    provider: 'Anthropic',
    initial: 'C',
    color: '#d97757',
    desc: '超长上下文与强代码能力，适合文档分析与工程协作场景。',
    ctx: '200K',
    updated: '3 天前',
    tags: ['长文本', '代码', 'Agent'],
    enabled: true,
    starred: false
  },
  {
    id: 'qwen-72b',
    name: 'Qwen2.5-72B',
    provider: '阿里云',
    initial: 'Q',
    color: '#6f42c1',
    desc: '中文理解与工具调用表现均衡，支持私有化部署。',
    ctx: '128K',
    updated: '6 天前',
    tags: ['中文', '工具调用', '开源'],
    enabled: true,
    starred: false
  },
  {
    id: 'glm-4-plus',
    name: 'GLM-4-Plus',
    provider: '智谱 AI',
    initial: '智',
    color: '#2f6bff',
    desc: '中文场景优化，具备较强的指令遵循与知识问答能力。',
    ctx: '128K',
    updated: '9 天前',
    tags: ['中文', '问答'],
    enabled: false,
    starred: false
  },
  {
    id: 'gemini-15',
    name: 'Gemini 1.5 Pro',
    provider: 'Google',
    initial: 'G',
    color: '#4285f4',
    desc: '百万级超长上下文，可一次处理海量文档与音视频内容。',
    ctx: '1M',
    updated: '4 天前',
    tags: ['多模态', '超长上下文'],
    enabled: true,
    starred: false
  },
  {
    id: 'llama-31-70b',
    name: 'Llama 3.1 70B',
    provider: 'Meta',
    initial: 'L',
    color: '#0668e1',
    desc: '开源权重模型，便于本地与私有云部署，生态工具丰富。',
    ctx: '128K',
    updated: '12 天前',
    tags: ['开源', '私有化'],
    enabled: false,
    starred: false
  },
  {
    id: 'moonshot-128k',
    name: 'Moonshot-v1-128K',
    provider: '月之暗面',
    initial: 'M',
    color: '#1f2937',
    desc: '长文本处理见长，适合合同、论文等长文档的理解与摘录。',
    ctx: '128K',
    updated: '7 天前',
    tags: ['长文本', '中文'],
    enabled: true,
    starred: false
  },
  {
    id: 'doubao-pro',
    name: 'Doubao-pro',
    provider: '字节火山',
    initial: '豆',
    color: '#1664ff',
    desc: '通用对话模型，低延迟、高并发，适合大规模在线业务接入。',
    ctx: '128K',
    updated: '2 天前',
    tags: ['对话', '低延迟'],
    enabled: true,
    starred: false
  }
]

export default {
  name: 'ModelsPage',
  data() {
    return {
      groupOpen: true,
      keyword: '',
      filter: 'all',
      tip: '',
      tipTimer: null,
      filters: [
        { key: 'all', label: '全部' },
        { key: 'enabled', label: '已启用' },
        { key: 'disabled', label: '已停用' },
        { key: 'starred', label: '已收藏' }
      ],
      models: MODELS.map((m) => ({ ...m }))
    }
  },
  computed: {
    enabledCount() {
      return this.models.filter((m) => m.enabled).length
    },
    providerCount() {
      return new Set(this.models.map((m) => m.provider)).size
    },
    filteredModels() {
      const kw = this.keyword.trim().toLowerCase()
      return this.models.filter((m) => {
        if (this.filter === 'enabled' && !m.enabled) return false
        if (this.filter === 'disabled' && m.enabled) return false
        if (this.filter === 'starred' && !m.starred) return false
        if (!kw) return true
        return (
          m.name.toLowerCase().includes(kw) ||
          m.provider.toLowerCase().includes(kw) ||
          m.tags.some((t) => t.toLowerCase().includes(kw))
        )
      })
    }
  },
  beforeDestroy() {
    if (this.tipTimer) clearTimeout(this.tipTimer)
  },
  methods: {
    toggleStar(model) {
      model.starred = !model.starred
      this.toast(model.starred ? '已收藏「' + model.name + '」' : '已取消收藏')
    },
    toast(text) {
      this.tip = text
      if (this.tipTimer) clearTimeout(this.tipTimer)
      this.tipTimer = setTimeout(() => {
        this.tip = ''
      }, 1800)
    }
  }
}
</script>
