import { http } from '@/utils/request'

/** 后台换电订单与人工干预接口。 */

export interface OrderRow {
  id: number
  order_no: string
  user_id: number
  cabinet_id: number
  return_slot_no: number | null
  offer_slot_no: number | null
  order_state: string
  right_state: string
  source: string
  create_time: string
  deadline_at: string | null
  displayState: string
}

export interface InterventionRow {
  id: number
  orderNo: string
  action: string
  applyState: string
  reason: string
  applicantId: number
  applicantName: string
  appliedAt: string
  approverId: number | null
  approverName: string | null
  approvedAt: string | null
  executedAt: string | null
  rejectReason: string | null
  execError: string | null
}

export interface OrderDetail {
  orderNo: string
  orderState: string
  displayState: string
  label: string
  tone: string
  rightState: string
  returnSlotNo: number | null
  offerSlotNo: number | null
  steps: Record<string, unknown>[]
  events: Record<string, unknown>[]
  reservations: Record<string, unknown>[]
  interventions: InterventionRow[]
}

export function pageOrders(params: { state?: string; orderNo?: string; pageNum?: number; pageSize?: number }) {
  return http<{ records: OrderRow[]; total: number }>({ url: '/swap/orders', method: 'GET', params })
}

export function orderDetail(orderNo: string) {
  return http<OrderDetail>({ url: `/swap/orders/${encodeURIComponent(orderNo)}`, method: 'GET' })
}

export function pendingInterventions(limit = 50) {
  return http<InterventionRow[]>({ url: '/swap/orders/interventions/pending', method: 'GET', params: { limit } })
}

export function applyIntervention(data: { orderNo: string; action: string; reason: string }) {
  return http<number>({ url: '/swap/orders/interventions', method: 'POST', data })
}

export function approveIntervention(id: number) {
  return http<string>({ url: `/swap/orders/interventions/${id}/approve`, method: 'POST' })
}

export function rejectIntervention(id: number, reason: string) {
  return http<void>({ url: `/swap/orders/interventions/${id}/reject`, method: 'POST', data: { reason } })
}
