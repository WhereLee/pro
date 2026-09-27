/**
 * 后端 VO 的前端类型定义。
 *
 * 注意：后端把 Long 统一序列化为字符串（避免 JS 大数精度丢失），
 * 因此这里所有 ID 字段都是 string 而不是 number。
 */

/** 后端统一响应体 */
export interface R<T = any> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 分页响应体 */
export interface PageResult<T = any> {
  records: T[]
  total: number
  pageNum: number
  pageSize: number
  pages: number
}

export interface LoginVO {
  token: string
  expireSeconds: number
}

export interface UserInfoVO {
  userId: string
  username: string
  nickname: string
  avatar: string
  roles: string[]
  permissions: string[]
  superAdmin: boolean
}

export interface MenuVO {
  id: string
  parentId: string
  menuName: string
  menuType: 'M' | 'C' | 'F'
  path: string
  component: string
  perms: string
  icon: string
  sort: number
  visible: number
  children: MenuVO[]
}

export interface SysUserVO {
  id: string
  username: string
  nickname: string
  email: string
  phone: string
  avatar: string
  status: number
  createTime: string
  remark: string
  roleIds: string[]
}

export interface SysRoleVO {
  id: string
  roleName: string
  roleKey: string
  sort: number
  status: number
  createTime: string
  remark: string
  menuIds: string[]
}
