<template>
  <div class="debug-drawer">
    <div class="drawer-head">
      <span class="drawer-title"><i class="el-icon-monitor"></i> 原始事件流</span>
      <span class="event-count">{{ events.length }}</span>
      <el-button size="mini" type="text" icon="el-icon-delete" @click="$emit('clear')">清空</el-button>
      <i class="el-icon-close close-btn" @click="$emit('close')"></i>
    </div>
    <div ref="list" class="drawer-list">
      <div v-if="!events.length" class="drawer-empty">暂无事件。发起一次对话后，这里会按顺序展示每一条 SSE 事件。</div>
      <div v-for="item in events" :key="item.seq" class="event-item">
        <div class="event-line">
          <span class="event-seq">#{{ item.seq }}</span>
          <span class="event-badge" :class="badgeClass(item.event)">{{ item.event.event }}</span>
          <span v-if="item.event.type" class="event-type">{{ item.event.type }}</span>
          <span class="event-time">{{ time(item.at) }}</span>
        </div>
        <pre class="event-json">{{ pretty(item.event) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
const BADGE = {
  session: 'b-session',
  think: 'b-think',
  tool_call: 'b-tool',
  resp: 'b-resp',
  resp_end: 'b-end',
  error: 'b-error'
}

export default {
  name: 'DebugDrawer',
  props: {
    events: { type: Array, default: () => [] }
  },
  watch: {
    events() {
      this.$nextTick(() => {
        const el = this.$refs.list
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  },
  methods: {
    badgeClass(event) {
      return BADGE[event.event] || 'b-default'
    },
    time(ms) {
      return new Date(ms).toLocaleTimeString('zh-CN')
    },
    pretty(obj) {
      try {
        return JSON.stringify(obj, null, 2)
      } catch (e) {
        return String(obj)
      }
    }
  }
}
</script>

<style scoped>
.debug-drawer {
  width: 380px;
  flex-shrink: 0;
  border-left: 1px solid #eceff4;
  background: #fbfcfe;
  display: flex;
  flex-direction: column;
  height: 100%;
}
.drawer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid #eceff4;
}
.drawer-title { font-size: 13px; font-weight: 600; color: #303133; }
.event-count {
  background: #e8eefb; color: #3b6fb6; border-radius: 10px;
  padding: 0 7px; font-size: 11px; line-height: 17px;
}
.close-btn { margin-left: auto; cursor: pointer; color: #98a2b3; }
.close-btn:hover { color: #3b6fb6; }
.drawer-list { flex: 1; overflow-y: auto; padding: 8px; }
.drawer-empty { font-size: 12px; color: #b0b8c4; padding: 20px 12px; line-height: 1.7; }
.event-item {
  background: #fff;
  border: 1px solid #eef0f4;
  border-radius: 8px;
  padding: 6px 8px;
  margin-bottom: 6px;
}
.event-line { display: flex; align-items: center; gap: 6px; font-size: 11px; }
.event-seq { color: #b0b8c4; font-family: Consolas, monospace; }
.event-badge {
  border-radius: 4px; padding: 0 5px; line-height: 16px; font-weight: 600; color: #fff;
}
.b-session { background: #7c5cf0; }
.b-think { background: #8a94a6; }
.b-tool { background: #e6a23c; }
.b-resp { background: #2fa86a; }
.b-end { background: #4b5563; }
.b-error { background: #d64545; }
.b-default { background: #98a2b3; }
.event-type { color: #6b7280; }
.event-time { margin-left: auto; color: #b0b8c4; font-family: Consolas, monospace; }
.event-json {
  margin: 6px 0 0;
  font-size: 11px;
  font-family: Consolas, monospace;
  color: #4a5568;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
  background: #f7f8fa;
  border-radius: 6px;
  padding: 6px 8px;
}
</style>
