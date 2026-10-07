<template>
  <div class="elegant-think-item" :class="{ running: part.status === 'thinking' }">
    <div class="elegant-think-head" @click="open = !open">
      <span class="elegant-think-title">深度思考</span>
      <span v-if="durationText" class="elegant-think-dur">{{ durationText }}</span>
      <i class="el-icon-arrow-down elegant-think-arrow" :class="{ open }"></i>
    </div>
    <el-collapse-transition>
      <div v-show="open" ref="content" class="elegant-think-content">{{ part.content || '正在思考...' }}</div>
    </el-collapse-transition>
  </div>
</template>

<script>
export default {
  name: 'ElegantThinkItem',
  props: {
    part: { type: Object, required: true }
  },
  data() {
    return { open: this.part.status === 'thinking' }
  },
  computed: {
    durationText() {
      if (this.part.status !== 'end' || !this.part.duration) return ''
      return this.part.duration < 1000
        ? `${this.part.duration}ms`
        : `${(this.part.duration / 1000).toFixed(1)}s`
    }
  },
  watch: {
    'part.status'(val) {
      if (val === 'thinking') {
        this.open = true
        this.scrollToBottom()
      }
      if (val === 'end') this.open = false
    },
    'part.content'() {
      this.scrollToBottom()
    },
    open(val) {
      if (val) this.scrollToBottom()
    }
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
.elegant-think-item { margin: 7px 0 10px; }
.elegant-think-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 0;
  cursor: pointer;
  user-select: none;
  font-size: 12.5px;
  color: #5a6472;
}
.elegant-think-head:hover .elegant-think-title { color: #53657d; }
.elegant-think-title { font-weight: 400; color: #718096; }
.elegant-think-dur { margin-left: auto; color: #98a2b3; font-size: 11px; padding: 0; }
.elegant-think-arrow { color: #b0b8c4; font-size: 11px; transition: transform 0.2s; }
.elegant-think-arrow.open { transform: rotate(180deg); }
.elegant-think-content {
  padding: 5px 2px 1px;
  color: #8a95a6;
  font-size: 12.5px;
  line-height: 1.72;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
</style>
