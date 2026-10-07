// 正式对话页面（chat）——独立构建，产物输出到 dist/
import { createAppConfig } from './build/vite.factory.js'

export default createAppConfig({
  entry: './index.html',
  outDir: 'dist',
  port: 5173,
  proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
})
