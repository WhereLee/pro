import router from '@/router'
import { useUserStore } from '@/store/user'
import { usePermissionStore } from '@/store/permission'

/** 登出进行中标记：一次页面多个请求同时 401 时，只登出一次 */
let loggingOut = false

/**
 * 统一的软登出：清理登录态 + 卸载动态权限路由 + 跳回登录页。
 *
 * 走 SPA 软跳转而非 window.location 硬刷新，原因有二：
 *   1. 免重下载/重解析整包 JS·CSS（首屏含 ~1MB vendor），切换瞬时；
 *   2. 跳向 /login 会卸载 Layout，其公告 SSE 与强制下线 SSE 连接经
 *      onBeforeUnmount 自然关闭，无需手动清理。
 *
 * 关键：依赖 permissionStore.reset() 真正 removeRoute，
 * 否则切换账号后会残留上一个角色的可访问路由（前端越权隐患）。
 */
export async function performSoftLogout(): Promise<void> {
  if (loggingOut) {
    return
  }
  loggingOut = true
  try {
    const userStore = useUserStore()
    const permissionStore = usePermissionStore()
    const current = router.currentRoute.value.fullPath
    // userStore.logout() 内部已调登出接口并清 token/userInfo，失败也会兜底清理
    await userStore.logout()
    permissionStore.reset()
    const redirect =
      current && current !== '/login' && current !== '/' ? { redirect: current } : {}
    await router.replace({ path: '/login', query: redirect })
  } finally {
    loggingOut = false
  }
}
