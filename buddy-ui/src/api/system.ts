import { http } from '@/utils/request'
import type { PageResult, SysUserVO, SysRoleVO, MenuVO } from './types'

/* ========== 用户 ========== */
export function pageUsers(data: Record<string, any>) {
  return http<PageResult<SysUserVO>>({ url: '/sys/user/page', method: 'POST', data })
}

export function saveUser(data: Record<string, any>) {
  return http<void>({ url: '/sys/user', method: 'POST', data })
}

export function updateUser(data: Record<string, any>) {
  return http<void>({ url: '/sys/user', method: 'PUT', data })
}

export function removeUsers(ids: string[]) {
  return http<void>({ url: '/sys/user', method: 'DELETE', data: ids })
}

export function resetPassword(userId: string, password: string) {
  return http<void>({ url: '/sys/user/reset-password', method: 'PUT', data: { userId, password } })
}

/* ========== 角色 ========== */
export function pageRoles(data: Record<string, any>) {
  return http<PageResult<SysRoleVO>>({ url: '/sys/role/page', method: 'POST', data })
}

export function listRoles() {
  return http<SysRoleVO[]>({ url: '/sys/role/list', method: 'GET' })
}

export function saveRole(data: Record<string, any>) {
  return http<void>({ url: '/sys/role', method: 'POST', data })
}

export function updateRole(data: Record<string, any>) {
  return http<void>({ url: '/sys/role', method: 'PUT', data })
}

/** 分配权限走独立接口，日志里会以 GRANT 类型记录 */
export function grantRole(data: Record<string, any>) {
  return http<void>({ url: '/sys/role/grant', method: 'PUT', data })
}

export function removeRoles(ids: string[]) {
  return http<void>({ url: '/sys/role', method: 'DELETE', data: ids })
}

export function roleMenuIds(roleId: string) {
  return http<string[]>({ url: `/sys/role/menu/${roleId}`, method: 'GET' })
}

/* ========== 菜单 ========== */
export function menuTree() {
  return http<MenuVO[]>({ url: '/sys/menu/tree', method: 'GET' })
}

export function saveMenu(data: Record<string, any>) {
  return http<void>({ url: '/sys/menu', method: 'POST', data })
}

export function updateMenu(data: Record<string, any>) {
  return http<void>({ url: '/sys/menu', method: 'PUT', data })
}

export function removeMenu(id: string) {
  return http<void>({ url: `/sys/menu/${id}`, method: 'DELETE' })
}

/* ========== 部门 ========== */
export interface SysDeptVO {
  id: string
  parentId: string
  deptName: string
  ancestors: string
  sort: number
  leader: string
  phone: string
  status: number
  children: SysDeptVO[]
}

export function deptTree() {
  return http<SysDeptVO[]>({ url: '/sys/dept/tree', method: 'GET' })
}

export function saveDept(data: Record<string, any>) {
  return http<void>({ url: '/sys/dept', method: 'POST', data })
}

export function updateDept(data: Record<string, any>) {
  return http<void>({ url: '/sys/dept', method: 'PUT', data })
}

export function removeDept(id: string) {
  return http<void>({ url: `/sys/dept/${id}`, method: 'DELETE' })
}
