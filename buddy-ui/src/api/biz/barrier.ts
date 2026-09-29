import { http } from '@/utils/request'

/**
 * 升降杆样例 API 客户端。
 *
 * 与后端 biz.barrier 控制器一一对应；baseURL 已含 /api（见 utils/request.ts），
 * 故此处只写控制器相对路径。后端 Long 统一序列化为字符串，ID 字段用 string。
 */

// ---------------- 类型（对齐后端 VO） ----------------
export interface BarrierVO {
  id: string
  name: string
  location: string
  enabled: number
  /** 当前状态：OPEN / CLOSED */
  state: string
  /** 是否处于手动覆盖 */
  override: boolean
}

export interface StatusVO {
  state: string
  manualOverride: boolean
  lastScheduled: string | null
}

export interface EventVO {
  id: string
  /** 事件发生后的杆状态：OPEN / CLOSED */
  state: string
  /** 触发来源：SCHEDULED / MANUAL */
  source: string
  occurredAt: string
  operatorId: string | null
}

export interface StrategyVO {
  id: string
  name: string
  description: string
  enabled: number
  priority: number
}

export interface ScheduleVO {
  id: string
  strategyId: string | null
  /** HH:mm:ss */
  timeOfDay: string
  /** OPEN / CLOSE */
  planState: string
  enabled: number
  name: string
}

export interface BarrierForm {
  name: string
  location?: string
  enabled?: number
}

export interface StrategyForm {
  name: string
  description?: string
  enabled?: number
  priority?: number
}

export interface ScheduleForm {
  strategyId?: string | null
  timeOfDay: string
  planState: string
  name?: string
  enabled?: number
}

// ---------------- 杆：运行面 + 管理面 ----------------
export function listBarriers() {
  return http<BarrierVO[]>({ url: '/barriers', method: 'GET' })
}

export function getBarrierStatus(id: string) {
  return http<StatusVO>({ url: `/barriers/${id}/status`, method: 'GET' })
}

export function listBarrierEvents(id: string, limit = 50) {
  return http<EventVO[]>({ url: `/barriers/${id}/events`, method: 'GET', params: { limit } })
}

/** 手动开合：action 取 OPEN / CLOSE。冲突时后端返回 code 409，由拦截器提示。 */
export function manualBarrier(id: string, action: string) {
  return http<StatusVO>({ url: `/barriers/${id}/manual`, method: 'POST', data: { action } })
}

export function createBarrier(data: BarrierForm) {
  return http<string>({ url: '/barriers', method: 'POST', data })
}

export function updateBarrier(id: string, data: BarrierForm) {
  return http<void>({ url: `/barriers/${id}`, method: 'PUT', data })
}

export function setBarrierEnabled(id: string, enabled: number) {
  return http<void>({ url: `/barriers/${id}/enabled`, method: 'PUT', params: { enabled } })
}

export function deleteBarrier(id: string) {
  return http<void>({ url: `/barriers/${id}`, method: 'DELETE' })
}

// ---------------- 策略：CRUD + N:M 绑杆 ----------------
export function listStrategies() {
  return http<StrategyVO[]>({ url: '/barrier/strategies', method: 'GET' })
}

export function createStrategy(data: StrategyForm) {
  return http<string>({ url: '/barrier/strategies', method: 'POST', data })
}

export function updateStrategy(id: string, data: StrategyForm) {
  return http<void>({ url: `/barrier/strategies/${id}`, method: 'PUT', data })
}

export function setStrategyEnabled(id: string, enabled: number) {
  return http<void>({ url: `/barrier/strategies/${id}/enabled`, method: 'PUT', params: { enabled } })
}

export function deleteStrategy(id: string) {
  return http<void>({ url: `/barrier/strategies/${id}`, method: 'DELETE' })
}

export function getStrategyBarriers(id: string) {
  return http<string[]>({ url: `/barrier/strategies/${id}/barriers`, method: 'GET' })
}

export function setStrategyBarriers(id: string, barrierIds: string[]) {
  return http<void>({ url: `/barrier/strategies/${id}/barriers`, method: 'PUT', data: { barrierIds } })
}

// ---------------- 计划点：CRUD ----------------
export function listSchedules() {
  return http<ScheduleVO[]>({ url: '/barrier/schedules', method: 'GET' })
}

export function createSchedule(data: ScheduleForm) {
  return http<string>({ url: '/barrier/schedules', method: 'POST', data })
}

export function updateSchedule(id: string, data: ScheduleForm) {
  return http<void>({ url: `/barrier/schedules/${id}`, method: 'PUT', data })
}

export function setScheduleEnabled(id: string, enabled: number) {
  return http<void>({ url: `/barrier/schedules/${id}/enabled`, method: 'PUT', params: { enabled } })
}

export function deleteSchedule(id: string) {
  return http<void>({ url: `/barrier/schedules/${id}`, method: 'DELETE' })
}
