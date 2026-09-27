import { http } from '@/utils/request'
import type { LoginVO, UserInfoVO, MenuVO } from './types'

export function login(data: { username: string; password: string }) {
  return http<LoginVO>({ url: '/auth/login', method: 'POST', data })
}

export function logout() {
  return http<void>({ url: '/auth/logout', method: 'POST' })
}

export function getUserInfo() {
  return http<UserInfoVO>({ url: '/auth/info', method: 'GET' })
}

/** 当前用户可见菜单，用于生成动态路由 */
export function getRoutes() {
  return http<MenuVO[]>({ url: '/auth/routes', method: 'GET' })
}
