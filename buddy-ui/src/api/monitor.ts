import { http } from '@/utils/request'

export interface OnlineUserVO {
  userId: string
  username: string
  nickname: string
  ip: string
  userAgent: string
  loginTime: string
  lastActiveTime: string
}

/** 在线用户列表 */
export function onlineUsers() {
  return http<OnlineUserVO[]>({ url: '/monitor/online', method: 'GET' })
}

/** 强制下线 */
export function kickOut(userId: string) {
  return http<void>({ url: `/monitor/online/${userId}`, method: 'DELETE' })
}

export interface CpuInfo {
  cpuNum: number
  total: number
  sys: number
  used: number
  wait: number
  free: number
}

export interface MemInfo {
  total: number
  used: number
  free: number
  usage: number
}

export interface JvmInfo {
  name: string
  version: string
  home: string
  total: number
  used: number
  free: number
  usage: number
  startTime: string
  runTime: string
}

export interface SysFileInfo {
  dirName: string
  total: string
  free: string
  used: string
  usage: number
}

export interface SysInfo {
  computerName: string
  osName: string
  osArch: string
  userDir: string
}

export interface ServerMetrics {
  cpu: CpuInfo
  mem: MemInfo
  jvm: JvmInfo
  sysFiles: SysFileInfo[]
  sys: SysInfo
  timestamp: number
}

/** 服务器指标快照（首屏立即渲染） */
export function serverMetrics() {
  return http<ServerMetrics>({ url: '/monitor/server', method: 'GET' })
}
