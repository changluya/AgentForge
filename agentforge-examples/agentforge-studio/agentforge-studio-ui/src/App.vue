<template>
  <div class="studio">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div class="brand">
          <span class="brand-logo"><img src="/logo.png" alt="AgentForge" /></span>
          <div>
            <div class="brand-name">AgentForge</div>
            <div class="brand-sub">AgentForge studio</div>
          </div>
        </div>
        <el-button size="small" icon="el-icon-plus" class="new-btn" @click="newChat">新建对话</el-button>
      </div>
      <div class="sidebar-scroll">
        <div class="section">
          <div class="section-title">当前会话</div>
          <div v-if="sessionId" class="session-item active">
            <i class="el-icon-chat-dot-round"></i><span>{{ title }}</span>
          </div>
          <div v-else class="empty">暂无会话</div>
        </div>
      </div>
    </aside>

    <main class="workspace">
      <div class="chat-header">
        <div class="header-left">
          <div class="chat-title">{{ title }}</div>
          <el-tag v-if="running" size="mini" type="success"><i class="el-icon-loading"></i> 运行中</el-tag>
        </div>
        <div class="header-right">
          <span class="model-tag">deepseek-flash</span>
          <el-select v-model="mode" size="mini" class="mode-select">
            <el-option v-for="m in renderModes" :key="m.key" :label="m.label" :value="m.key" />
          </el-select>
          <el-button size="mini" :type="showDebug ? 'primary' : 'default'" icon="el-icon-monitor" @click="showDebug = !showDebug">事件流</el-button>
        </div>
      </div>

      <div class="message-input-wrapper" :class="{ 'input-at-bottom': messages.length > 0 }">
      <section ref="feed" class="chat-scroll">
        <div class="chat-inner">
          <div v-if="!messages.length" class="welcome">
            <h2>今天想解决什么问题？</h2>
            <p>AgentForge 已就绪，支持流式回答、思考过程与工具调用展示。</p>
          </div>

          <template v-for="(message, index) in messages">
            <div v-if="message.role === 'user'" :key="index" class="message-row user">
              <div class="user-block">
                <div class="user-bubble">{{ message.text }}</div>
                <div class="msg-actions">
                  <button class="mini-btn" title="复制" @click="copy(message.text)">
                    <i class="el-icon-document-copy"></i>
                  </button>
                </div>
              </div>
            </div>
            <div v-else :key="index" class="message-row assistant">
              <div class="bot-avatar"><img src="/logo.png" alt="AgentForge" /></div>
              <div class="bot-body">
                <component :is="modeComponent" :message="message" />
                <div v-if="message.status !== 'running'" class="msg-actions">
                  <button class="mini-btn" title="复制回答" :disabled="!message.content" @click="copy(message.content)">
                    <i class="el-icon-document-copy"></i>
                  </button>
                  <span v-if="message.status === 'error'" class="status-tag error">失败</span>
                </div>
              </div>
            </div>
          </template>
        </div>
      </section>

      <div class="composer-wrap">
        <div class="composer-inner">
          <div class="composer-box" :class="{ focus: focused }">
            <textarea ref="composerInput" v-model="draft" rows="1" placeholder="给 AgentForge 发送消息..." @focus="focused=true" @blur="focused=false" @input="autoGrow" @keydown.enter.exact.prevent="send"></textarea>
            <button
              class="send-btn"
              :class="{ stop: running }"
              :disabled="!running && !draft.trim()"
              :title="running ? '停止' : '发送'"
              @click="running ? stop() : send()"
            >
              <svg v-if="!running" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 19V5" />
                <path d="M5 12l7-7 7 7" />
              </svg>
              <span v-else class="stop-square"></span>
            </button>
          </div>
          <div class="composer-tip">AI 生成内容可能不准确，请核对重要信息</div>
        </div>
      </div>
      </div>
    </main>

    <DebugDrawer v-if="showDebug" :events="rawEvents" @clear="clearRawEvents" @close="showDebug = false" />
  </div>
</template>

<script>
import DebugDrawer from './components/DebugDrawer.vue'
import { RENDER_MODES, DEFAULT_RENDER_MODE, getRenderMode } from './modes'
import { postSse } from './utils/sse'

const MODE_KEY = 'agentforge.render.mode'

function resolveInitialMode() {
  const saved = localStorage.getItem(MODE_KEY)
  return RENDER_MODES.some((m) => m.key === saved) ? saved : DEFAULT_RENDER_MODE
}

