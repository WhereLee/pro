import { http } from '@/utils/request'
import type { PageResult } from '@/api/types'

export interface SysFile {
  id: string
  originalName: string
  storedName: string
  relativePath: string
  contentType: string
  size: number
  storageType: string
  directory: string
  createTime: string
}

export function pageFiles(data: Record<string, any>) {
  return http<PageResult<SysFile>>({ url: '/sys/file/page', method: 'POST', data })
}

/** 上传走原生 FormData，不经过 JSON 序列化 */
export function uploadUrl() {
  return '/api/sys/file/upload'
}

export function removeFiles(ids: string[]) {
  return http<void>({ url: '/sys/file', method: 'DELETE', data: ids })
}

export function downloadUrl(id: string) {
  return `/api/sys/file/download/${id}`
}

export function formatSize(bytes: number) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log10(bytes) / Math.log10(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`
}
