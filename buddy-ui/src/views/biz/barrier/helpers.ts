/**
 * 升降杆页面的纯展示辅助函数（无副作用、无框架依赖，便于单测）。
 *
 * 后端状态词：杆状态 OPEN/CLOSED；事件来源 SCHEDULED/MANUAL；手动动作 OPEN/CLOSE(D)。
 */

/** 杆状态 → 中文标签 */
export function stateLabel(state?: string | null): string {
  switch (state) {
    case 'OPEN':
      return '开启'
    case 'CLOSED':
      return '关闭'
    default:
      return state ? state : '未知'
  }
}

/** 杆状态 → el-tag 类型（开启=绿、关闭=红、其它=灰） */
export function stateTagType(state?: string | null): 'success' | 'danger' | 'info' {
  if (state === 'OPEN') return 'success'
  if (state === 'CLOSED') return 'danger'
  return 'info'
}

/** 事件来源 → 中文标签 */
export function sourceLabel(source?: string | null): string {
  switch (source) {
    case 'SCHEDULED':
      return '定时'
    case 'MANUAL':
      return '手动'
    default:
      return source ? source : '—'
  }
}

/** 计划状态 → 中文标签（计划点用 OPEN/CLOSE） */
export function planStateLabel(planState?: string | null): string {
  switch ((planState || '').toUpperCase()) {
    case 'OPEN':
      return '开启'
    case 'CLOSE':
    case 'CLOSED':
      return '关闭'
    default:
      return planState ? planState : '未知'
  }
}

/** 手动开合动作选项（提交给 /barriers/{id}/manual 的 action） */
export const ACTION_OPTIONS: { label: string; value: string }[] = [
  { label: '开启', value: 'OPEN' },
  { label: '关闭', value: 'CLOSE' }
]

/** 计划点目标状态选项 */
export const PLAN_STATE_OPTIONS: { label: string; value: string }[] = [
  { label: '开启', value: 'OPEN' },
  { label: '关闭', value: 'CLOSE' }
]

/** enabled(1/0) → el-tag 类型 */
export function enabledTagType(enabled?: number | null): 'success' | 'info' {
  return enabled === 1 ? 'success' : 'info'
}

/** enabled(1/0) → 中文标签 */
export function enabledLabel(enabled?: number | null): string {
  return enabled === 1 ? '启用' : '停用'
}
