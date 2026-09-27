import { test, expect } from '@playwright/test'

/**
 * 认证/路由主链路 E2E：把这几天手动验证过的回归固化成自动化——
 *   登录 → 点后端下发的动态菜单（P0：保留 Layout 布局）
 *   → 刷新动态路由页（catch-all 回归：不得跳 404）
 *   → 软登出（回到 /login）
 */
test.describe('buddy 管理端主链路', () => {
  test('登录→动态路由保留布局→刷新仍在→登出', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/\/login/)

    await page.getByPlaceholder('请输入用户名').fill('admin')
    await page.getByPlaceholder('请输入密码').fill('Admin@123456')
    await page.getByRole('button', { name: /登\s*录/ }).click()

    await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 })
    await expect(page.locator('.layout-aside')).toBeVisible()

    // 展开后端下发的一级目录并进入其子菜单（动态路由）
    const sysMenu = page.locator('.layout-aside').getByText('系统管理')
    await expect(sysMenu).toBeVisible({ timeout: 15000 })
    await sysMenu.click()
    await page.locator('.layout-aside').getByText('用户管理').click()

    await expect(page).toHaveURL(/\/system\/user/, { timeout: 15000 })
    // P0 断言：进入动态路由页后侧边栏（Layout）仍在
    await expect(page.locator('.layout-aside')).toBeVisible()

    // catch-all 回归：刷新不应跳到 /404
    await page.reload()
    await expect(page).toHaveURL(/\/system\/user/, { timeout: 15000 })
    await expect(page.locator('.layout-aside')).toBeVisible()

    // 软登出
    await page.locator('.user-info').click()
    await page.getByText('退出登录').click()
    await page.getByRole('button', { name: '确定' }).click()
    await expect(page).toHaveURL(/\/login/, { timeout: 15000 })
  })
})
