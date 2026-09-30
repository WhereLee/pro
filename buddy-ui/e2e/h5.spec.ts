import { test, expect } from '@playwright/test'

/**
 * C 端 H5 主链路 E2E（B2）。
 *
 * 覆盖范围是刻意为之的：**不断言"换电完成"**。完整物理闭环需要柜机侧真的回事件，
 * 那由后端 SwapFlowTest（真 MQTT）与跨进程联跑负责；这里要保的是页面链路本身：
 *   未登录 → 落到 C 端登录页（不是后台 /login）
 *   → MOCK 通道拿验证码 → 注册即登录 → 进入换电页 → 新会员必须先实名
 * 尤其是第一条：两域共存在同一个前端产物里，串了域就是越权入口。
 */
test.describe('换电 C 端 H5', () => {
  test('未登录访问换电页应落到 C 端登录页而不是后台登录页', async ({ page }) => {
    await page.goto('/h5/swap')
    await expect(page).toHaveURL(/\/h5\/login/, { timeout: 15000 })
    await expect(page.getByPlaceholder('手机号')).toBeVisible()
    // redirect 参数必须指向 C 端自己的路径：如果落在 /login?redirect=/h5/swap（后台登录页），
    // 说明两域路由串了，会员会被拉到一个根本认不了 member 令牌的页面
    await expect(page).toHaveURL(/redirect=%2Fh5%2Fswap|redirect=\/h5\/swap/)
  })

  test('验证码登录（注册即登录）后进入换电页并提示需要实名', async ({ page }) => {
    const phone = '139' + String(Date.now()).slice(-8)
    await page.goto('/h5/login')

    await page.getByPlaceholder('手机号').fill(phone)
    await page.getByRole('button', { name: /获取验证码/ }).click()

    // 只有 MOCK 短信通道会回显验证码；接真实通道后这一步需要换成读库或测试桩
    const echo = page.locator('.echo')
    await expect(echo).toBeVisible({ timeout: 15000 })
    const text = (await echo.textContent()) ?? ''
    const code = (text.match(/\d{6}/) ?? [''])[0]
    expect(code.length).toBe(6)

    await page.getByPlaceholder('验证码').fill(code)
    await page.getByRole('button', { name: /登录 \/ 注册/ }).click()

    await expect(page).toHaveURL(/\/h5\/swap/, { timeout: 15000 })
    // 新会员额度为 0 且未实名：页面必须先引导实名，而不是直接给"开始换电"
    await expect(page.getByRole('heading', { name: '需要实名认证' })).toBeVisible({ timeout: 15000 })
  })

  test('后台登录页仍独立可用（两域互不干扰）', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByPlaceholder('请输入用户名')).toBeVisible()
    await page.getByPlaceholder('请输入用户名').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('Admin@123456')
    await page.getByRole('button', { name: /登\s*录/ }).click()
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 20000 })
  })
})
