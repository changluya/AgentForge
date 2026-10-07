<template>
  <div class="elegant-message">
    <!-- 思考过程：模型思考 + 工具调用，统一收拢为轻量事件流 -->
    <div v-if="thinkParts.length" class="elegant-thinking" :class="{ running: message.status === 'running' }">
      <div class="elegant-thinking-head" @click="open = !open">
        <span class="elegant-thinking-title">
          {{ statusTitle }}<span v-if="doneElapsed" class="elegant-thinking-elapsed"> {{ doneElapsed }}</span>
        </span>
        <i class="el-icon-arrow-down elegant-thinking-arrow" :class="{ open }"></i>
      </div>

      <el-collapse-transition>
        <div v-show="open" class="elegant-thinking-body">
          <template v-for="(part, idx) in thinkParts">
            <ElegantThinkItem v-if="part.type === 'think'" :key="'t' + idx" :part="part" />
            <ElegantToolItem v-else-if="part.type === 'tool_card'" :key="'c' + idx" :part="part" />
          </template>
        </div>
      </el-collapse-transition>
    </div>

    <!-- 最终回答 -->
    <div v-if="message.content" class="answer">
      <MarkdownView :content="message.content" />
    </div>

    <div v-if="message.status === 'running' && !message.content" class="elegant-typing">
      <span class="elegant-typing-text">正在处理</span>
    </div>

    <div v-if="message.error" class="error-box">
      <i class="el-icon-warning-outline"></i>
      <span>{{ message.error }}</span>
    </div>
  </div>
</template>

<script>
import MarkdownView from '../../components/MarkdownView.vue'
import ElegantThinkItem from './ElegantThinkItem.vue'
import ElegantToolItem from './ElegantToolItem.vue'

export default {
  name: 'ElegantMessage',
  components: { MarkdownView, ElegantThinkItem, ElegantToolItem },
  props: {
    message: { type: Object, required: true }
  },
  data() {
    return { open: this.message.status === 'running' }
  },
  computed: {
    thinkParts() {
      return (this.message.parts || []).filter((p) => p.type === 'think' || p.type === 'tool_card')
    },
    elapsed() {
      return this.message.elapsedMs ? (this.message.elapsedMs / 1000).toFixed(1) : ''
    },
    statusTitle() {
      if (this.message.status === 'running') return '正在处理'
      if (this.message.status === 'error') return '处理失败'
      return '已完成'
    },
    doneElapsed() {
      return this.message.status !== 'running' && this.elapsed ? `${this.elapsed}s` : ''
    }
  },
  watch: {
    'message.status'(val) {
      this.open = val === 'running'
    }
  }
}
</script>

<style scoped>
.elegant-thinking { margin: 0 0 12px; }
.elegant-thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  min-height: 24px;
  padding: 0;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  color: #8490a2;
}
.elegant-thinking-head:hover .elegant-thinking-title { color: #4f6f9f; }
.elegant-thinking-title { font-weight: 400; color: #7c889b; }
.elegant-thinking.running .elegant-thinking-title { color: #657791; }
.elegant-thinking-elapsed { color: #98a2b3; font-weight: 400; font-size: 11.5px; }
.elegant-thinking-arrow { color: #aeb7c4; font-size: 11px; transition: transform 0.2s; }
.elegant-thinking-arrow.open { transform: rotate(180deg); }
.elegant-thinking-body { padding: 4px 0 1px; }

.answer { margin: 2px 0 8px; }

.elegant-typing { padding: 6px 2px; }
.elegant-typing-text {
  font-size: 13.5px;
  font-weight: 500;
  letter-spacing: 0.5px;
  background: linear-gradient(90deg, #c4cdd9 0%, #c4cdd9 40%, #3b6fb6 50%, #c4cdd9 60%, #c4cdd9 100%);
  background-size: 220% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  animation: elegant-shimmer 1.8s linear infinite;
}
.elegant-typing-text::after { content: '...'; }
@keyframes elegant-shimmer {
  0% { background-position: 120% 0; }
  100% { background-position: -120% 0; }
}

.error-box {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: #fff5f5;
  border: 1px solid #ffd7d7;
  color: #d64545;
  border-radius: 8px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
</style>
