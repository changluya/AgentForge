<template>
  <div class="tool-card" :class="{ done: part.isFinish }">
    <div class="tool-head" @click="open = !open">
      <i v-if="!part.isFinish" class="el-icon-loading tool-status"></i>
      <i v-else class="el-icon-success tool-status done"></i>
      <span class="tool-name">{{ part.toolName }}</span>
      <span v-if="part.methodName" class="tool-method">{{ part.methodName }}</span>
      <span class="tool-desc">{{ part.isFinish ? '执行完成' : '执行中...' }}</span>
      <span v-if="durationText" class="tool-duration">{{ durationText }}</span>
      <i class="tool-arrow el-icon-arrow-down" :class="{ open }"></i>
    </div>
    <transition name="fade">
      <div v-if="open" class="tool-body">
        <div class="tool-meta">
          <span v-if="part.description">{{ part.description }}</span>
          <span v-if="callTimeText">调用时间：{{ callTimeText }}</span>
          <span v-if="part.toolCallId" class="mono">toolCallId: {{ part.toolCallId }}</span>
        </div>
        <div v-if="part.inputParams" class="tool-section">
          <div class="section-title"><i class="el-icon-document"></i> 入参</div>
          <pre class="json">{{ format(part.inputParams) }}</pre>
        </div>
        <div v-if="part.outputResult" class="tool-section">
          <div class="section-title"><i class="el-icon-finished"></i> 结果</div>
          <pre class="json">{{ format(part.outputResult) }}</pre>
        </div>
        <div v-if="!part.inputParams && !part.outputResult" class="tool-empty">暂无详细数据</div>
      </div>
    </transition>
  </div>
</template>

<script>
export default {
  name: 'ToolCard',
  props: {
    part: { type: Object, required: true }
  },
  data() {
    return { open: false }
  },
  computed: {
    durationText() {
      if (this.part.duration == null) return ''
      return `${this.part.duration}ms`
    },
    callTimeText() {
      if (!this.part.callTime) return ''
      return new Date(this.part.callTime).toLocaleTimeString('zh-CN')
    }
  },
  methods: {
    format(value) {
      if (value == null) return ''
      try {
        return typeof value === 'string' ? JSON.stringify(JSON.parse(value), null, 2) : JSON.stringify(value, null, 2)
      } catch (e) {
        return String(value)
      }
    }
  }
}
</script>

<style scoped>
.tool-card {
  border: 1px solid #e6e9ef;
  border-radius: 8px;
  background: #fff;
  margin: 6px 0;
  overflow: hidden;
}
.tool-card.done { border-color: #d8efdf; }
.tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  cursor: pointer;
  font-size: 12.5px;
  user-select: none;
}
.tool-head:hover { background: #fafbfc; }
.tool-status { color: #409eff; font-size: 13px; }
.tool-status.done { color: #52b788; }
.tool-name { font-weight: 600; color: #303133; }
.tool-method { color: #3b6fb6; font-family: Consolas, monospace; font-size: 11.5px; }
.tool-desc { color: #98a2b3; font-size: 11.5px; }
.tool-duration {
  margin-left: auto;
  background: #eef4fd;
  color: #3b6fb6;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 11px;
}
.tool-arrow { color: #98a2b3; transition: transform 0.2s; font-size: 12px; }
.tool-arrow.open { transform: rotate(180deg); }
.tool-body { border-top: 1px solid #eef0f4; padding: 8px 10px 10px; }
.tool-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 11.5px;
  color: #6b7280;
  margin-bottom: 8px;
}
.mono { font-family: Consolas, monospace; }
.tool-section { margin-bottom: 8px; }
.section-title { font-size: 11.5px; font-weight: 600; color: #4b5563; margin-bottom: 4px; }
.json {
  margin: 0;
  padding: 8px 10px;
  background: #f7f8fa;
  border: 1px solid #eceff4;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: Consolas, monospace;
  color: #4a5568;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.tool-empty { font-size: 12px; color: #98a2b3; padding: 6px 0; }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter, .fade-leave-to { opacity: 0; }
</style>
