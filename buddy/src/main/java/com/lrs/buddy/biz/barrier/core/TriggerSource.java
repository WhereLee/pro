package com.lrs.buddy.biz.barrier.core;

/**
 * 一次状态切换的来源。
 */
public enum TriggerSource {
    /** 定时任务按计划点触发 */
    SCHEDULED,
    /** 非定时（手动/接口）触发 */
    MANUAL
}
