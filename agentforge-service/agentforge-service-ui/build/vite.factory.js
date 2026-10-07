import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
import { resolve } from 'node:path'

// 统一的 Vite 配置工厂：每个页面用独立配置 + 独立构建产物，互不打包。
export function createAppConfig({ entry, outDir, port, proxy }) {
  return defineConfig({
    plugins: [vue()],
    css: {
      preprocessorOptions: {
        scss: { api: 'modern' }
      }
    },
    server: {
      port,
      proxy
    },
    build: {
      outDir,
      emptyOutDir: true,
      rollupOptions: {
        input: resolve(process.cwd(), entry)
      }
    }
  })
}
