// 管理页面（我的模型）——独立构建，产物输出到 dist-models/
// 与对话页面零共享：单独入口、单独输出目录、单独开发端口。
import { createAppConfig } from './build/vite.factory.js'

export default createAppConfig({
  entry: './models.html',
  outDir: 'dist-models',
  port: 5174,
  proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
})
