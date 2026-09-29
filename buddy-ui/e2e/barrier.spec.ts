import { test, expect, type Page } from '@playwright/test'

/**
 * 升降杆样例 E2E：登录 → 进入"升降杆样例 / 杆管理" → 手动开合 → 状态回显 → 事件流可见 → 权限按钮显隐。
 *
 * 前置：后端在 8200（dev/H2，建议关闭心跳调度 barrier.scheduler.enabled=false 保证确定性），
 * 前端由 playwright.config.ts 的 webServer 起在 3000（/api 代理到 8200）。
 */
async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  await page.getByPlaceholder('请输入用户名').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('Admin@123456')
  await page.getByRole('button', { name: /登\s*录/ }).click()
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 })
  await expect(page.locator('.layout-aside')).toBeVisible()
}

test.describe('升降杆样例主链路', () => {
  test('登录→杆管理→手动开合→事件流→权限显隐', async ({ page }) => {
    await login(page)

    // 展开后端下发的"升降杆样例"目录，进入"杆管理"（动态路由 /barrier/barrier）
    const barrierDir = page.locator('.layout-aside').getByText('升降杆样例')
    await expect(barrierDir).toBeVisible({ timeout: 15000 })
    await barrierDir.click()
    await page.locator('.layout-aside').getByText('杆管理').click()
    await expect(page).toHaveURL(/\/barrier\/barrier/, { timeout: 15000 })
    // 进入动态路由页后 Layout 仍在（与主链路 E2E 一致的 P0 断言）
    await expect(page.locator('.layout-aside')).toBeVisible()

    // 表格加载出至少一根杆（V3 种子杆 id=1）
    const firstRow = page.locator('.el-table__body tbody tr').first()
    await expect(firstRow).toBeVisible({ timeout: 15000 })

    // 手动"关" → 成功提示
    await firstRow.getByRole('button', { name: '关', exact: true }).click()
    await expect(page.getByText('已关闭')).toBeVisible({ timeout: 10000 })

    // 手动"开" → 成功提示
    await firstRow.getByRole('button', { name: '开', exact: true }).click()
    await expect(page.getByText('已开启')).toBeVisible({ timeout: 10000 })

    // 事件流抽屉：应看到刚才产生的"手动"事件
    await firstRow.getByRole('button', { name: '事件', exact: true }).click()
    await expect(page.locator('.el-drawer')).toBeVisible()
    await expect(page.locator('.el-drawer').getByText('手动').first()).toBeVisible({ timeout: 10000 })
    await page.keyboard.press('Escape')

    // 超级管理员：barrier:manage 对应的"新增杆"按钮可见（按钮级权限显隐正向断言）
    await expect(page.getByRole('button', { name: '新增杆' })).toBeVisible()
  })
})
