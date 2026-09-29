import { describe, it, expect } from 'vitest'
import {
  stateLabel,
  stateTagType,
  sourceLabel,
  planStateLabel,
  enabledLabel,
  enabledTagType,
  ACTION_OPTIONS,
  PLAN_STATE_OPTIONS
} from './helpers'

describe('升降杆展示辅助函数', () => {
  it('stateLabel：OPEN/CLOSED 映射中文，空值兜底', () => {
    expect(stateLabel('OPEN')).toBe('开启')
    expect(stateLabel('CLOSED')).toBe('关闭')
    expect(stateLabel(null)).toBe('未知')
    expect(stateLabel(undefined)).toBe('未知')
    expect(stateLabel('WEIRD')).toBe('WEIRD')
  })

  it('stateTagType：开启=success、关闭=danger、其它=info', () => {
    expect(stateTagType('OPEN')).toBe('success')
    expect(stateTagType('CLOSED')).toBe('danger')
    expect(stateTagType('')).toBe('info')
    expect(stateTagType(null)).toBe('info')
  })

  it('sourceLabel：SCHEDULED/MANUAL 映射中文，空值破折号', () => {
    expect(sourceLabel('SCHEDULED')).toBe('定时')
    expect(sourceLabel('MANUAL')).toBe('手动')
    expect(sourceLabel(null)).toBe('—')
    expect(sourceLabel('OTHER')).toBe('OTHER')
  })

  it('planStateLabel：大小写不敏感，CLOSE/CLOSED 同义', () => {
    expect(planStateLabel('OPEN')).toBe('开启')
    expect(planStateLabel('open')).toBe('开启')
    expect(planStateLabel('CLOSE')).toBe('关闭')
    expect(planStateLabel('CLOSED')).toBe('关闭')
    expect(planStateLabel(null)).toBe('未知')
  })

  it('enabled 标签/类型：1=启用/success，其它=停用/info', () => {
    expect(enabledLabel(1)).toBe('启用')
    expect(enabledLabel(0)).toBe('停用')
    expect(enabledTagType(1)).toBe('success')
    expect(enabledTagType(0)).toBe('info')
    expect(enabledTagType(null)).toBe('info')
  })

  it('动作与计划状态选项：值与后端 ActionParser/planState 对齐', () => {
    expect(ACTION_OPTIONS.map((o) => o.value)).toEqual(['OPEN', 'CLOSE'])
    expect(PLAN_STATE_OPTIONS.map((o) => o.value)).toEqual(['OPEN', 'CLOSE'])
  })
})
