import { http } from '@/utils/request'
import type { PageResult } from './types'

export interface NoticeVO {
  id: string
  title: string
  content: string
  type: number
  typeDesc: string
  status: number
  statusDesc: string
  targetType: number
  targetTypeDesc: string
  targetIds: string[]
  publishTime: string
  createTime: string
}

export interface MyNoticeVO {
  id: string
  title: string
  content: string
  type: number
  publishTime: string
  unread: boolean
}

/* ========== 管理端 ========== */
export function pageNotices(data: Record<string, any>) {
  return http<PageResult<NoticeVO>>({ url: '/notice/page', method: 'POST', data })
}

/** 返回新公告 ID，便于"保存并发布"一步完成 */
export function saveNotice(data: Record<string, any>) {
  return http<string>({ url: '/notice', method: 'POST', data })
}

export function updateNotice(data: Record<string, any>) {
  return http<void>({ url: '/notice', method: 'PUT', data })
}

export function removeNotices(ids: string[]) {
  return http<void>({ url: '/notice', method: 'DELETE', data: ids })
}

export function publishNotice(id: string) {
  return http<void>({ url: `/notice/publish/${id}`, method: 'PUT' })
}

export function revokeNotice(id: string) {
  return http<void>({ url: `/notice/revoke/${id}`, method: 'PUT' })
}

/* ========== 用户端 ========== */
export function myNotices() {
  return http<MyNoticeVO[]>({ url: '/notice/mine', method: 'GET' })
}

export function unreadCount() {
  return http<number>({ url: '/notice/unread-count', method: 'GET' })
}

export function markRead(id: string) {
  return http<void>({ url: `/notice/read/${id}`, method: 'PUT' })
}
