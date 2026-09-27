import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// 独立的 Vitest 配置（不动 vite.config.ts，避免影响生产构建）。
// 纯逻辑/store 单测用 node 环境即可，无需浏览器。
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts']
  }
})
