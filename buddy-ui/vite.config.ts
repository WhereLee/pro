import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    open: false,
    // 后端统一上下文路径为 /api，这里直接透传，前端代码里只需写相对路径
    proxy: {
      '/api': {
        target: 'http://localhost:8200',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        /**
         * 按依赖来源拆包。
         *
         * 不拆分的话 Element Plus 与 ECharts 会被打进业务 chunk，
         * 导致首屏必须下载 1MB+ 才能渲染。拆分后这两块可以长期缓存在浏览器，
         * 业务代码迭代时用户只需重新下载很小的业务 chunk。
         */
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'vendor-echarts'
          if (id.includes('element-plus')) return 'vendor-element'
          if (id.includes('@vue') || id.includes('/vue/') || id.includes('pinia') || id.includes('vue-router')) {
            return 'vendor-vue'
          }
          return 'vendor'
        }
      }
    }
  }
})
