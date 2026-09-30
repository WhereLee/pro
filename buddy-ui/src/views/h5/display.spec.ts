import { describe, expect, it } from 'vitest'
import { isFailureLike, nextAction, reasonText, shouldPoll, toneClass } from './display'
import type { ProgressView } from '@/api/member'

/** 造一个进度视图，只覆盖被测字段。 */
function view(overrides: Partial<ProgressView> = {}): ProgressView {
  return {
    orderNo: 'SO1',
    orderState: 'RETURNING',
    displayState: 'RETURN_IN_PROGRESS',
    label: '归还中',
    tone: 'progress',
    hint: '放入电池后请关好仓门',
    terminal: false,
    canStart: false,
    canDeclareClosed: false,
    canReorder: false,
    returnSlotNo: 3,
    offerSlotNo: 6,
    steps: [],
    right: [],
    ...overrides
  }
}

describe('C 端展示语义', () => {
  it('未知/待确认/人工核资都不是失败样式', () => {
    // 这是本文件存在的唯一理由：这三条一旦被改成 danger，页面就会把"核实中"显示成"失败"
    expect(isFailureLike('warn')).toBe(false)
    expect(isFailureLike('progress')).toBe(false)
    expect(isFailureLike('success')).toBe(false)
    expect(toneClass('warn')).toBe('is-warn')
    expect(isFailureLike('danger')).toBe(true)
  })

  it('未知态必须继续轮询且不给"再来一单"按钮', () => {
    const unknown = view({ displayState: 'UNKNOWN', tone: 'warn', terminal: true })
    expect(shouldPoll(unknown)).toBe(true)
    expect(nextAction(unknown).type).toBe('wait')
    expect(nextAction(unknown).label).toContain('请稍')
  })

  it('待确认态给"我已关好仓门"，明确终态才停轮询', () => {
    const suspended = view({ displayState: 'NEED_CONFIRM', tone: 'warn', canDeclareClosed: true })
    expect(nextAction(suspended)).toEqual({ type: 'declare', label: '我已关好仓门' })
    expect(shouldPoll(suspended)).toBe(true)

    const completed = view({ displayState: 'SUCCESS', tone: 'success', terminal: true, canReorder: true })
    expect(shouldPoll(completed)).toBe(false)
    expect(nextAction(completed).type).toBe('reorder')
  })

  it('可开仓时优先给"打开归还仓"', () => {
    expect(nextAction(view({ canStart: true })).type).toBe('start')
  })

  it('null 视图不轮询（避免没有单号还去轮询）', () => {
    expect(shouldPoll(null)).toBe(false)
  })

  it('拒绝原因有中文解释，未知码原样透出不被吞掉', () => {
    expect(reasonText('REALNAME_NONE')).toBe('还未完成实名认证')
    expect(reasonText('NO_OFFER_SLOT')).toBe('附近柜机暂时没有满电电池')
    // 带 # 后缀的动态码（如 SLOT_STATE_IDLE_CHARGING#5）没有预置文案时**原样透出**，
    // 连后缀一起保留：仓号/电池号就在后缀里，吞掉它现场就没法定位
    expect(reasonText('SLOT_STATE_IDLE_CHARGING#5')).toBe('SLOT_STATE_IDLE_CHARGING#5')
    expect(reasonText('BRAND_NEW_CODE')).toBe('BRAND_NEW_CODE')
  })
})
