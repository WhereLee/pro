import { http } from '@/utils/request'
import type { PageResult } from '@/api/types'

export interface OperateLogVO {
  id: string
  title: string
  businessType: number
  method: string
  requestMethod: string
  operatorId: string
  operatorName: string
  operUrl: string
  operIp: string
  operParam: string
  jsonResult: string
  status: number
  errorMsg: string
  costTime: number
  operTime: string
}

export function pageOperateLogs(data: Record<string, any>) {
  return http<PageResult<OperateLogVO>>({ url: '/sys/operate-log/page', method: 'POST', data })
}
