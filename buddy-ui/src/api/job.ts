import { http } from '@/utils/request'
import type { PageResult } from '@/api/types'

export interface SysJob {
  id: string
  jobName: string
  jobGroup: string
  beanName: string
  params: string
  cronExpression: string
  status: number
  concurrent: number
  misfirePolicy: number
  remark: string
}

export function pageJobs(data: Record<string, any>) {
  return http<PageResult<SysJob>>({ url: '/sys/job/page', method: 'POST', data })
}

/** 容器内可用的执行体 Bean 名称 */
export function taskBeans() {
  return http<string[]>({ url: '/sys/job/beans', method: 'GET' })
}

export function saveJob(data: Record<string, any>) {
  return http<void>({ url: '/sys/job', method: 'POST', data })
}

export function updateJob(data: Record<string, any>) {
  return http<void>({ url: '/sys/job', method: 'PUT', data })
}

export function removeJobs(ids: string[]) {
  return http<void>({ url: '/sys/job', method: 'DELETE', data: ids })
}

export function pauseJob(id: string) {
  return http<void>({ url: `/sys/job/pause/${id}`, method: 'PUT' })
}

export function resumeJob(id: string) {
  return http<void>({ url: `/sys/job/resume/${id}`, method: 'PUT' })
}

export function runJob(id: string) {
  return http<void>({ url: `/sys/job/run/${id}`, method: 'PUT' })
}
