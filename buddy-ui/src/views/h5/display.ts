import type { ProgressView, Tone } from '@/api/member'

/**
 * C 端展示语义的纯函数层（无 DOM、无请求，可单测）。
 *
 * 存在意义只有一条：**"是不是失败"必须由后端 tone 决定，不许由文案字符串猜**。
 * 页面里只要出现 `label.includes('失败')` 这类判断，"核实中"就可能被当成失败渲染
 * （文案改一个字就翻车）。这里把判断收敛成对 tone 的映射，并用单测钉住。
 */

export function toneClass(tone: Tone | string): string {
  switch (tone) {
    case 'danger':
      return 'is-danger'
    case 'warn':
      return 'is-warn'
    case 'success':
      return 'is-success'
    default:
      return 'is-progress'
  }
}

/** 只有 danger 才是"失败"语义；warn（核实中/待确认/人工核资）一律不是。 */
export function isFailureLike(tone: Tone | string): boolean {
  return tone === 'danger'
}

/** 是否还要继续轮询：终态且非未知才停。 */
export function shouldPoll(view: Pick<ProgressView, 'terminal' | 'displayState'> | null): boolean {
  if (!view) {
    return false
  }
  if (view.displayState === 'UNKNOWN' || view.displayState === 'NEED_CONFIRM') {
    // 未知态必须继续轮询：反查与超时驱动可能把它收敛成确定态
    return true
  }
  return !view.terminal
}

export type NextAction =
  | { type: 'start'; label: string }
  | { type: 'declare'; label: string }
  | { type: 'reorder'; label: string }
  | { type: 'wait'; label: string }

/** 用户此刻能做什么。按钮只从这里出，页面不自己拼条件。 */
export function nextAction(view: ProgressView): NextAction {
  if (view.canStart) {
    return { type: 'start', label: '打开归还仓' }
  }
  if (view.canDeclareClosed) {
    return { type: 'declare', label: '我已关好仓门' }
  }
  if (view.canReorder) {
    return { type: 'reorder', label: '再换一次' }
  }
  // 剩下的都是"等着"：包括未知态——此时最不该给的就是"重试/再来一单"
  return { type: 'wait', label: '请稍候，系统处理中' }
}

/** 拒绝原因的中文解释。未知码原样返回，不静默吞掉（现场要靠码定位问题）。 */
export function reasonText(code: string): string {
  const map: Record<string, string> = {
    REALNAME_NONE: '还未完成实名认证',
    REALNAME_PENDING: '实名认证审核中',
    REALNAME_REJECTED: '实名认证未通过',
    MEMBER_FROZEN: '账号已被冻结',
    MEMBER_CANCELLED: '账号已注销',
    MEMBER_RISK_FLAG: '账号存在风险标记，请联系客服',
    RIGHTS_INSUFFICIENT_OR_EXPIRED: '换电次数不足或套餐已到期',
    NO_RETURN_SLOT: '没有可用的归还仓',
    NO_OFFER_SLOT: '附近柜机暂时没有满电电池',
    SLOT_RACE_LOST: '仓位刚被别人占用，请重试',
    CABINET_OFFLINE: '柜机当前离线',
    CABINET_DISABLED: '柜机已停用',
    CABINET_FAULT: '柜机故障中',
    CABINET_SAFETY_LOCKED: '柜机安全锁定中',
    BATTERY_IN_FLIGHT: '你手上还有未归还的电池',
    ONE_ORDER_PER_USER: '你已有一笔换电未完成'
  }
  const prefix = code.split('#')[0]
  return map[code] ?? map[prefix] ?? code
}