export default {
  components: { DebugDrawer },
  data: () => ({
    draft: '', focused: false, running: false, sessionId: '', controller: null, messages: [],
    rawEvents: [], rawSeq: 0, showDebug: false,
    mode: resolveInitialMode()
  }),
  computed: {
    title() { return this.messages.find((m) => m.role === 'user')?.text || '新对话' },
    renderModes() { return RENDER_MODES },
    modeComponent() { return getRenderMode(this.mode).component }
  },
  watch: {
    mode(value) { localStorage.setItem(MODE_KEY, value) }
  },
  created() {
    // 覆盖历史遗留的无效模式值，避免下拉框显示旧标识
    localStorage.setItem(MODE_KEY, this.mode)
  },
  methods: {
    newChat() { this.stop(); this.messages = []; this.sessionId = ''; this.draft = ''; this.clearRawEvents() },
    clearRawEvents() { this.rawEvents = []; this.rawSeq = 0 },
    async copy(text) {
      if (!text) return
      try {
        await navigator.clipboard.writeText(text)
        this.$message.success('已复制')
      } catch (e) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    },
    createAnswer() {
      return {
        role: 'assistant', status: 'running', parts: [], content: '', error: '',
        startedAt: Date.now(), finishedAt: null, elapsedMs: null
      }
    },
    autoGrow() {
      const el = this.$refs.composerInput
      if (!el) return
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 140) + 'px'
    },
    async send() {
      const text = this.draft.trim()
      if (!text || this.running) return
      this.draft = ''
      this.$nextTick(this.autoGrow)
      this.messages.push({ role: 'user', text })
      const answer = this.createAnswer()
      this.messages.push(answer)
      this.running = true
      this.controller = new AbortController()
      this.scroll()
      try {
        await postSse('/api/chat/stream', { sessionId: this.sessionId, message: text }, {
          signal: this.controller.signal,
          onEvent: (event) => this.handleEvent(answer, event)
        })
      } catch (error) {
        if (error.name !== 'AbortError') {
          answer.status = 'error'
          answer.error = error.message || '请求失败'
          this.finishParts(answer)
        }
      } finally {
        if (answer.status === 'running') {
          answer.status = 'success'
          this.finishParts(answer)
        }
        this.running = false
        this.controller = null
        this.scroll()
      }
    },
    handleEvent(answer, event) {
      this.rawEvents.push({ seq: ++this.rawSeq, at: Date.now(), event })
      if (this.rawEvents.length > 500) this.rawEvents.splice(0, this.rawEvents.length - 500)
      switch (event.event) {
        case 'session':
          if (event.content) this.sessionId = event.content
          break
        case 'think':
          this.applyThink(answer, event)
          break
        case 'resp':
          answer.content += event.content || ''
          break
        case 'tool_call':
          this.applyTool(answer, event)
          break
        case 'resp_end':
          answer.status = 'success'
          this.finishParts(answer)
          break
        case 'error':
          answer.status = 'error'
          answer.error = typeof event.content === 'string' ? event.content : JSON.stringify(event.content || '生成失败')
          this.finishParts(answer)
          break
        default:
          break
      }
      this.scroll()
    },
    applyThink(answer, event) {
      const open = this.findLastPart(answer, (p) => p.type === 'think' && p.status === 'thinking')
      const text = event.content || ''
      if (!event.isFinish) {
        if (open) open.content += text
        else answer.parts.push({ type: 'think', content: text, status: 'thinking', startedAt: Date.now(), duration: null })
        return
      }
      if (open) {
        if (text) open.content = text
        open.status = 'end'
        open.duration = Date.now() - (open.startedAt || Date.now())
      } else if (text) {
        answer.parts.push({ type: 'think', content: text, status: 'end', startedAt: Date.now(), duration: 0 })
      }
    },
    applyTool(answer, event) {
      const content = event.content || {}
      let part = answer.parts.find((p) => p.type === 'tool_card' && p.toolCallId && p.toolCallId === content.toolCallId)
      if (!part) {
        part = {
          type: 'tool_card', toolCallId: content.toolCallId || '', toolName: content.toolName || 'unknown',
          methodName: '', description: '', inputParams: null, outputResult: null,
          duration: null, isFinish: false, startedAt: Date.now(), finishedAt: null
        }
        answer.parts.push(part)
      }
      part.toolCallId = content.toolCallId || part.toolCallId
      part.toolName = content.toolName || part.toolName
      if (content.description) part.description = content.description
      if (content.inputParams != null) part.inputParams = content.inputParams
      if (content.outputResult != null) part.outputResult = content.outputResult
      const duration = Number(content.duration)
      if (Number.isFinite(duration) && duration > 0) part.duration = duration
      part.isFinish = !!event.isFinish
      if (part.isFinish) {
        part.finishedAt = Date.now()
        if (!(part.duration > 0)) part.duration = Math.max(100, part.finishedAt - (part.startedAt || part.finishedAt))
      }
    },
    findLastPart(answer, predicate) {
      for (let i = answer.parts.length - 1; i >= 0; i--) {
        if (predicate(answer.parts[i])) return answer.parts[i]
      }
      return null
    },
    finishParts(answer) {
      answer.parts.forEach((part) => {
        if (part.type === 'think' && part.status === 'thinking') {
          part.status = 'end'
          part.duration = Date.now() - (part.startedAt || Date.now())
        }
        if (part.type === 'tool_card' && !part.isFinish) {
          part.isFinish = true
          part.finishedAt = Date.now()
          if (!(part.duration > 0)) part.duration = part.finishedAt - (part.startedAt || part.finishedAt)
        }
      })
      if (!answer.finishedAt) answer.finishedAt = Date.now()
      if (!answer.elapsedMs) answer.elapsedMs = answer.finishedAt - (answer.startedAt || answer.finishedAt)
    },
    async stop() {
      if (!this.running) return
      if (this.sessionId) fetch(`/api/chat/stop?sessionId=${encodeURIComponent(this.sessionId)}`, { method: 'POST' }).catch(() => {})
      if (this.controller) this.controller.abort()
      this.running = false
    },
    scroll() { this.$nextTick(() => { if (this.$refs.feed) this.$refs.feed.scrollTop = this.$refs.feed.scrollHeight }) }
  }
}
</script>
