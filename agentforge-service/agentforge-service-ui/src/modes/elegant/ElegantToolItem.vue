<template>
  <div class="elegant-tool" :class="{ done: part.isFinish }">
    <div class="elegant-tool-head" @click="open = !open">
      <svg v-if="part.isFinish" class="elegant-tool-icon elegant-tool-wrench" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M14.7 6.3a4.1 4.1 0 0 0-5.1 5.1L3.5 17.5a2.1 2.1 0 0 0 3 3l6.1-6.1a4.1 4.1 0 0 0 5.1-5.1l-2.5 2.5-3-3 2.5-2.5Z"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      <i v-else class="elegant-tool-icon el-icon-loading"></i>
      <span class="elegant-tool-name">{{ part.toolName }}</span>
      <span v-if="part.isFinish && durationText" class="elegant-tool-dur">（{{ durationText }}）</span>
      <i class="el-icon-arrow-down elegant-tool-arrow" :class="{ open }"></i>
    </div>
    <el-collapse-transition>
      <div v-show="open" class="elegant-tool-body">
        <div v-if="part.inputParams" class="elegant-tool-section">
          <div class="elegant-tool-label">入参</div>
          <pre class="elegant-tool-code">{{ format(part.inputParams) }}</pre>
        </div>
        <div v-if="part.outputResult" class="elegant-tool-section">
          <div class="elegant-tool-label">结果</div>
          <pre class="elegant-tool-code">{{ format(part.outputResult) }}</pre>
        </div>
        <div v-if="!part.inputParams && !part.outputResult" class="elegant-tool-empty">暂无详情</div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script>
export default {
  name: 'ElegantToolItem',
  props: {
    part: { type: Object, required: true }
  },
  data() {
    return { open: false }
  },
  computed: {
    durationText() {
      const duration = Number(this.part.duration)
      if (!Number.isFinite(duration) || duration <= 0) return ''
      if (duration < 1000) return `${Math.max(1, Math.round(duration))}ms`
      return `${(duration / 1000).toFixed(1)}s`
    }
  },
  methods: {
    format(value) {
      if (value == null) return ''
      try {
        return typeof value === 'string'
          ? JSON.stringify(JSON.parse(value), null, 2)
          : JSON.stringify(value, null, 2)
      } catch (e) {
        return String(value)
      }
    }
  }
}
</script>

<style scoped>
.elegant-tool { margin: 7px 0 10px; }
.elegant-tool-head {
  display: flex;
  align-items: center;
  gap: 7px;
  width: fit-content;
  max-width: 100%;
  padding: 0;
  cursor: pointer;
  font-size: 12.5px;
  user-select: none;
}
.elegant-tool-head:hover .elegant-tool-name { color: #53657d; }
.elegant-tool-icon { font-size: 13px; color: #91a0b6; flex: 0 0 auto; }
.elegant-tool-wrench { width: 15px; height: 15px; }
.elegant-tool.done .elegant-tool-icon { color: #91a0b6; }
.elegant-tool-name { font-weight: 400; color: #718096; }
.elegant-tool-dur { color: #98a2b3; font-size: 11px; padding: 0; }
.elegant-tool-arrow { color: #b0b8c4; font-size: 11px; transition: transform 0.2s; }
.elegant-tool-arrow.open { transform: rotate(180deg); }
.elegant-tool-body { margin-top: 6px; padding: 9px 10px; border-radius: 6px; background: #f7f9fc; }
.elegant-tool-section { margin-bottom: 8px; }
.elegant-tool-label { font-size: 11px; color: #98a2b3; margin-bottom: 4px; }
.elegant-tool-code {
  margin: 0;
  padding: 8px 10px;
  background: #f7f8fa;
  border: 1px solid #eceff4;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: Consolas, monospace;
  color: #4a5568;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.elegant-tool-empty { font-size: 12px; color: #b0b8c4; }
</style>
