<template>
  <div class="think-block" :class="{ running: part.status === 'thinking' }">
    <div class="think-head" @click="open = !open">
      <span class="think-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z" />
          <path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z" />
          <path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4" />
          <path d="M17.599 6.5a3 3 0 0 0 .399-1.375" />
          <path d="M6.003 5.125A3 3 0 0 0 6.401 6.5" />
          <path d="M3.477 10.896a4 4 0 0 1 .585-.396" />
          <path d="M19.938 10.5a4 4 0 0 1 .585.396" />
          <path d="M6 18a4 4 0 0 1-1.967-.516" />
          <path d="M19.967 17.484A4 4 0 0 1 18 18" />
        </svg>
      </span>
      <span class="think-label">{{ part.status === 'thinking' ? '思考中...' : '思考过程' }}</span>
      <span v-if="durationText" class="think-duration">{{ durationText }}</span>
      <i class="think-arrow el-icon-arrow-down" :class="{ open }"></i>
    </div>
    <transition name="fade">
      <pre v-if="open && part.content" ref="content" class="think-content">{{ part.content }}</pre>
    </transition>
  </div>
</template>

<script>
export default {
  name: 'ThinkBlock',
  props: {
    part: { type: Object, required: true }
  },
  data() {
    return { open: false }
  },
  computed: {
    durationText() {
      if (this.part.status !== 'end' || !this.part.duration) return ''
      return this.part.duration < 1000 ? `${this.part.duration}ms` : `${(this.part.duration / 1000).toFixed(1)}s`
    }
  },
  watch: {
    'part.status'(val) {
      if (val === 'thinking') this.open = true
      if (val === 'end') this.open = false
    },
    'part.content'() {
      this.scrollToBottom()
    },
    open(val) {
      if (val) this.scrollToBottom()
    }
  },
  created() {
    this.open = this.part.status === 'thinking'
  },
  mounted() {
    this.scrollToBottom()
  },
  methods: {
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.content
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }
}
</script>

<style scoped>
.think-block {
  border: 1px solid #eef0f4;
  border-radius: 8px;
  background: #fbfcfe;
  margin: 6px 0;
  overflow: hidden;
}
.think-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 12.5px;
  color: #5a6472;
  user-select: none;
}
.think-block.running .think-head { color: #3b6fb6; }
.think-head i { font-size: 13px; }
.think-icon { display: inline-flex; align-items: center; }
.think-icon svg { width: 14px; height: 14px; display: block; }
.think-block.running .think-icon { animation: think-pulse 1.4s ease-in-out infinite; }
@keyframes think-pulse { 0%, 100% { opacity: 0.45; } 50% { opacity: 1; } }
.think-duration { color: #98a2b3; font-size: 11.5px; }
.think-arrow { margin-left: auto; transition: transform 0.2s; font-size: 12px; }
.think-arrow.open { transform: rotate(180deg); }
.think-content {
  margin: 0;
  padding: 0 12px 10px;
  font-size: 12.5px;
  line-height: 1.65;
  color: #4b5563;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
  font-family: inherit;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter, .fade-leave-to { opacity: 0; }
</style>
