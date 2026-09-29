import { defineConfig } from '@playwright/test'

// 前端 E2E：baseURL 指向 vite dev（/api 由 vite 代理到后端 8200）。
// 本地复用已在跑的 dev server；CI 用 webServer 新起 vite，并在前置步骤启动后端 jar。
export default defineConfig({
  testDir: './e2e',
  timeout: 60000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  // 全部用例共用 admin 账号，且登出会使 Redis 在线会话失效；单 worker 串行，避免并发登录/登出互相踢下线
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:3000',
    headless: true,
    trace: 'off'
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120000
  }
})
