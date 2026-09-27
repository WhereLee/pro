import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// mock 掉后端接口：只测"菜单树 → 动态路由"的纯转换逻辑
vi.mock('@/api/auth', () => ({
  getRoutes: vi.fn()
}))

import { getRoutes } from '@/api/auth'
import { usePermissionStore } from './permission'

const menuTree = [
  {
    id: 1,
    parentId: 0,
    menuName: '系统管理',
    menuType: 'M',
    path: '/system',
    children: [
      {
        id: 2,
        parentId: 1,
        menuName: '用户管理',
        menuType: 'C',
        path: 'user',
        component: 'system/user/index',
        perms: 'sys:user:list',
        children: []
      }
    ]
  }
]

describe('permission store：菜单树 → 动态路由', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(getRoutes).mockReset()
  })

  it('目录(M)自身不生成路由，其子菜单(C)拼出绝对路径 /system/user', async () => {
    vi.mocked(getRoutes).mockResolvedValue(menuTree as never)
    const store = usePermissionStore()
    const routes = await store.generateRoutes()

    expect(store.loaded).toBe(true)
    expect(routes.some((r) => r.path === '/system/user')).toBe(true)
    // M 目录不产出以 /system 结尾的叶子路由
    expect(routes.find((r) => r.path === '/system')).toBeUndefined()
  })

  it('按钮(F)不生成路由', async () => {
    vi.mocked(getRoutes).mockResolvedValue([
      { id: 9, menuName: '新增', menuType: 'F', path: '', perms: 'sys:user:save', children: [] }
    ] as never)
    const store = usePermissionStore()
    const routes = await store.generateRoutes()
    expect(routes).toHaveLength(0)
  })

  it('reset() 清空菜单与 loaded 标记', async () => {
    vi.mocked(getRoutes).mockResolvedValue(menuTree as never)
    const store = usePermissionStore()
    await store.generateRoutes()
    store.reset()
    expect(store.loaded).toBe(false)
    expect(store.menus).toEqual([])
    expect(store.dynamicRoutes).toEqual([])
  })
})
