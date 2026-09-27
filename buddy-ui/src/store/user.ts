import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, getUserInfo } from '@/api/auth'
import type { UserInfoVO } from '@/api/types'

const TOKEN_KEY = 'buddy_token'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userInfo: null as UserInfoVO | null
  }),
  getters: {
    /** 是否已拿到用户权限（用于路由守卫判断是否还需拉取信息） */
    permissions: (state): string[] => state.userInfo?.permissions ?? [],
    roles: (state): string[] => state.userInfo?.roles ?? [],
    isSuperAdmin: (state): boolean => state.userInfo?.superAdmin ?? false
  },
  actions: {
    async login(form: { username: string; password: string }) {
      const data = await loginApi(form)
      this.token = data.token
      localStorage.setItem(TOKEN_KEY, data.token)
    },

    async loadUserInfo() {
      this.userInfo = await getUserInfo()
      return this.userInfo
    },

    async logout() {
      try {
        if (this.token) {
          await logoutApi()
        }
      } catch {
        // 登出接口失败也要清理本地状态，否则用户会卡在"已登录但无权访问"的中间态
      } finally {
        this.reset()
      }
    },

    reset() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(TOKEN_KEY)
    },

    /**
     * 判断是否拥有某个权限。
     * 超级管理员直接放行——这与后端 SecurityConfig 的处理保持一致，
     * 避免前后端权限判断口径不一致导致"按钮能显示但接口报 403"。
     */
    hasPermission(perm?: string): boolean {
      if (!perm) return true
      if (this.isSuperAdmin) return true
      return this.permissions.includes(perm)
    }
  }
})
