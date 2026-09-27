import { defineStore } from 'pinia'
import { getRoutes } from '@/api/auth'
import type { MenuVO } from '@/api/types'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 把后端返回的组件路径映射为真实的 Vue 组件。
 *
 * 用 import.meta.glob 预扫描 views 目录：Vite 会为匹配到的文件生成动态 import，
 * 这样组件仍然是按需加载的（而不是一次性全部打进首屏）。
 * 注意 glob 的模式在构建期就已确定，不能拼接变量。
 */
const modules = import.meta.glob('../views/**/*.vue')

function loadView(component: string) {
  const key = `../views/${component}.vue`
  return modules[key] || modules['../views/error/404.vue']
}

/**
 * 菜单树 → 路由表。
 *
 * 约定（与后端 sys_menu 表设计对应）：
 *   - M 目录：只做层级容器，本身没有页面
 *   - C 菜单：有 component，生成一条路由
 *   - F 按钮：只是权限标识，不生成路由
 */
function buildRoutes(menus: MenuVO[], parentPath = ''): RouteRecordRaw[] {
  const routes: RouteRecordRaw[] = []

  for (const menu of menus) {
    if (menu.menuType === 'F') continue

    const rawPath = menu.path || ''
    const fullPath = rawPath.startsWith('/') ? rawPath : `${parentPath}/${rawPath}`

    if (menu.menuType === 'C' && menu.component) {
      routes.push({
        path: fullPath,
        name: fullPath,
        component: loadView(menu.component),
        meta: {
          title: menu.menuName,
          icon: menu.icon,
          perms: menu.perms
        }
      })
    }

    if (menu.children && menu.children.length > 0) {
      // 目录节点把自己的 path 作为子级前缀（如 /system → /system/user）
      const nextParent = menu.menuType === 'M' ? rawPath : parentPath
      routes.push(...buildRoutes(menu.children, nextParent))
    }
  }

  return routes
}

/**
 * 动态路由卸载句柄：router.addRoute() 返回的 removeRoute 闭包。
 * 用模块级数组持有而非放进 reactive state，避免函数被 Proxy 包裹；
 * 登出时统一调用即可把上次注入的权限路由干净撤下。
 */
let removers: (() => void)[] = []

export const usePermissionStore = defineStore('permission', {
  state: () => ({
    /** 后端返回的原始菜单树，用于渲染侧边栏 */
    menus: [] as MenuVO[],
    /** 由菜单树转换出的动态路由 */
    dynamicRoutes: [] as RouteRecordRaw[],
    loaded: false
  }),
  actions: {
    async generateRoutes(): Promise<RouteRecordRaw[]> {
      const menus = await getRoutes()
      this.menus = menus
      this.dynamicRoutes = buildRoutes(menus)
      this.loaded = true
      return this.dynamicRoutes
    },
    /** 登记一条已注入路由的 removeRoute 闭包 */
    addRemover(remove: () => void) {
      removers.push(remove)
    },
    reset() {
      // 先卸载动态路由再清 state，避免切换账号后残留上一个角色的可访问路由
      removers.forEach((remove) => remove())
      removers = []
      this.menus = []
      this.dynamicRoutes = []
      this.loaded = false
    }
  }
})
