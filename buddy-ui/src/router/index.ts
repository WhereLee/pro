import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/store/user'
import { usePermissionStore } from '@/store/permission'
import { memberTokenStore } from '@/api/member'
import { ElMessage } from 'element-plus'

const Layout = () => import('@/layout/index.vue')

/** 无需登录即可访问的路由（后台） */
const WHITE_LIST = ['/login']
/** C 端 H5 自己的免登录路径 */
const H5_WHITE_LIST = ['/h5/login']

/**
 * 静态路由：所有登录用户都可见。
 * 首页不需要权限，因此放这里而不是等后端下发。
 */
export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
    meta: { hidden: true }
  },
  {
    path: '/404',
    component: () => import('@/views/error/404.vue'),
    meta: { hidden: true }
  },
  // C 端 H5：不进后台 Layout（不能带侧边栏/菜单/SSE），也不走动态权限路由。
  // 两个域共用一个前端产物，但路由与令牌彼此完全隔开。
  {
    path: '/h5/login',
    name: 'H5Login',
    component: () => import('@/views/h5/login.vue'),
    meta: { hidden: true, title: '换电登录' }
  },
  {
    path: '/h5/swap',
    name: 'H5Swap',
    component: () => import('@/views/h5/swap.vue'),
    meta: { hidden: true, title: '换电' }
  },
  {
    path: '/',
    name: 'Layout',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页', icon: 'HomeFilled' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to, _from, next) => {
  // C 端分支：用自己的令牌判定，绝不能复用后台那套（拉菜单/校权限）。
  // 混用的后果是：会员没登录就被踢到后台登录页，而后台登录页并不认 member 令牌。
  if (to.path.startsWith('/h5')) {
    if (H5_WHITE_LIST.includes(to.path)) {
      next()
      return
    }
    if (!memberTokenStore.loggedIn) {
      next({ path: '/h5/login', query: { redirect: to.fullPath }, replace: true })
      return
    }
    next()
    return
  }

  const userStore = useUserStore()
  const permissionStore = usePermissionStore()

  if (WHITE_LIST.includes(to.path)) {
    // 已登录还去登录页，直接回首页
    if (userStore.token && to.path === '/login') {
      next({ path: '/', replace: true })
      return
    }
    next()
    return
  }

  if (!userStore.token) {
    next({ path: '/login', query: { redirect: to.fullPath }, replace: true })
    return
  }

  try {
    // 首次进入需要拉取用户信息与菜单；刷新页面后 store 为空，这里会重新加载
    if (!userStore.userInfo) {
      await userStore.loadUserInfo()
    }
    if (!permissionStore.loaded) {
      const routes = await permissionStore.generateRoutes()
      // addRoute 返回 removeRoute 闭包，交 permissionStore 统一持有，登出时卸载
      routes.forEach((route) => permissionStore.addRemover(router.addRoute('Layout', route)))
      // 动态路由就绪后再挂 catch-all：若在静态路由阶段就挂，刷新/直链时目标路径会
      // 被它先匹配并 redirect 到 /404（redirect 早于 beforeEach），导致刷新误落 404
      permissionStore.addRemover(
        router.addRoute({
          path: '/:pathMatch(.*)*',
          redirect: '/404',
          meta: { hidden: true }
        })
      )
      // addRoute 后必须重新导航一次，否则当前这次跳转匹配不到新路由
      next({ ...to, replace: true })
      return
    }
    next()
  } catch (error) {
    ElMessage.error('获取用户信息失败，请重新登录')
    await userStore.logout()
    permissionStore.reset()
    next({ path: '/login', replace: true })
  }
})

export default router
