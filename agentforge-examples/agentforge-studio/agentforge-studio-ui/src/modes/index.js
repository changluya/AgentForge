import DebugMessage from './debug/DebugMessage.vue'
import ElegantMessage from './elegant/ElegantMessage.vue'

export const RENDER_MODES = [
  {
    key: 'debug',
    label: '调试模式 · 事件时间线',
    description: '完整展示 think / tool_call 事件过程',
    component: DebugMessage
  },
  {
    key: 'elegant',
    label: '优雅交互模式',
    description: '面向用户的优雅对话式渲染',
    component: ElegantMessage
  }
]

export const DEFAULT_RENDER_MODE = 'debug'

export function getRenderMode(key) {
  return RENDER_MODES.find((m) => m.key === key) || RENDER_MODES[0]
}
